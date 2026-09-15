package com.netbrake.app.core

import java.io.ByteArrayOutputStream

/**
 * DNS 响应过滤器：删除应答中的 AAAA(IPv6) 记录。
 *
 * 背景：引擎只转发 IPv4（IPv6 导入 TUN 后黑洞以防绕过管控）。
 * 若应用从 DNS 拿到 AAAA 记录会优先尝试 IPv6 连接，进而全部黑洞——
 * 表现为「开启管控就没网」。删除 AAAA 后应用自然回退 IPv4，被正常管控。
 *
 * 删除记录会让其后所有记录整体前移，而报文里的压缩指针编码的是删除前的绝对偏移，
 * 因此必须同步修正指针（见 [filterAaaa] 第三遍）。只删不修会让指针指向错位的字节，
 * 客户端解析出垃圾域名并丢弃该记录——由于 buildUdp 会重算校验和，
 * 报文本身校验通过，症状就是纯粹的解析失败，正是本过滤器要避免的场景。
 *
 * 任何解析失败或无法安全改写的情况都返回 null（原样放行），绝不阻断 DNS。
 */
object DnsFilter {

    private const val TYPE_AAAA = 28

    /**
     * 过滤 payload 中的 AAAA 记录。
     * @return null 表示无需修改（或无法安全改写，保底放行）；否则返回新的 DNS 报文。
     */
    fun filterAaaa(p: ByteArray): ByteArray? {
        if (p.size < 12) return null
        val flags = read16(p, 2)
        if (flags and 0x8000 == 0) return null            // 不是响应
        if (flags and 0x000F != 0) return null            // 非零 RCODE，原样
        val qd = read16(p, 4)
        val an = read16(p, 6)
        val ns = read16(p, 8)
        val ar = read16(p, 10)
        if (qd == 0 || qd > 8) return null
        if (an > 64 || ns > 64 || ar > 64) return null

        // 跳过 question 区
        var i = 12
        repeat(qd) {
            i = skipName(p, i) ?: return null
            i += 4                                          // QTYPE + QCLASS
            if (i > p.size) return null
        }
        val qEnd = i

        // ---- 第一遍：枚举 answer / authority / additional 三段的全部 RR ----
        val total = an + ns + ar
        val nameStart = IntArray(total)
        val rrEnd = IntArray(total)
        val rrType = IntArray(total)
        val rdStart = IntArray(total)
        val rdLen = IntArray(total)
        val section = IntArray(total)
        var idx = 0
        for (sec in 0 until 3) {
            val count = when (sec) { 0 -> an; 1 -> ns; else -> ar }
            repeat(count) {
                val start = i
                i = skipName(p, i) ?: return null
                if (i + 10 > p.size) return null
                val len = read16(p, i + 8)
                val end = i + 10 + len
                if (end > p.size) return null
                nameStart[idx] = start
                rrType[idx] = read16(p, i)
                rdStart[idx] = i + 10
                rdLen[idx] = len
                rrEnd[idx] = end
                section[idx] = sec
                idx++
                i = end
            }
        }

        // ---- 被删区间（按扫描顺序天然递增，后续二分/线性查找都依赖这个有序性）----
        val remStart = IntArray(total)
        val remEnd = IntArray(total)
        var remCount = 0
        for (k in 0 until total) {
            if (rrType[k] == TYPE_AAAA) {
                remStart[remCount] = nameStart[k]
                remEnd[remCount] = rrEnd[k]
                remCount++
            }
        }
        if (remCount == 0) return null

        // ---- 第二遍：把保留的 RR 原样拼接到 header + question 之后 ----
        val out = ByteArrayOutputStream(p.size)
        out.write(p, 0, qEnd)
        var keptAn = 0; var keptNs = 0; var keptAr = 0
        for (k in 0 until total) {
            if (rrType[k] == TYPE_AAAA) continue
            when (section[k]) { 0 -> keptAn++; 1 -> keptNs++; else -> keptAr++ }
            out.write(p, nameStart[k], rrEnd[k] - nameStart[k])
        }
        val res = out.toByteArray()

        // ---- 第三遍：修正保留记录里的压缩指针 ----
        // 保留区间内部不含被删字节，故整条 RR 的位移等于其起点之前被删的总字节数；
        // 指针目标同理按目标位置自身的位移回退。目标若落在被删区间内则无法安全改写。
        val ptrs = ArrayList<Int>(4)
        for (k in 0 until total) {
            if (rrType[k] == TYPE_AAAA) continue
            val delta = shiftOf(remStart, remEnd, remCount, nameStart[k])
            if (delta < 0) return null
            ptrs.clear()
            if (!collectPointers(p, nameStart[k], rrType[k], rdStart[k], rdLen[k], ptrs)) return null
            for (q in ptrs) {
                val target = read16(p, q) and 0x3FFF
                if (target >= p.size) return null
                val tShift = shiftOf(remStart, remEnd, remCount, target)
                if (tShift < 0) return null                 // 指向已被删的记录：放弃改写
                val np = q - delta
                if (np < 0 || np + 1 >= res.size) return null
                write16(res, np, 0xC000 or (target - tShift))
            }
        }

        write16(res, 6, keptAn)
        write16(res, 8, keptNs)
        write16(res, 10, keptAr)
        return res
    }

