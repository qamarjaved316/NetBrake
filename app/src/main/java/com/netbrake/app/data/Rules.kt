package com.netbrake.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 网络选择方式。
 */
object NetMode {
    const val FOLLOW = 0   // 跟随系统默认网络
    const val WIFI = 1     // 强制走 WiFi
    const val CELLULAR = 2 // 强制走移动数据
    const val BLOCK = 3    // 完全断网（黑洞）
}

/**
 * 单个应用的管控规则。
 * 限速单位 KB/s（0 = 不限）；丢包率单位 %（0-100）。
 */
data class Rule(
    val pkg: String,
    val enabled: Boolean = true,
    val downLimitKBps: Int = 0,
    val upLimitKBps: Int = 0,
    val downLossPct: Int = 0,
    val upLossPct: Int = 0,
    @NetModeVal val netMode: Int = NetMode.FOLLOW
)

@Retention(AnnotationRetention.SOURCE)
annotation class NetModeVal

/**
 * 规则仓库：进程内单例，UI 与 VPN 服务共享。
 * 持久化为应用私有目录中的 JSON 文件。
 */
object RuleRepo {

    private lateinit var file: File
    private val rules = CopyOnWriteArrayList<Rule>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var version: Long = 0
        private set

    fun init(context: Context) {
        file = File(context.filesDir, "rules.json")
        load()
    }

    fun all(): List<Rule> = rules.toList()

    fun enabledPkgs(): List<String> = rules.filter { it.enabled }.map { it.pkg }

    fun byPkg(pkg: String): Rule? = rules.firstOrNull { it.pkg == pkg }

    fun upsert(rule: Rule) {
        synchronized(this) {
            val idx = rules.indexOfFirst { it.pkg == rule.pkg }
            if (idx >= 0) rules[idx] = rule else rules.add(rule)
            version++
            persist()
        }
        notifyChanged()
    }

    fun remove(pkg: String) {
        synchronized(this) {
            rules.removeAll { it.pkg == pkg }
            version++
            persist()
        }
        notifyChanged()
    }

    fun setEnabled(pkg: String, enabled: Boolean) {
        byPkg(pkg)?.let { upsert(it.copy(enabled = enabled)) }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() { listeners.forEach { it() } }

    private fun persist() {
        if (!::file.isInitialized) return
        try {
            val arr = JSONArray()
            rules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("pkg", r.pkg)
                    put("enabled", r.enabled)
                    put("down", r.downLimitKBps)
                    put("up", r.upLimitKBps)
                    put("downLoss", r.downLossPct)
                    put("upLoss", r.upLossPct)
                    put("net", r.netMode)
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rules.add(
                    Rule(
                        pkg = o.getString("pkg"),
                        enabled = o.optBoolean("enabled", true),
                        downLimitKBps = o.optInt("down", 0),
                        upLimitKBps = o.optInt("up", 0),
                        downLossPct = o.optInt("downLoss", 0),
                        upLossPct = o.optInt("upLoss", 0),
                        netMode = o.optInt("net", NetMode.FOLLOW)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }
}
