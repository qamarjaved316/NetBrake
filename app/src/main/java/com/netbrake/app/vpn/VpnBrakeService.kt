package com.netbrake.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netbrake.app.R
import com.netbrake.app.core.Engine
import com.netbrake.app.core.NetworkHub
import com.netbrake.app.data.RuleRepo
import com.netbrake.app.ui.MainActivity

/**
 * 前台 VpnService：为被管控应用建立本地 TUN，承载 [Engine]。
 */
class VpnBrakeService : VpnService() {

    companion object {
        const val ACTION_START = "com.netbrake.action.START"
        const val ACTION_STOP = "com.netbrake.action.STOP"
        const val ACTION_APPLY = "com.netbrake.action.APPLY"

        const val CHANNEL_ID = "netbrake_vpn"
        const val NOTI_ID = 1

        /** 供 UI 读取的运行状态。 */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startService(
                Intent(context, VpnBrakeService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VpnBrakeService::class.java).setAction(ACTION_STOP)
            )
        }

        fun applyRules(context: Context) {
            context.startService(
                Intent(context, VpnBrakeService::class.java).setAction(ACTION_APPLY)
            )
        }
    }

    private var engine: Engine? = null
    private var hub: NetworkHub? = null
    private var lastAppSet: Set<String>? = null

    /** 周期刷新通知里的实时速率，让「运行中」状态可感知。 */
    private val notiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notiUpdater = object : Runnable {
        override fun run() {
            if (!running) return
            updateNotificationWithRate()
            notiHandler.postDelayed(this, 3000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (engine == null) startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_APPLY -> applyNewRules()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------

    private fun startVpn() {
        val allRules = RuleRepo.all()
        val enabled = allRules.filter { it.enabled }
        if (enabled.isEmpty()) {
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("NetBrake")
            .setMtu(1500)
            .addAddress("10.111.0.1", 32)
            .addRoute("0.0.0.0", 0)
            // IPv6 全部导入 TUN 后由引擎黑洞丢弃，防止应用用 v6 绕过管控
            .addAddress("fd00:111:1::1", 128)
            .addRoute("::", 0)
            .setUnderlyingNetworks(null)

        for (d in systemDns()) builder.addDnsServer(d)

        var ok = 0
        for (r in enabled) {
            try {
                builder.addAllowedApplication(r.pkg)
                ok++
            } catch (_: Exception) {
                // 应用已卸载等，跳过
            }
        }
        if (ok == 0) {
            stopSelf()
            return
        }

        val fd: ParcelFileDescriptor = try {
            builder.establish() ?: run {
                // 用户拒绝了 VPN 授权
                stopSelf()
                return
            }
        } catch (_: Exception) {
            stopSelf()
            return
        }

        val h = NetworkHub(this, connectivityManager())
        h.start()
        val e = Engine(this, fd, h, allRules)
        e.start()
        engine = e
        EngineHolder.engine = e
        hub = h
        lastAppSet = enabled.map { it.pkg }.toSet()
        running = true
        startInForeground()
        notiHandler.removeCallbacks(notiUpdater)
        notiHandler.postDelayed(notiUpdater, 3000)
    }

    /** 规则变化：管控应用集合没变则热更新，否则重建 VPN。 */
    private fun applyNewRules() {
        val e = engine
        val curSet = RuleRepo.enabledPkgs().toSet()
        // 一个应用都没启用就没有可管控的东西：彻底停服务。
        // 仅 restartVpn() 会停掉引擎却不撤前台状态，留下一条
        // running == false、引擎为 null 却仍在显示「NetBrake 运行中」的常驻通知。
        if (curSet.isEmpty()) {
            stopVpn()
            return
        }
        if (e == null) {
            startVpn()
            return
        }
        if (curSet == lastAppSet) {
            e.applyRules(RuleRepo.all())
            return
        }
        // 管控应用集合变化：重建（establish 的 allowed 列表无法动态修改）
        restartVpn()
        startVpn()
    }

    /** 停引擎但保留服务存活，随后可立即重建。 */
    private fun restartVpn() {
        notiHandler.removeCallbacks(notiUpdater)
        running = false
        EngineHolder.engine = null
        engine?.stop()
        engine = null
        hub?.stop()
        hub = null
        lastAppSet = null
    }

    private fun stopVpn() {
        notiHandler.removeCallbacks(notiUpdater)
        running = false
        EngineHolder.engine = null
        engine?.stop()
        engine = null
        hub?.stop()
        hub = null
        lastAppSet = null
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /** 用户在系统设置中断开了 VPN。 */
    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        notiHandler.removeCallbacks(notiUpdater)
        running = false
        engine?.stop()
        engine = null
        hub?.stop()
        hub = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    // ------------------------------------------------------------------

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 取系统当前物理网络的 DNS（IPv4），失败时使用公共 DNS 兜底。 */
    private fun systemDns(): List<String> {
        val fallback = listOf("223.5.5.5", "119.29.29.29")
        return try {
            val cm = connectivityManager()
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return fallback
            val v4 = lp.dnsServers.filter { it.address.size == 4 }.map { it.hostAddress!! }
            if (v4.isEmpty()) fallback else v4.take(3)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    /** 用实时速率更新常驻通知（不弹横幅、不响铃）。 */
    private fun updateNotificationWithRate() {
        val e = engine ?: return
        val rx = e.stats.totalRxRate()
        val tx = e.stats.totalTxRate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification(rx to tx))
    }

    private fun buildNotification(rate: Pair<Long, Long>?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val count = RuleRepo.enabledPkgs().size
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        builder.setContentText(
            if (rate != null) {
                getString(
                    R.string.notification_text_with_rate,
                    com.netbrake.app.ui.RulesAdapter.fmt(rate.first),
                    com.netbrake.app.ui.RulesAdapter.fmt(rate.second),
                    count
                )
            } else {
                getString(R.string.notification_text, count)
            }
        )
        return builder.build()
    }
}