    /**
     * 收集一条 RR 中全部压缩指针的位置（RR NAME 一个，RDATA 内域名各一个）。
     * @return false 表示该 RR 无法安全解析，调用方应整体放弃改写。
     */
    private fun collectPointers(
        p: ByteArray, nameStart: Int, type: Int, rdStart: Int, rdLen: Int, out: ArrayList<Int>
    ): Boolean {
        val n = scanName(p, nameStart) ?: return false
        if (n[1] >= 0) out.add(n[1])
        val rd = rdataNames(type, rdStart) ?: return true   // 该类型 RDATA 内无域名
        var off = rd[0]
        val rdEnd = rdStart + rdLen
        repeat(rd[1]) {
            if (off >= rdEnd) return false
            val m = scanName(p, off) ?: return false
            if (m[0] > rdEnd) return false
            if (m[1] >= 0) out.add(m[1])
            off = m[0]
        }
        return true
    }

    /**
     * RDATA 内可能被压缩的域名：返回 [起始偏移, 连续域名个数]，无则 null。
     *
     * 只有 RFC 1035 原生定义的这几种类型允许在 RDATA 里使用压缩指针；
     * RFC 3597 明确要求新类型的 RDATA 不得压缩，故其余类型按「无域名」处理。
     */
    private fun rdataNames(type: Int, rdStart: Int): IntArray? = when (type) {
        2, 5, 12 -> intArrayOf(rdStart, 1)          // NS / CNAME / PTR
        6 -> intArrayOf(rdStart, 2)                 // SOA：MNAME + RNAME
        15 -> intArrayOf(rdStart + 2, 1)            // MX：preference(2) 之后是 exchange
        33 -> intArrayOf(rdStart + 6, 1)            // SRV：priority/weight/port(6) 之后是 target
        else -> null
    }

    /**
     * 计算 offset 处的字节在删除后前移了多少字节。
     * @return 位移量；offset 落在被删区间内返回 -1。
     */
    private fun shiftOf(starts: IntArray, ends: IntArray, count: Int, offset: Int): Int {
        var shift = 0
        for (k in 0 until count) {
            if (ends[k] <= offset) shift += ends[k] - starts[k]
            else if (starts[k] <= offset) return -1
            else break
        }
        return shift
    }

    /**
     * 扫描一个（可能带压缩指针的）域名。
     * @return [结束偏移, 压缩指针所在偏移]；无指针时第二项为 -1；非法返回 null。
     */
    private fun scanName(p: ByteArray, off: Int): IntArray? {
        var i = off
        var hops = 0
        while (true) {
            if (i >= p.size) return null
            val b = p[i].toInt() and 0xFF
            when {
                b == 0 -> return intArrayOf(i + 1, -1)     // 根标签，name 结束
                b and 0xC0 == 0xC0 -> {
                    if (i + 1 >= p.size) return null
                    return intArrayOf(i + 2, i)            // 压缩指针，name 结束
                }
                b and 0xC0 != 0 -> return null             // 非法标签类型
                else -> {
                    i += 1 + b
                    if (++hops > 128) return null          // 防指针环
                }
            }
        }
    }

    /** 跳过（可能带压缩指针的）域名，返回下一个字段偏移；非法返回 null。 */
    private fun skipName(p: ByteArray, off: Int): Int? = scanName(p, off)?.get(0)

    private fun read16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun write16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }
}
