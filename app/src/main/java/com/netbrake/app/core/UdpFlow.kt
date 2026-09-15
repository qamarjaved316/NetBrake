package com.netbrake.app.core

import com.netbrake.app.data.Rule
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.ArrayDeque

/**
 * UDP 流：按 (app源, 远端目标) 四元组复用一个出站 socket。
 * 上行经令牌桶限速后由 socket 发出；下行收到后经丢包/限速判定写回 TUN。
 */
class UdpFlow internal constructor(
    private val engine: Engine,
    val key: Engine.ConnKey,
    val uid: Int,
    @Volatile var rule: Rule
) {
    companion object {
        const val UP_CAP = 256 * 1024
        const val IDLE_TIMEOUT = 60_000L
        const val MAX_DGRAM = 65_507
    }

    val srcIp = key.srcIp
    val srcPort = key.srcPort
    val dstIp = key.dstIp
    val dstPort = key.dstPort

    private class Dgram(val data: ByteArray, val len: Int)

    private val upQueue = ArrayDeque<Dgram>()
    private var upLen = 0
    @Volatile private var lastActive = System.currentTimeMillis()
    @Volatile private var closed = false

    private var socket: DatagramSocket? = null
    private val upBucket = TokenBucket(rule.upLimitKBps * 1024L)
    private val downBucket = TokenBucket(rule.downLimitKBps * 1024L)

    /** 由引擎创建后立即调用（在引擎读线程中）。 */
    fun start() {
        val kicked = engine.submit {
            try {
                val sock = DatagramSocket(null)
                engine.hub.prepareUdp(sock, rule.netMode)
                sock.connect(InetSocketAddress(IpPacket.ipToString(dstIp), dstPort))
                synchronized(this) {
                    if (closed) {
                        try { sock.close() } catch (_: Exception) {}
                        return@submit
                    }
                    socket = sock
                }
                // 收发泵任一起不来就没有人搬运数据，必须立即拆流而不是留一个空壳
                if (!startRecv(sock) || !startUpWriter(sock)) destroy()
            } catch (_: Exception) {
                // 无法建立（如强制 WiFi 但无 WiFi）：直接标记失败，丢弃该流的所有包
                closed = true
                engine.onFlowClosed(this)
            }
        }
        if (!kicked) {
            // 线程池拒绝（池满或停机）：同样不能把流留在 engine.flows 里空转
            closed = true
            engine.onFlowClosed(this)
        }
    }

    /** 上行：入队（引擎读线程调用）。已满则丢弃（模拟真实网络拥塞尾部丢包）。 */
    fun enqueueUp(buf: ByteArray, off: Int, len: Int) {
        if (closed || len <= 0) return
        lastActive = System.currentTimeMillis()
        val dropped: Boolean
        synchronized(this) {
            if (upLen + len > UP_CAP) {
                dropped = true
            } else {
                upQueue.add(Dgram(buf.copyOfRange(off, off + len), len))
                upLen += len
                dropped = false
            }
        }
        if (dropped) engine.stats.addDrop(uid, 1)
    }

    private fun startUpWriter(sock: DatagramSocket): Boolean =
        engine.submit {
            try {
                while (!closed) {
                    val d: Dgram? = synchronized(this) { upQueue.peekFirst() }
                    if (d == null) {
                        Thread.sleep(10)
                        continue
                    }
                    // 整包等待令牌（minTake=d.len）：避免部分扣减后丢弃导致令牌泄漏。
                    // 数据报大于桶容量时 acquire 内部会把 minTake 钳到容量，
                    // 返回的 got 可能小于 d.len——数据报不可分片，照发即可，
                    // 超出的部分由后续等待自然补偿，总吞吐仍受限速约束。
                    val got = upBucket.acquire(d.len, d.len, 500) { closed }
                    if (got <= 0) continue
                    try {
                        sock.send(DatagramPacket(d.data, d.len))
                    } catch (_: Exception) {
                        // 发送失败必须拆流：仅 break 会让流留在 engine.flows 里，
                        // enqueueUp 继续往一个再也没人消费的队列里灌包
                        destroy()
                        return@submit
                    }
                    engine.stats.addTx(uid, d.len)
                    synchronized(this) {
                        upQueue.pollFirst()
                        upLen -= d.len
                    }
                }
            } catch (_: Exception) {
            }
        }

    private fun startRecv(sock: DatagramSocket): Boolean =
        engine.submit {
            val buf = ByteArray(MAX_DGRAM)
            try {
                while (!closed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (pkt.length <= 0) continue
                    lastActive = System.currentTimeMillis()
                    val len = pkt.length
                    // 下行限速（阻塞式，形成对内核 socket 缓冲的背压）。
                    // 判据只能是 got <= 0：数据报大于桶容量时 acquire 会把 minTake
                    // 钳到容量，got < len 属正常情况，据此丢弃等于把整条流黑洞掉
                    val got = downBucket.acquire(len, len, 500) { closed }
                    if (got <= 0) continue
                    var data = buf.copyOf(len)
                    // DNS 响应：删除 AAAA 记录，防止应用连 IPv6 进黑洞（表现为断网）
                    if (dstPort == 53) {
                        val filtered = DnsFilter.filterAaaa(data)
                        if (filtered != null) data = filtered
                    }
                    val ip = IpPacket.buildUdp(dstIp, srcIp, dstPort, srcPort, data, data.size)
                    engine.writeTun(ip, uid, rule, applyLoss = true, payloadLen = data.size)
                }
            } catch (_: Exception) {
            }
        }

    fun onTimer(now: Long) {
        if (closed) return
        if (now - lastActive > IDLE_TIMEOUT) destroy()
    }

    fun applyRule(newRule: Rule) {
        rule = newRule
        upBucket.rate = newRule.upLimitKBps * 1024L
        downBucket.rate = newRule.downLimitKBps * 1024L
    }

    fun destroy() {
        if (closed) return
        closed = true
        synchronized(this) {
            try { socket?.close() } catch (_: Exception) {}
        }
        engine.onFlowClosed(this)
    }

    override fun toString(): String =
        "Udp(${IpPacket.ipToString(srcIp)}:$srcPort -> ${IpPacket.ipToString(dstIp)}:$dstPort)"
}
