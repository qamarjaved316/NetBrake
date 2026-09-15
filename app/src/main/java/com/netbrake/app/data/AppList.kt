package com.netbrake.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

object AppList {

    /**
     * 轻量列表查询：只取包名 / 标签 / 是否系统应用，不加载图标。
     * 图标解码是列表加载的主要耗时项，由 UI 层按需异步加载（见 [iconOf]）。
     */
    fun queryLite(context: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = context.packageManager
        val out = ArrayList<AppEntry>()
        try {
            val apps = pm.getInstalledApplications(0)
            for (ai in apps) {
                if (context.packageName == ai.packageName) continue
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (sys && !includeSystem) continue
                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    ai.packageName
                }
                out.add(AppEntry(ai.packageName, label, null, sys))
            }
            out.sortWith(compareBy({ it.isSystem }, { it.label }))
        } catch (_: Exception) {
        }
        return out
    }

    /** 按需加载单个应用图标，供列表项异步绑定。 */
    fun iconOf(context: Context, pkg: String): Drawable? = try {
        context.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    /** 兼容旧调用：一次取全量（含图标）。 */
    fun query(context: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = context.packageManager
        return queryLite(context, includeSystem).map { e ->
            e.copy(icon = try {
                pm.getApplicationIcon(e.pkg)
            } catch (_: Exception) {
                null
            })
        }
    }

    fun entryOf(context: Context, pkg: String): AppEntry? {
        val pm = context.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppEntry(
                pkg,
                pm.getApplicationLabel(ai).toString(),
                pm.getApplicationIcon(ai),
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
