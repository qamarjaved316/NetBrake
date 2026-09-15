package com.netbrake.app.core

import com.netbrake.app.core.IpPacket.seqLeq
import com.netbrake.app.core.IpPacket.seqLt
import com.netbrake.app.data.Rule
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP 会话：面向应用侧实现一个极简 TCP 栈
 * （SYN 握手 / 数据 / FIN / RST / RTO 与快速重传 / 零窗口探测 / 窗口缩放），
 * 远端方向通过真实 Socket 转发（protect + 按规则绑定指定网络）。
 *
 * 方向约定：
 *  - 上行：app -> TUN -> 本类 -> socket -> 远端
 *  - 下行：远端 -> socket -> 本类 -> TUN -> app
 */
class TcpSession internal constructor(
    private val engine: Engine,
    val key: Engine.ConnKey,
    val uid: Int,
    @Volatile var rule: Rule
) {
    companion object {
        const val OUR_MSS = 1400
        const val OUR_WS_SHIFT = 6
        const val SND_CAP = 1024 * 1024      // 下行待发送缓冲上限（背压 socket 读取）
        const val SND_CWND = 64 * 1024       // 伪拥塞窗口：限制 in-flight 字节。
        // 若一口气灌满 app 通告窗口（可达 8MB），尾部所有数据都已发出后，
        // 丢失段失去 dup-ACK 触发源（快速重传依赖后续新数据乱序到达），
        // 只能等 RTO 慢慢补——传输尾段会停滞数十秒。限制 in-flight 后
        // 发送方始终留有新数据可发，丢失可被快速重传即时恢复。
        // TUN 内部 RTT 亚毫秒，64KB 的带宽时延积远超任何限速值，不约束吞吐。
        const val RCV_CAP = 512 * 1024       // 上行接收缓冲上限（决定通告窗口）
        const val RTO_INIT = 300L
        const val RTO_MAX = 8000L
        const val TLP_INTERVAL = 50L         // TLP 探测周期（ms）
        const val IDLE_TIMEOUT = 10 * 60 * 1000L
    }

    // 会话四元组（app 视角）
    val srcIp = key.srcIp
    val srcPort = key.srcPort
    val dstIp = key.dstIp
    val dstPort = key.dstPort

    private val rnd = Random()

    // ---- TCP 状态 ----
    private var state = 0                 // 0=NEW 1=SYN_RCVD 2=ESTABLISHED
    private val iss: Int = rnd.nextInt()  // 我们的初始序号
    private var rcvNxt = 0                // 期望收到的下一个序号（app 方向）
    private var sndUna = 0                // 最老未确认序号（我们发出的）
    private var sndNxt = 0                // 已分配（入队）的下一序号
    private var sentNxt = 0               // 已实际发出的下一序号
    private var appWsShift = 0
    private var appMss = OUR_MSS
    private var appWindow = 65535         // app 通告窗口（已还原缩放的真值）
    private var appFinReceived = false
    private var ourFinQueued = false
    private var ourFinSeq = 0
    private var ourFinSent = false
    private var ourFinAcked = false
    @Volatile var closed = false
        private set
    var closedAt = 0L
        private set

    // ---- 下行发送队列 ----
    /**
     * 发送段：[seq, seq+len) 为「未确认」字节；对应数组区间 [off, off+len)；
     * sentOff 为已发送到的数组偏移。段保留到被 ACK 确认才移出队列——
     * 既保住重传依据，也让 sndBufLen（未确认字节数）与背压语义一致。
     */
    private class Seg(var seq: Int, val data: ByteArray, var off: Int, var len: Int, var sentOff: Int)

    private val sndQueue = ArrayDeque<Seg>()
    private var sndBufLen = 0

    // ---- 上行接收队列 ----
    private val rcvQueue = ArrayDeque<Seg>()
    private var rcvBufLen = 0
    private var winUpdateDebt = 0 // 已消费但尚未通告窗口恢复的字节数
    // 上行乱序重组（模拟内核 ofo queue）：app 侧丢包时数据乱序到达，
    // 缓存而非丢弃 + ACK 带 SACK，让 app 内核精准重传丢失段而非整窗回退
    private val rcvOoo = java.util.TreeMap<Int, ByteArray>()
    private var rcvOooLen = 0

    // ---- 计时 ----
    private var rto = RTO_INIT
    private var lastProgress = 0L
    private var lastActivity = System.currentTimeMillis()
    private var dupAcks = 0
    // 快速重传已发出、等待 ACK 推进期间不再重复触发。
    // TUN 读线程写、定时器线程读，必须 volatile。
    @Volatile private var retransInFlight = false
    private var lastProbe = 0L
    private var lastTlp = 0L              // 上次 TLP 探测时间
    private var lastOooPrune = 0L         // 上次乱序队列兜底清理时间
    @Volatile private var lastSacks: IntArray? = null   // 对端最近通告的 SACK 块

    /** 下行单泵：同一时刻只允许一个线程执行实际发送，防止 Seg 状态被并发推进。 */
    private val pumping = AtomicBoolean(false)

    // ---- socket ----
    @Volatile private var socket: Socket? = null
    @Volatile private var sockOpen = false

    private val upBucket = TokenBucket(rule.upLimitKBps * 1024L)
    private val downBucket = TokenBucket(rule.downLimitKBps * 1024L)

    // =====================================================================
    // TUN 入包（引擎读线程调用）
    // =====================================================================

    fun handleSegment(t: IpPacket.TcpHeader, buf: ByteArray) {
        if (closed) {
            // 墓碑：对重传的 FIN 回 ACK，让对端正常关闭
            if (t.flags and IpPacket.FIN != 0) {
                sendControl(IpPacket.ACK, rcvNxt)
            }
            return
        }
        lastActivity = System.currentTimeMillis()
        if (t.flags and IpPacket.RST != 0) {
            destroy()
            return
        }
        if (t.flags and IpPacket.SYN != 0 && t.flags and IpPacket.ACK == 0) {
            onSyn(t)
            return
        }
        if (t.flags and IpPacket.ACK != 0) onAck(t)
        // 顺序要求：数据必须先于 FIN 处理。带数据的 FIN（HTTP Connection: close、
        // TLS close_notify+FIN 都是常态）其 finSeq = seq + payloadLen，
        // 若先跑 onFin，此刻 rcvNxt 尚未吃进本段数据，finSeq != rcvNxt 两个分支都不成立，
        // FIN 被静默丢弃，只能等对端重传 FIN——每条这类连接的拆除白白多一个 RTO。
        if (t.payloadLen > 0 && !appFinReceived) onData(t, buf)
        if (t.flags and IpPacket.FIN != 0) onFin(t)
    }

    private fun onSyn(t: IpPacket.TcpHeader) {
        if (state == 2) {           // SYN-ACK 丢失导致的 SYN 重传
            sendSynAck()
            return
        }
        rcvNxt = t.seq + 1
        sndUna = iss + 1
        sndNxt = iss + 1
        sentNxt = iss + 1
        appMss = if (t.mss in 536..1460) t.mss else 536
        appWsShift = if (t.wsShift in 0..14) t.wsShift else 0
        state = 1
        sendSynAck()
    }

    private fun sendSynAck() {
        val pkt = IpPacket.buildTcp(
            dstIp, srcIp, dstPort, srcPort,
            iss, rcvNxt, IpPacket.SYN or IpPacket.ACK, advertisedWindow(),
            mss = OUR_MSS, wsShift = if (appWsShift > 0) OUR_WS_SHIFT else -1
        )
        engine.writeTun(pkt, uid, rule, applyLoss = false, payloadLen = 0)
    }

    private fun onAck(t: IpPacket.TcpHeader) {
        if (state == 1 && t.ack == iss + 1) {
            state = 2
            lastProgress = System.currentTimeMillis()
            startConnector()
        }
        appWindow = (t.window.toLong() shl appWsShift).toInt().coerceAtLeast(0)
        if (t.sacks != null) lastSacks = t.sacks
        val ack = t.ack
        // 上界是 sndNxt 而非 sndNxt+1：onSockEof 已把 FIN 占用的那个字节计入 sndNxt，
        // FIN 的 ACK 恰好等于 sndNxt。放宽到 sndNxt+1 会接受一个越界 ACK，
        // 使 sndUna 越过 sentNxt，(sentNxt - sndUna) 变负、发送窗口被虚增。
        if (seqLt(sndUna, ack) && seqLeq(ack, sndNxt)) {
            val progressed = ack != sndUna
            sndUna = ack
            acknowledge(ack)
            if (ourFinSent && !ourFinAcked && seqLeq(ourFinSeq + 1, ack)) ourFinAcked = true
            if (progressed) {
                lastProgress = System.currentTimeMillis()
                rto = RTO_INIT
                dupAcks = 0
                retransInFlight = false
                if (ourFinAcked && appFinReceived) destroy() // 双向正常关闭
            }
        } else if (state == 2 && ack == sndUna && sentNxt > sndUna) {
            // 重复 ACK：app 缺失 [sndUna, ...) 的数据
            // 触发快速重传的两种信号（满足其一，且无重传在途）：
            //  a) 累计 3 个重复 ACK（无 SACK 的对端，经典判定）
            //  b) 本 ACK 携带 SACK——对端明确报告已收区间，丢包确凿，立即恢复
            // 重传在途期间不重复触发：重传段从发出到对端 ACK 有排队延迟，
            // 无节制重复触发会形成 dup-ACK 正反馈风暴（重复段再诱发 dup），
            // 发送队列被垃圾段淹没、有效速率跌至个位数 KB/s。
            dupAcks++
            if ((dupAcks >= 3 || t.sacks != null) && !retransInFlight) {
                dupAcks = 0
                retransInFlight = true
                retransmit()
            }
        }
    }

    private fun onFin(t: IpPacket.TcpHeader) {
        val finSeq = t.seq + t.payloadLen
        if (finSeq == rcvNxt) {
            rcvNxt += 1
            appFinReceived = true
            sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
            if (ourFinAcked) destroy()
        } else if (seqLt(finSeq, rcvNxt)) {
            sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
        }
    }

    private fun onData(t: IpPacket.TcpHeader, buf: ByteArray) {
        if (state < 2) return
        var seq = t.seq
        var off = t.payloadOff
        var len = t.payloadLen
        if (seqLt(seq, rcvNxt)) {            // 重传中与新数据重叠
            val overlap = rcvNxt - seq
            if (overlap >= len) {
                sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
                return
            }
            off += overlap; len -= overlap; seq += overlap
        }
        if (seq != rcvNxt) {                 // 乱序：进重组队列 + ACK+SACK（内核 ofo 行为）
            synchronized(this) {
                if (rcvBufLen + rcvOooLen + len <= RCV_CAP && !rcvOoo.containsKey(seq)) {
                    rcvOoo[seq] = buf.copyOfRange(off, off + len)
                    rcvOooLen += len
                }
            }
            sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
            return
        }
        if (len > RCV_CAP - rcvBufLen - rcvOooLen) {  // 缓冲满：丢弃，等窗口恢复
            sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
            return
        }
        val data = buf.copyOfRange(off, off + len)
        synchronized(this) {
            rcvQueue.add(Seg(rcvNxt, data, 0, len, 0))
            rcvBufLen += len
            rcvNxt += len
            harvestOooLocked()
        }
        engine.stats.addTx(uid, len)
        sendControl(IpPacket.ACK, rcvNxt, rcvSackBlocks())
    }

    /**
     * 收割乱序队列（调用方必须持有 this 锁）。
     *
     * 必须按「起点 <= rcvNxt < 终点」判定重叠而非要求起点精确等于 rcvNxt：
     * app 侧 TCP 重传时分段方式常与首发不同（Linux 会把相邻段合并后重传，
     * 一旦 upLossPct > 0 几乎必然发生），按序数据会把 rcvNxt 推过某个已缓存
     * 乱序条目的起点。老实现只认 key == rcvNxt，这类条目从此无人认领，
     * rcvOooLen 只增不减；而 advertisedWindow() = RCV_CAP - rcvBufLen - rcvOooLen，
     * 通告窗口会单调缩到 0 并开始丢弃一切新数据——上行方向就此死锁且不可恢复。
     */
    private fun harvestOooLocked() {
        while (rcvOoo.isNotEmpty()) {
            val e = rcvOoo.firstEntry() ?: break
            val start = e.key
            val data = e.value
            val end = start + data.size
            if (seqLeq(end, rcvNxt)) {          // 整段已收：陈旧副本，直接清掉
                rcvOoo.remove(start)
                rcvOooLen -= data.size
                continue
            }
            if (seqLt(rcvNxt, start)) break     // 前面还有洞，停止收割
            val skip = rcvNxt - start           // 与已收区间的重叠部分
            val take = data.size - skip
            rcvQueue.add(Seg(rcvNxt, data, skip, take, 0))
            rcvBufLen += take
            rcvOooLen -= data.size              // 整条离开乱序队列
            rcvNxt += take
            rcvOoo.remove(start)
        }
    }

    /**
     * 清理完全落在已接收区间内的乱序条目（调用方必须持有 this 锁）。
     * harvestOooLocked 已覆盖常规路径；此处兜底序号回绕期间 TreeMap 有序性
     * 与序号空间不一致导致的残留，保证 rcvOooLen 不会永久性虚高。
     */
    private fun pruneOooLocked() {
        val it = rcvOoo.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (seqLeq(e.key + e.value.size, rcvNxt)) {
                rcvOooLen -= e.value.size
                it.remove()
            }
        }
    }

    /** 计算上行乱序队列的 SACK 块（合并相邻区间，最多 3 块）。无乱序返回 null。 */
    private fun rcvSackBlocks(): IntArray? {
        synchronized(this) {
            if (rcvOoo.isEmpty()) return null
            val blocks = ArrayList<Int>(6)
            var curL = 0
            var curR = 0
            var open = false
            for ((k, v) in rcvOoo) {
                if (!open) { curL = k; curR = k + v.size; open = true }
                else if (k == curR) curR = k + v.size      // 相邻合并
                else {
                    blocks.add(curL); blocks.add(curR)
                    if (blocks.size >= 6) return blocks.toIntArray()
                    curL = k; curR = k + v.size
                }
            }
            if (open && blocks.size < 6) { blocks.add(curL); blocks.add(curR) }
            return if (blocks.isEmpty()) null else blocks.toIntArray()
        }
    }

    // =====================================================================
    // socket 连接与读写
    // =====================================================================

    private fun startConnector() {
        val kicked = engine.submit {
            try {
                if (closed) return@submit
                val sock = Socket()
                engine.hub.prepareTcp(sock, rule.netMode)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(IpPacket.ipToString(dstIp), dstPort), 15000)
                val alreadyClosed: Boolean
                synchronized(this) {
                    alreadyClosed = closed
                    if (!closed) {
                        socket = sock
                        sockOpen = true
                    }
                }
                if (alreadyClosed) {
                    try { sock.close() } catch (_: Exception) {}
                    return@submit
                }
                // 读写泵任一起不来都必须立即失败：SYN-ACK 早已发出，
                // app 眼中连接已 ESTABLISHED，静默放弃会让它一直挂到 10 分钟空闲超时
                if (!startSockReader(sock) || !startSockWriter(sock)) onSockBroken()
            } catch (_: Exception) {
                if (!closed) {
                    sendRst()
                    destroy()
                }
            }
        }
        // 线程池拒绝（池满或停机）时同理：不能把 app 晾在一条永远不会有数据的连接上
        if (!kicked && !closed) {
            sendRst()
            destroy()
        }
    }

    private fun startSockReader(sock: Socket): Boolean =
        engine.submit {
            val buf = ByteArray(64 * 1024)
            try {
                val input = sock.getInputStream()
                while (sockOpen && !closed) {
                    if (synchronized(this) { sndBufLen } >= SND_CAP) {
                        Thread.sleep(20)      // 背压：暂停读 socket
                        continue
                    }
                    val n = input.read(buf)
                    if (n < 0) {
                        onSockEof()
                        break
                    }
                    if (n > 0) enqueueDownstream(buf, n)
                }
            } catch (_: Exception) {
                onSockBroken()
            }
        }

    private fun startSockWriter(sock: Socket): Boolean =
        engine.submit {
            try {
                val output = sock.getOutputStream()
                // 注意：对端 EOF（半关闭）后上行方向仍可写，退出只看 closed
                while (!closed) {
                    val seg: Seg? = synchronized(this) { rcvQueue.peekFirst() }
                    if (seg == null) {
                        if (appFinReceived) {
                            try { sock.shutdownOutput() } catch (_: Exception) {}
                            break
                        }
                        Thread.sleep(10)
                        continue
                    }
                    val want = minOf(seg.len, 64 * 1024)
                    val got = upBucket.acquire(want, minOf(want, 256), 500) { closed }
                    if (got <= 0) continue
                    try {
                        output.write(seg.data, seg.off, got)
                        output.flush()
                    } catch (_: IOException) {
                        onSockBroken()
                        return@submit
                    }
                    synchronized(this) {
                        seg.off += got
                        seg.len -= got
                        rcvBufLen -= got
                        winUpdateDebt += got
                        if (seg.len == 0) rcvQueue.pollFirst()
                    }
                    // 窗口更新：攒够 4KB 或队列已空时通告，避免 app 发送窗口卡死
                    val debt = synchronized(this) { winUpdateDebt }
                    if (debt >= 4096 || synchronized(this) { rcvQueue.isEmpty() }) {
                        sendControl(IpPacket.ACK, rcvNxt)
                        synchronized(this) { winUpdateDebt = 0 }
                    }
                }
            } catch (_: Exception) {
            }
        }

    // =====================================================================
    // 下行：socket -> TUN -> app
    // =====================================================================

    private fun enqueueDownstream(buf: ByteArray, n: Int) {
        synchronized(this) {
            sndQueue.add(Seg(sndNxt, buf.copyOf(n), 0, n, 0))
            sndBufLen += n
            sndNxt += n
        }
        pumpDown()
    }

    /**
     * 尽力发送下行数据（受 app 接收窗口与下行令牌桶约束）。
     *
     * 单泵 + 后台驱动设计（修复三问题）：
     *  1. 并发调用（ACK 路径 / socket 读路径 / 定时器）只允许一个实际发送者，
     *     防止 Seg.off/seq 被双重推进导致越界或发乱序段（会诱发对端 RST）；
     *  2. 令牌不足时泵不退出（限速节奏下持续流出，修复限速死锁）；
     *  3. 泵跑在线程池而非调用者线程——调用者可能是全局唯一的 TUN 读线程，
     *     绝不能被限速节奏阻塞数秒。
     */
    private fun pumpDown() {
        if (closed) return
        if (!pumping.compareAndSet(false, true)) return   // 已有泵在场，它自会处理新数据
        val kicked = engine.submit(Runnable {
            try {
                pumpLoop()
            } finally {
                pumping.set(false)
                // 退出后若又有可发数据（竞态窗口内到达），再踢一轮
                if (hasSendableWork()) pumpDown()
            }
        })
        if (!kicked) pumping.set(false)
    }

    private fun hasSendableWork(): Boolean {
        if (closed) return false
        synchronized(this) {
            if (sndQueue.any { it.sentOff < it.off + it.len }) {
                return minOf(appWindow, SND_CWND) - (sentNxt - sndUna) > 0
            }
            return false
        }
    }

    /** 实际发送循环（仅在线程池中由单泵执行）。 */
    private fun pumpLoop() {
        // 注意：socket EOF 后仍需继续把已缓冲数据与 FIN 发完，条件只看 closed
        while (!closed) {
            var seg: Seg?
            var win: Int
            synchronized(this) {
                seg = sndQueue.firstOrNull { it.sentOff < it.off + it.len }
                win = minOf(appWindow, SND_CWND) - (sentNxt - sndUna)
            }
            if (seg == null) {
                maybeSendFin()
                return
            }
            if (win <= 0) return               // 等 app ACK 后由 acknowledge() 再次驱动
            val n = minOf(seg!!.off + seg!!.len - seg!!.sentOff, appMss, win)
            val got = downBucket.acquire(n, minOf(n, 256), 200) { closed }
            if (got <= 0) continue             // 令牌不足：不退出，按限速节奏继续
            transmit(seg!!, got, retrans = false)
        }
    }

    /**
     * 发送（retrans=false）或重传（retrans=true）下行数据。
     * 新发送推进 sentOff / sentNxt；重传只重发已发送过的未确认区间，不改状态。
     * 丢包模拟：命中概率时该段不写入 TUN，但序号照常推进，等待 app 触发重传。
     */
    private fun transmit(seg: Seg, requested: Int, retrans: Boolean) {
        val seq: Int
        val dataOff: Int
        val n: Int
        synchronized(this) {
            if (retrans) {
                // 从未确认区间头部起，最多重发到已发送位置
                var len = minOf(requested, seg.len, seg.sentOff - seg.off)
                if (len <= 0) return
                seq = seg.seq
                dataOff = seg.off
                n = len
            } else {
                // 请求发出到取得令牌之间段可能被 ACK 截断：按剩余未发送量钳制
                var len = requested
                val remain = seg.off + seg.len - seg.sentOff
                if (len > remain) len = remain
                if (len <= 0) return
                seq = seg.seq + (seg.sentOff - seg.off)
                dataOff = seg.sentOff
                seg.sentOff += len
                sentNxt += len
                n = len
            }
            lastProgress = System.currentTimeMillis()
        }
        val pkt = IpPacket.buildTcp(
            dstIp, srcIp, dstPort, srcPort,
            seq, rcvNxt, IpPacket.ACK or IpPacket.PSH, advertisedWindow(),
            seg.data, dataOff, n
        )
        engine.writeTun(pkt, uid, rule, applyLoss = true, payloadLen = n)
    }

    /** 全部数据已发送（序号推进到 FIN 位置）后发送 FIN（无需等 ACK，允许 FIN 前有未确认数据）。 */
    private fun maybeSendFin() {
        var sendIt = false
        var finSeq = 0
        synchronized(this) {
            if (ourFinQueued && !ourFinSent && sentNxt == ourFinSeq) {
                ourFinSent = true
                finSeq = ourFinSeq
                sendIt = true
            }
        }
        if (sendIt) {
            val pkt = IpPacket.buildTcp(
                dstIp, srcIp, dstPort, srcPort,
                finSeq, rcvNxt, IpPacket.FIN or IpPacket.ACK, advertisedWindow()
            )
            engine.writeTun(pkt, uid, rule, applyLoss = true, payloadLen = 0)
            lastProgress = System.currentTimeMillis()
        }
    }

    /** socket 读到 EOF：对端关闭，排空缓冲后向 app 发 FIN。 */
    private fun onSockEof() {
        synchronized(this) {
            if (!ourFinQueued) {
                ourFinQueued = true
                ourFinSeq = sndNxt
                sndNxt += 1
            }
        }
        sockOpen = false
        pumpDown()
    }

    private fun onSockBroken() {
        if (!closed) {
            sendRst()
            destroy()
        }
    }

    // =====================================================================
    // 引擎定时器（约 50ms 一次）
    // =====================================================================

    fun onTimer(now: Long) {
        if (closed) return
        if (now - lastActivity > IDLE_TIMEOUT) {
            sendRst()
            destroy()
            return
        }
        // 兜底清理陈旧乱序条目（每秒一次，仅在队列非空时扫描）
        if (now - lastOooPrune >= 1000) {
            lastOooPrune = now
            synchronized(this) { if (rcvOoo.isNotEmpty()) pruneOooLocked() }
        }
        val needRetrans = synchronized(this) {
            ((sentNxt > sndUna) || (ourFinSent && !ourFinAcked)) &&
                    now - lastProgress >= rto && state == 2
        }
        if (needRetrans) {
            rto = minOf(rto * 2, RTO_MAX)
            lastProgress = now
            lastTlp = now
            // 超时即说明上一次重传（若有）也已丢失，必须解除在途标记：
            // 否则快速重传（见 onAck）与下方 TLP 会被同一个标记双双锁死，
            // 恢复退化为纯 RTO + 指数退避到 8s——正是 TLP 要避免的崩溃场景。
            retransInFlight = false
            retransmit()
            return
        }
        // TLP（Tail Loss Probe，RFC 8985）：发送窗口已满、无新段可发时，
        // 重复 ACK 的源头枯竭（对端收不到任何新数据），快速重传失效，
        // 只能靠 300ms 起步的 RTO 逐洞恢复——高丢包下有效速率暴跌。
        // 周期性重传一个 MSS 作探针：探针到达即触发对端 ACK 前进
        // （若为真洞）或 dup+SACK（若重复），重新点燃快速恢复链。
        val needTlp = synchronized(this) {
            sentNxt > sndUna && state == 2 && !retransInFlight && now - lastTlp >= TLP_INTERVAL
        }
        if (needTlp) {
            lastTlp = now
            retransmit()
            return
        }
        val zeroWindow = synchronized(this) {
            sndBufLen > 0 && appWindow - (sentNxt - sndUna) <= 0
        }
        if (zeroWindow && now - lastProbe >= 500) {
            lastProbe = now
            // 标准零窗口探测：只允许发送 1 字节（已发送过则作重传发，否则作新数据发）
            val seg = synchronized(this) { sndQueue.peekFirst() }
            if (seg != null && seg.len > 0) {
                transmit(seg, 1, retrans = seg.sentOff > seg.off)
            }
        }
    }

    /**
     * SACK 感知的重传：优先补接收端真正缺失的第一个洞。
     * 洞的确定依据对端 SACK 通告（已收区间），第一个洞终点 =
     *   sndUna 之后第一个 SACK 块的左缘（无 SACK 时默认一个 MSS）。
     * 单轮最多补 4×MSS（连续洞一次补多段，避免逐段往返）。
     */
    private fun retransmit() {
        val seg: Seg? = synchronized(this) { sndQueue.peekFirst() }
        if (seg != null && seg.len > 0 && seg.sentOff > seg.off) {
            var len = minOf(seg.sentOff - seg.off, appMss)   // 无 SACK：保守一个 MSS
            val sk = lastSacks
            if (sk != null && sk.size >= 2) {
                // 过滤已确认（<= sndUna）的过期块，找第一个有效块的左缘
                var i = 0
                while (i + 1 < sk.size && !seqLt(sndUna, sk[i])) i += 2
                if (i + 1 < sk.size && seqLt(sndUna, sk[i])) {
                    val hole = sk[i] - sndUna
                    if (hole > 0) len = minOf(hole, seg.sentOff - seg.off, appMss * 4)
                }
            }
            if (len > 0) transmit(seg, len, retrans = true)
        } else if (ourFinSent && !ourFinAcked) {
            val pkt = IpPacket.buildTcp(
                dstIp, srcIp, dstPort, srcPort,
                ourFinSeq, rcvNxt, IpPacket.FIN or IpPacket.ACK, advertisedWindow()
            )
            engine.writeTun(pkt, uid, rule, applyLoss = true, payloadLen = 0)
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** app ACK 推进 sndUna 后，从发送队列头部移除已确认数据并继续泵。 */
    private fun acknowledge(ack: Int) {
        synchronized(this) {
            while (sndQueue.isNotEmpty()) {
                val seg = sndQueue.peekFirst()!!
                if (seqLt(seg.seq, ack)) {
                    val segEnd = seg.seq + seg.len
                    if (seqLeq(ack, segEnd)) {
                        val cut = ack - seg.seq
                        seg.seq = ack
                        seg.off += cut
                        seg.len -= cut
                        sndBufLen -= cut
                        if (seg.len == 0) sndQueue.pollFirst()
                        break
                    } else {
                        sndBufLen -= seg.len
                        sndQueue.pollFirst()
                    }
                } else break
            }
        }
        pumpDown()
    }

    private fun advertisedWindow(): Int {
        val free = (RCV_CAP - rcvBufLen - rcvOooLen).coerceAtLeast(0)
        val shift = if (appWsShift > 0) OUR_WS_SHIFT else 0
        return (free ushr shift).coerceIn(0, 65535)
    }

    private fun sendControl(flags: Int, ack: Int, sack: IntArray? = null) {
        val pkt = IpPacket.buildTcp(
            dstIp, srcIp, dstPort, srcPort,
            sentNxt, ack, flags, advertisedWindow(), sack = sack
        )
        engine.writeTun(pkt, uid, rule, applyLoss = false, payloadLen = 0)
    }

    private fun sendRst() {
        val pkt = IpPacket.buildTcp(
            dstIp, srcIp, dstPort, srcPort,
            sndNxt, rcvNxt, IpPacket.RST or IpPacket.ACK, 0
        )
        engine.writeTun(pkt, uid, rule, applyLoss = false, payloadLen = 0)
    }

    /** 规则热更新（限速立即生效）。 */
    fun applyRule(newRule: Rule) {
        rule = newRule
        upBucket.rate = newRule.upLimitKBps * 1024L
        downBucket.rate = newRule.downLimitKBps * 1024L
    }

    /**
     * 因规则变更、网络丢失等外部原因主动断开：先发 RST 再拆会话。
     * 只 destroy 不发 RST 会让 app 的 socket 一直挂着（它不知道对端没了），
     * 直到它自己的超时才失败。
     */
    fun kill() {
        if (closed) return
        sendRst()
        destroy()
    }

    fun destroy() {
        if (closed) return
        closed = true
        closedAt = System.currentTimeMillis()
        synchronized(this) {
            sockOpen = false
            try { socket?.close() } catch (_: Exception) {}
        }
        engine.onSessionClosed(this)
    }

    override fun toString(): String =
        "Tcp(${IpPacket.ipToString(srcIp)}:$srcPort -> ${IpPacket.ipToString(dstIp)}:$dstPort)"
}
