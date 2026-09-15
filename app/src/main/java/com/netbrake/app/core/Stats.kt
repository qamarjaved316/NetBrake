package com.netbrake.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 每应用（UID）流量统计。速率由引擎定时器每秒推进。
 */
class Stats {

    class AppStat {
        val rx = AtomicLong()        // 下行字节（应用收到）
        val tx = AtomicLong()        // 上行字节（应用发出）
        val dropped = AtomicLong()   // 被丢弃的包数
        val sessions = AtomicLong()  // 活动会话数

        @Volatile var rxRate = 0L
        @Volatile var txRate = 0L
        var lastRx = 0L
        var lastTx = 0L
    }

    private val map = ConcurrentHashMap<Int, AppStat>()

    fun stat(uid: Int): AppStat = map.computeIfAbsent(uid) { AppStat() }

    fun addRx(uid: Int, n: Int) { stat(uid).rx.addAndGet(n.toLong()) }
    fun addTx(uid: Int, n: Int) { stat(uid).tx.addAndGet(n.toLong()) }
    fun addDrop(uid: Int, n: Int) { stat(uid).dropped.addAndGet(n.toLong()) }

    fun snapshot(): Map<Int, AppStat> = HashMap(map)

    private var lastTickAt = System.nanoTime()

    /**
     * 由引擎定时器高频调用（约 50ms 一次），内部自行按真实经过时间换算成「字节/秒」。
     *
     * 不能直接把两次调用之间的增量当速率：调用周期是 50ms 而非 1s，
     * 那样算出的是「每 50ms 字节数」，却被 UI 与通知按 B/s、KB/s、MB/s 渲染，
     * 显示值会比真实速率低约 20 倍。这里显式除以实际经过时间，
     * 使结果与调用频率解耦。
     */
    fun tick() {
        val now = System.nanoTime()
        val dtMs = (now - lastTickAt) / 1_000_000L
        if (dtMs < RATE_WINDOW_MS) return          // 不足一个统计窗口，保持上次结果
        lastTickAt = now
        map.values.forEach {
            val r = it.rx.get(); val t = it.tx.get()
            it.rxRate = (r - it.lastRx).coerceAtLeast(0) * 1000L / dtMs
            it.txRate = (t - it.lastTx).coerceAtLeast(0) * 1000L / dtMs
            it.lastRx = r; it.lastTx = t
        }
    }

    fun totalRxRate(): Long = map.values.sumOf { it.rxRate }
    fun totalTxRate(): Long = map.values.sumOf { it.txRate }
    fun totalActiveSessions(): Long = map.values.sumOf { it.sessions.get() }

    companion object {
        /** 速率统计窗口（ms）：够短能跟手，够长能压住 50ms 粒度的抖动。 */
        const val RATE_WINDOW_MS = 1000L
    }
}
