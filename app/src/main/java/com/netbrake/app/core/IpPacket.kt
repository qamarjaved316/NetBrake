package com.netbrake.app.core

/**
 * IPv4 / TCP / UDP 报文的解析与构造工具。
 * 仅处理 IPv4；IPv6 由引擎直接黑洞丢弃（避免应用绕过管控）。
 */
object IpPacket {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    // TCP flags
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20

    class IpHeader {
        var ihl: Int = 5
        var totalLen: Int = 0
        var proto: Int = 0
        var src: Int = 0
        var dst: Int = 0
        var headerBytes: Int = 20
        var payloadOff: Int = 0
        var payloadLen: Int = 0
    }

    class TcpHeader {
        var srcPort: Int = 0
        var dstPort: Int = 0
        var seq: Int = 0
        var ack: Int = 0
        var dataOffset: Int = 5
        var flags: Int = 0
        var window: Int = 0
        var mss: Int = 0          // >0：SYN 中携带的 MSS
        var wsShift: Int = -1     // >=0：SYN 中携带的窗口缩放
        var sacks: IntArray? = null  // SACK 块（乱序 ACK 携带）：[l1, r1, l2, r2, ...] 绝对序号
        var payloadOff: Int = 0
        var payloadLen: Int = 0
    }

    class UdpHeader {
        var srcPort: Int = 0
        var dstPort: Int = 0
        var payloadOff: Int = 0
        var payloadLen: Int = 0
    }

    /** 解析 IPv4 头。失败返回 null。不校验头部校验和（来自本机内核，可信）。 */
    fun parseIp(buf: ByteArray, len: Int): IpHeader? {
        if (len < 20) return null
        if ((buf[0].toInt() ushr 4) and 0xF != 4) return null
        val ihl = buf[0].toInt() and 0xF
        if (ihl < 5 || ihl * 4 > len) return null
        val h = IpHeader()
        h.ihl = ihl
        h.totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (h.totalLen < ihl * 4) return null
        if (h.totalLen > len) h.totalLen = len
        h.proto = buf[9].toInt() and 0xFF
        h.src = readInt32(buf, 12)
        h.dst = readInt32(buf, 16)
        h.headerBytes = ihl * 4
        h.payloadOff = h.headerBytes
        h.payloadLen = h.totalLen - h.headerBytes
        return h
    }

