package com.netbrake.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import com.netbrake.app.data.NetMode
import com.netbrake.app.data.Rule
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 转发引擎：从 TUN 读取被管控应用的 IP 包，
 * TCP 交给 [TcpSession]（本地 TCP 栈 + 真实 socket），UDP 交给 [UdpFlow]，
 * 两个方向施加令牌桶限速与随机丢包，并可按规则把 socket 绑定到 WiFi / 蜂窝。
 */
class Engine(
    private val context: Context,
    private val tun: ParcelFileDescriptor,
    internal val hub: NetworkHub,
    private val initialRules: List<Rule>
) {
    companion object {
        const val TOMBSTONE_MS = 30_000L
        const val PROTO_ICMP = 1
    }

    data class ConnKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    val stats = Stats()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val tunIn = FileInputStream(tun.fileDescriptor)
    private val tunOut = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Object()

    // 会话工作线程池（每会话 2-3 个阻塞线程，按需创建）
    private val executor = ThreadPoolExecutor(
        8, 1024, 30L, TimeUnit.SECONDS, SynchronousQueue()
    ) { r ->
        Thread(r, "nb-worker").apply { isDaemon = true }
    }

    private val sessions = ConcurrentHashMap<ConnKey, TcpSession>()
    private val flows = ConcurrentHashMap<ConnKey, UdpFlow>()
    private val tombstones = ConcurrentHashMap<ConnKey, TcpSession>()

    @Volatile private var running = false
    private var readerThread: Thread? = null
    private var timerThread: Thread? = null

    @Volatile private var uidRules: Map<Int, Rule> = emptyMap()

    private val rnd = java.util.Random()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start() {
        running = true
        applyRules(initialRules)
        hub.onLost = { transport -> killTransport(transport) }
        readerThread = Thread({ readLoop() }, "nb-tun-read").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        timerThread = Thread({ timerLoop() }, "nb-timer").apply { start() }
    }

    fun stop() {
        running = false
        hub.onLost = null
        sessions.values.forEach { it.destroy() }
        flows.values.forEach { it.destroy() }
        sessions.clear()
        flows.clear()
        tombstones.clear()
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
        try { tun.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    /** 提交会话工作线程；返回 false 表示线程池已不可用（如正在停机）。 */
    internal fun submit(r: Runnable): Boolean = try {
        executor.execute(r)
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    /** 热更新规则：重建 uid 索引、刷新活跃会话；变为断网的立即杀连接。 */
    fun applyRules(rules: List<Rule>) {
        val map = HashMap<Int, Rule>()
        val pm = context.packageManager
        for (r in rules) {
            try {
                val uid = pm.getPackageUid(r.pkg, 0)
                if (!map.containsKey(uid)) map[uid] = r
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        val old = uidRules
        uidRules = map

        // 新变为「断网」的：立即断开现有连接
        for ((uid, newRule) in map) {
            if (newRule.enabled && newRule.netMode == NetMode.BLOCK) {
                val prev = old[uid]
                if (prev == null || !prev.enabled || prev.netMode != NetMode.BLOCK) {
                    sessions.values.filter { it.uid == uid }.forEach { it.destroy() }
                    flows.values.filter { it.uid == uid }.forEach { it.destroy() }
                }
            }
        }

        // 限速 / 丢包 / 网络绑定热生效
        sessions.values.forEach { s ->
            val r = map[s.uid]
            if (r == null || !r.enabled) s.destroy() else s.applyRule(r)
        }
        flows.values.forEach { f ->
            val r = map[f.uid]
            if (f.uid > 0) {
                if (r == null || !r.enabled) f.destroy() else f.applyRule(r)
            }
        }
    }

    /** 网络丢失：杀掉绑定该网络的会话，应用自动重连时走新网络。 */
    private fun killTransport(transport: Int) {
        val mode = if (transport == NetworkHub.TRANSPORT_WIFI) NetMode.WIFI else NetMode.CELLULAR
        sessions.values.filter { it.rule.netMode == mode }.forEach { it.destroy() }
        flows.values.filter { it.rule.netMode == mode }.forEach { it.destroy() }
    }

    private fun uidToRule(uid: Int): Rule? {
        if (uid <= 0) return null
        val r = uidRules[uid] ?: return null
        return if (r.enabled) r else null
    }

    // ------------------------------------------------------------------
    // TUN 读循环
    // ------------------------------------------------------------------

    private fun readLoop() {
        val buf = ByteArray(65535)
        while (running) {
            val n = try {
                tunIn.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            try {
                dispatch(buf, n)
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatch(buf: ByteArray, n: Int) {
        val ip = IpPacket.parseIp(buf, n) ?: return
        if (ip.proto == PROTO_ICMP) return                 // ICMP 黑洞
        val fragField = IpPacket.readInt16(buf, 6)
        if (fragField and 0x3FFF != 0) return              // 丢弃 IP 分片

        if (ip.proto == IpPacket.PROTO_TCP) {
            val t = IpPacket.parseTcp(buf, ip) ?: return
            val key = ConnKey(ip.src, t.srcPort, ip.dst, t.dstPort)

            var session = sessions[key] ?: tombstones[key]
            if (session != null && session.closed) {
                // 已关闭连接的残包：FIN/数据重传回 ACK，其余忽略
                if (t.flags and IpPacket.FIN != 0) session.handleSegment(t, buf)
                return
            }
            if (session == null) {
                if (t.flags and IpPacket.SYN == 0 || t.flags and IpPacket.ACK != 0) {
                    // 引擎重启后的旧连接残包：RST 促使应用重连
                    if (t.flags and IpPacket.RST == 0) sendReset(key, t)
                    return
                }
                val uid = lookupOwnerUid(IpPacket.PROTO_TCP, ip.src, t.srcPort, ip.dst, t.dstPort)
                val rule = uidToRule(uid)
                if (rule != null && rule.netMode == NetMode.BLOCK) return  // 断网黑洞
                // UID 查询失败时保底放行（不限速不丢包）：只有被管控应用的流量才会进入 TUN，
                // 查不到多半是连接建立瞬间的竞态
                val effRule = rule ?: Rule(pkg = "unknown", enabled = true)
                val s = TcpSession(this, key, uid, effRule)
                val exist = sessions.putIfAbsent(key, s)
                if (exist != null) return
                session = s
            }

            // 上行丢包判定（RST 不丢，便于快速失败）
            val r = session.rule
            if (r.upLossPct > 0 && t.flags and IpPacket.RST == 0 && rnd.nextInt(100) < r.upLossPct) {
                stats.addDrop(session.uid, 1)
                return
            }
            session.handleSegment(t, buf)
            return
        }

        if (ip.proto == IpPacket.PROTO_UDP) {
            val u = IpPacket.parseUdp(buf, ip) ?: return
            val key = ConnKey(ip.src, u.srcPort, ip.dst, u.dstPort)
            var flow = flows[key]
            if (flow == null) {
                val uid = lookupOwnerUid(IpPacket.PROTO_UDP, ip.src, u.srcPort, ip.dst, u.dstPort)
                val rule = uidToRule(uid)
                if (rule != null && rule.netMode == NetMode.BLOCK) return  // 断网黑洞
                // uid 未知（如系统代发的 DNS 查询）：不限速不丢包直接转发
                val effRule = rule ?: Rule(pkg = "unknown", enabled = true)
                val f = UdpFlow(this, key, uid, effRule)
                val exist = flows.putIfAbsent(key, f)
                if (exist != null) flow = exist
                else { f.start(); flow = f }
            }
            val r = flow.rule
            if (r.upLossPct > 0 && rnd.nextInt(100) < r.upLossPct) {
                stats.addDrop(flow.uid, 1)
                return
            }
            flow.enqueueUp(buf, u.payloadOff, u.payloadLen)
            return
        }
        // 其他协议：黑洞
    }

    private fun sendReset(key: ConnKey, t: IpPacket.TcpHeader) {
        val pkt = IpPacket.buildTcp(
            key.dstIp, key.srcIp, key.dstPort, key.srcPort,
            t.ack, t.seq + t.payloadLen, IpPacket.RST or IpPacket.ACK, 0
        )
        rawWrite(pkt)
    }

    /** ConnectionOwnerUid 查询（binder 调用，仅新连接发生一次）。 */
    private fun lookupOwnerUid(proto: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int {
        return try {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(intToBytes(srcIp)), srcPort),
                InetSocketAddress(InetAddress.getByAddress(intToBytes(dstIp)), dstPort)
            )
        } catch (_: Exception) {
            -1
        }
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    // ------------------------------------------------------------------
    // TUN 写
    // ------------------------------------------------------------------

    /** 写包到 TUN。applyLoss=true 时按规则做下行丢包判定。 */
    internal fun writeTun(pkt: ByteArray, uid: Int, rule: Rule?, applyLoss: Boolean, payloadLen: Int) {
        if (!running) return
        if (applyLoss && rule != null && rule.downLossPct > 0 && rnd.nextInt(100) < rule.downLossPct) {
            stats.addDrop(uid, 1)
            return
        }
        rawWrite(pkt)
        if (payloadLen > 0) stats.addRx(uid, payloadLen)
    }

    private fun rawWrite(pkt: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOut.write(pkt)
                tunOut.flush()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 定时器
    // ------------------------------------------------------------------

    private fun timerLoop() {
        while (running) {
            val now = System.currentTimeMillis()
            try {
                sessions.values.forEach { it.onTimer(now) }
                flows.values.forEach { it.onTimer(now) }
                stats.tick()
                tombstones.entries.removeIf { now - it.value.closedAt > TOMBSTONE_MS }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    internal fun onSessionClosed(s: TcpSession) {
        sessions.remove(s.key)
        tombstones[s.key] = s
    }

    internal fun onFlowClosed(f: UdpFlow) {
        flows.remove(f.key)
    }

    fun activeSessionCount(): Int = sessions.size
    fun activeFlowCount(): Int = flows.size
}
