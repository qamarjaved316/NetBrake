package com.netbrake.app.core

/**
 * 令牌桶限速器（字节为单位）。
 * rate == 0 表示不限速。
 */
class TokenBucket(rateBytesPerSec: Long) {

    @Volatile var rate: Long = rateBytesPerSec
        set(value) {
            synchronized(lock) {
                field = value
                burst = if (value <= 0) 0 else minOf(value, MAX_BURST)
                if (value <= 0) tokens = 0.0
            }
        }

    private val lock = Any()
    private var burst: Long = if (rateBytesPerSec <= 0) 0 else minOf(rateBytesPerSec, MAX_BURST)
    private var tokens: Double = 0.0
    private var lastRefill = System.nanoTime()

    /** 非阻塞：尽量取 n 个令牌，返回实际取得数量（0..n）。 */
    fun tryTake(n: Int): Int {
        if (rate <= 0) return n
        synchronized(lock) {
            refillLocked()
            val t = tokens.toInt()
            val take = minOf(n, t)
            tokens -= take
            return take
        }
    }

    /**
     * 阻塞式：尝试获取 upTo 个令牌（至少 minTake 个才返回）。
     * 返回实际取得的数量；超时返回 0。cancelled 返回 true 时立即返回当前可得数量。
     */
    fun acquire(upTo: Int, minTake: Int, timeoutMs: Long, cancelled: () -> Boolean): Int {
        if (rate <= 0) return upTo
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var acquired = 0
        synchronized(lock) {
            while (true) {
                refillLocked()
                val avail = tokens.toInt()
                if (avail >= minTake) {
                    val take = minOf(upTo, avail)
                    tokens -= take
                    return acquired + take
                }
                if (cancelled()) return acquired + minOf(upTo, tokens.toInt()).also { tokens -= it }
                val remainMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainMs <= 0) return 0
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (lock as java.lang.Object).wait(minOf(remainMs, 20L))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return 0
    }

    private fun refillLocked() {
        val now = System.nanoTime()
        val dt = (now - lastRefill) / 1_000_000.0 // ms
        if (dt <= 0) return
        lastRefill = now
        if (rate > 0) {
            tokens = minOf(tokens + rate * dt / 1000.0, burst.toDouble())
        }
    }

    companion object {
        const val MAX_BURST = 4L * 1024 * 1024
    }
}
