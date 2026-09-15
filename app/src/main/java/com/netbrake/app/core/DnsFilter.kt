package com.netbrake.app.core

import java.io.ByteArrayOutputStream

/**
 * DNS 响应过滤器：删除应答中的 AAAA(IPv6) 记录。
 *
 * 背景：引擎只转发 IPv4（IPv6 导入 TUN 后黑洞以防绕过管控）。
 * 若应用从 DNS 拿到 AAAA 记录会优先尝试 IPv6 连接，进而全部黑洞——
 * 表现为「开启管控就没网」。删除 AAAA 后应用自然回退 IPv4，被正常管控。
 *
 * 任何解析失败都返回 null（原样放行），绝不阻断 DNS。
 */
object DnsFilter {

    private const val TYPE_AAAA = 28
    private const val TYPE_OPT = 41

    /**
     * 过滤 payload 中的 AAAA 记录。
     * @return null 表示无需修改（或无法解析，保底放行）；否则返回新的 DNS 报文。
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

        // 遍历 answer / authority / additional 三段
        val out = ByteArrayOutputStream(p.size)
        out.write(p, 0, i)                                 // header + question 原样保留
        var keptAn = 0; var keptNs = 0; var keptAr = 0
        var removed = 0
        for (sec in 0 until 3) {
            val count = when (sec) { 0 -> an; 1 -> ns; else -> ar }
            repeat(count) {
                val nameStart = i
                i = skipName(p, i) ?: return null
                if (i + 10 > p.size) return null
                val type = read16(p, i)
                val rdlen = read16(p, i + 8)
                val rrEnd = i + 10 + rdlen
                if (rrEnd > p.size) return null
                if (type == TYPE_AAAA) {
                    removed++                               // AAAA：丢弃
                } else {
                    if (sec == 0) keptAn++ else if (sec == 1) keptNs++ else keptAr++
                    out.write(p, nameStart, rrEnd - nameStart)
                }
                i = rrEnd
            }
        }
        if (removed == 0) return null

        val res = out.toByteArray()
        write16(res, 6, keptAn)
        write16(res, 8, keptNs)
        write16(res, 10, keptAr)
        return res
    }

    /** 跳过（可能带压缩指针的）域名，返回下一个字段偏移；非法返回 null。 */
    private fun skipName(p: ByteArray, off: Int): Int? {
        var i = off
        var hops = 0
        while (true) {
            if (i >= p.size) return null
            val b = p[i].toInt() and 0xFF
            when {
                b == 0 -> return i + 1                     // 根标签，name 结束
                b and 0xC0 == 0xC0 -> {
                    if (i + 1 >= p.size) return null
                    return i + 2                           // 压缩指针，name 结束
                }
                b and 0xC0 != 0 -> return null             // 非法标签类型
                else -> {
                    i += 1 + b
                    if (++hops > 128) return null          // 防指针环
                }
            }
        }
    }

    private fun read16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun write16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }
}