    /** 解析 TCP 头（跟随 IP 头之后）。 */
    fun parseTcp(buf: ByteArray, ip: IpHeader): TcpHeader? {
        val off = ip.headerBytes
        if (off + 20 > ip.totalLen) return null
        val t = TcpHeader()
        t.srcPort = readInt16(buf, off)
        t.dstPort = readInt16(buf, off + 2)
        t.seq = readInt32(buf, off + 4)
        t.ack = readInt32(buf, off + 8)
        t.dataOffset = (buf[off + 12].toInt() ushr 4) and 0xF
        if (t.dataOffset < 5) return null
        t.flags = buf[off + 13].toInt() and 0xFF
        t.window = readInt16(buf, off + 14)
        val tcpEnd = off + t.dataOffset * 4
        if (tcpEnd > ip.totalLen) return null
        t.payloadOff = tcpEnd
        t.payloadLen = ip.totalLen - tcpEnd
        // 选项解析：MSS/WS 仅 SYN 段有意义；SACK 在任意段的 ACK 中携带（RFC 2018）
        val synOpts = t.flags and SYN != 0
        var i = off + 20
        while (i < tcpEnd - 1) {
            val kind = buf[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            val olen = buf[i + 1].toInt() and 0xFF
            if (olen < 2 || i + olen > tcpEnd) break
            when {
                synOpts && kind == 2 && olen == 4 -> t.mss = readInt16(buf, i + 2)
                synOpts && kind == 3 && olen == 3 -> t.wsShift = buf[i + 2].toInt() and 0xFF
                kind == 5 && olen >= 10 && (olen - 2) % 8 == 0 -> {
                    val n = (olen - 2) / 8
                    val arr = IntArray(n * 2)
                    for (k in 0 until n) {
                        arr[k * 2] = readInt32(buf, i + 2 + k * 8)
                        arr[k * 2 + 1] = readInt32(buf, i + 6 + k * 8)
                    }
                    t.sacks = arr
                }
            }
            i += olen
        }
        return t
    }

    fun parseUdp(buf: ByteArray, ip: IpHeader): UdpHeader? {
        val off = ip.headerBytes
        if (off + 8 > ip.totalLen) return null
        val u = UdpHeader()
        u.srcPort = readInt16(buf, off)
        u.dstPort = readInt16(buf, off + 2)
        u.payloadOff = off + 8
        u.payloadLen = ip.totalLen - off - 8
        if (u.payloadLen < 0) return null
        return u
    }

    /**
     * 构造完整的 IPv4+TCP 数据包。
     * @param mss / wsShift 仅在 SYN 段携带（mss>0 / wsShift>=0 时生效）
     * @param sack SACK 块 [l1, r1, l2, r2, ...]（乱序 ACK 携带，RFC 2018）；
     *             SYN 段自动携带 SACK-Permitted 选项与对端协商
     */
    fun buildTcp(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        seq: Int, ack: Int,
        flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0,
        mss: Int = 0, wsShift: Int = -1,
        sack: IntArray? = null
    ): ByteArray {
        var optLen = 0
        if (flags and SYN != 0) {
            if (mss > 0) optLen += 4
            if (wsShift >= 0) optLen += 3
            optLen += 2                       // SACK-Permitted
        }
        if (sack != null && sack.size >= 2) optLen += 2 + sack.size * 4
        if (optLen > 0) optLen = (optLen + 3) and 3.inv()
        val tcpHdrLen = 20 + optLen
        val l4Len = tcpHdrLen + payloadLen
        val totalLen = 20 + l4Len
        val out = ByteArray(totalLen)
        // IP 头
        out[0] = 0x45
        writeInt16(out, 2, totalLen)
        writeInt16(out, 6, 0x4000) // DF
        out[8] = 64
        out[9] = PROTO_TCP.toByte()
        writeInt32(out, 12, srcIp)
        writeInt32(out, 16, dstIp)
        // TCP 头
        writeInt16(out, 20, srcPort)
        writeInt16(out, 22, dstPort)
        writeInt32(out, 24, seq)
        writeInt32(out, 28, ack)
        out[32] = ((tcpHdrLen ushr 2) shl 4).toByte()
        out[33] = flags.toByte()
        writeInt16(out, 34, window)
        var oi = 40
        if (flags and SYN != 0) {
            if (mss > 0) {
                out[oi] = 2; out[oi + 1] = 4; writeInt16(out, oi + 2, mss); oi += 4
            }
            if (wsShift >= 0) {
                out[oi] = 3; out[oi + 1] = 3; out[oi + 2] = wsShift.toByte(); oi += 3
            }
            out[oi] = 4; out[oi + 1] = 2; oi += 2     // SACK-Permitted
        }
        if (sack != null && sack.size >= 2) {
            out[oi] = 5; out[oi + 1] = (2 + sack.size * 4).toByte()
            for (k in sack.indices) writeInt32(out, oi + 2 + k * 4, sack[k])
            oi += 2 + sack.size * 4
        }
        if (payloadLen > 0) System.arraycopy(payload!!, payloadOff, out, 20 + tcpHdrLen, payloadLen)
        // 校验和
        writeInt16(out, 10, ipChecksum(out))
        writeInt16(out, 36, transportChecksum(out, l4Len))
        return out
    }

    /** 构造完整的 IPv4+UDP 数据包。 */
    fun buildUdp(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadLen: Int
    ): ByteArray {
        val l4Len = 8 + payloadLen
        val totalLen = 20 + l4Len
        val out = ByteArray(totalLen)
        out[0] = 0x45
        writeInt16(out, 2, totalLen)
        writeInt16(out, 6, 0x4000)
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        writeInt32(out, 12, srcIp)
        writeInt32(out, 16, dstIp)
        writeInt16(out, 20, srcPort)
        writeInt16(out, 22, dstPort)
        writeInt16(out, 24, l4Len)
        if (payloadLen > 0) System.arraycopy(payload, 0, out, 28, payloadLen)
        writeInt16(out, 10, ipChecksum(out))
        writeInt16(out, 26, transportChecksum(out, l4Len))
        return out
    }

    /** 对已组装好的完整 IPv4 包（校验和字段为 0）计算 IP 头校验和。 */
    private fun ipChecksum(buf: ByteArray): Int {
        var sum = 0L
        for (i in 0 until 20 step 2) {
            sum += (((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF))
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    /** 对已组装好的完整 IPv4 包计算 TCP/UDP 校验和（含伪头）。 */
    private fun transportChecksum(buf: ByteArray, l4Len: Int): Int {
        var sum = 0L
        // 伪头
        for (i in 12 until 20 step 2) {
            sum += (((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF))
        }
        sum += (buf[9].toInt() and 0xFF).toLong()
        sum += l4Len.toLong()
        // 传输层（校验和字段当前为 0，不影响求和）
        var i = 20
        var remain = l4Len
        while (remain >= 2) {
            sum += (((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF))
            i += 2; remain -= 2
        }
        if (remain == 1) sum += (buf[i].toInt() and 0xFF).toLong() shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    // ---------------- 小工具 ----------------

    fun readInt16(b: ByteArray, o: Int): Int =
        (((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF))

    fun readInt32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun writeInt16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    fun writeInt32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    fun ipToString(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    fun stringToIp(s: String): Int {
        val p = s.split(".").map { it.toInt() }
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    /** TCP 序号比较（考虑 32 位回绕：差值 < 2^31 时正确）。 */
    fun seqLt(a: Int, b: Int): Boolean = a - b < 0
    fun seqLeq(a: Int, b: Int): Boolean = a - b <= 0
}
