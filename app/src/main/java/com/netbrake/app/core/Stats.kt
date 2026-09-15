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

    /** 每秒调用：计算速率。 */
    fun tick() {
        map.values.forEach {
            val r = it.rx.get(); val t = it.tx.get()
            it.rxRate = (r - it.lastRx).coerceAtLeast(0)
            it.txRate = (t - it.lastTx).coerceAtLeast(0)
            it.lastRx = r; it.lastTx = t
        }
    }

    fun totalRxRate(): Long = map.values.sumOf { it.rxRate }
    fun totalTxRate(): Long = map.values.sumOf { it.txRate }
    fun totalActiveSessions(): Long = map.values.sumOf { it.sessions.get() }
}
