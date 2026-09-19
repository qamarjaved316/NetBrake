package com.netbrake.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netbrake.app.R
import com.netbrake.app.core.Engine
import com.netbrake.app.core.NetworkHub
import com.netbrake.app.data.Rule
import com.netbrake.app.data.RuleRepo
import com.netbrake.app.ui.MainActivity
import java.net.InetAddress

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

        val dnsServers = systemDns()

        // API 33+ 用排除路由把组播/广播留在物理网络；个别 ROM 若在 establish()
        // 阶段才拒绝这类路由，去掉排除整体重建重试一次，保证 VPN 至少回到
        // 没有排除路由时（改动前）的可用性
        var excludeMulticast = Build.VERSION.SDK_INT >= 33
        var fd: ParcelFileDescriptor?
        while (true) {
            val builder = buildVpnInterface(enabled, dnsServers, excludeMulticast)
            if (builder == null) {
                stopSelf()
                return
            }
            var thrown = false
            fd = try {
                builder.establish()
            } catch (_: Exception) {
                thrown = true
                null
            }
            // 只有异常才可能是排除路由所致，值得去掉排除重试；
            // 返回 null 是授权被拒，重试也无济于事
            if (fd != null || !thrown || !excludeMulticast) break
            excludeMulticast = false
        }
        if (fd == null) {
            // 用户拒绝了 VPN 授权，或接口建立失败
            stopSelf()
            return
        }

        val h = NetworkHub(this, connectivityManager(), dnsServers)
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

    /** 组装 VPN 接口；一个可管控应用都没有时返回 null。 */
    private fun buildVpnInterface(
        enabled: List<Rule>,
        dnsServers: List<String>,
        excludeMulticast: Boolean
    ): Builder? {
        val builder = Builder()
            .setSession("NetBrake")
            .setMtu(1500)
            .addAddress("10.111.0.1", 32)
            .addRoute("0.0.0.0", 0)
            // IPv6 全部导入 TUN 后由引擎黑洞丢弃，防止应用用 v6 绕过管控
            .addAddress("fd00:111:1::1", 128)
            .addRoute("::", 0)
            .setUnderlyingNetworks(null)

        // 局域网发现（mDNS/SSDP/受限广播）依赖收听局域网内其他设备的组播/广播通告，
        // 这类包由内核本地投递、永不进 TUN，被管控应用因此在引擎侧永远「听不见」，
        // 引擎代理转发也收不到应答（见 Engine.dispatch）。API 33+ 用 excludeRoute
        // 把它们留在物理网络上原生收发，发现即恢复正常；代价是组播/广播流量不再
        // 经过限速管控（局域网单播仍受管控）。API < 33 没有 excludeRoute，这类流量
        // 仍会进 TUN，由引擎黑洞并对失败四元组 tombstone（见 Engine.dispatch）。
        if (excludeMulticast) {
            try {
                // IpPrefix 版 excludeRoute 自 API 33 可用；(InetAddress, int) 重载要 34
                builder.excludeRoute(IpPrefix(InetAddress.getByName("224.0.0.0"), 4))        // IPv4 组播（含 mDNS 224.0.0.251:5353、SSDP 239.255.255.250:1900）
                builder.excludeRoute(IpPrefix(InetAddress.getByName("255.255.255.255"), 32)) // 受限广播
                builder.excludeRoute(IpPrefix(InetAddress.getByName("ff00::"), 8))           // IPv6 组播
            } catch (_: Exception) {
            }
        }

        for (d in dnsServers) builder.addDnsServer(d)

        var ok = 0
        for (r in enabled) {
            // 排除自身：NetBrake 进了 allowed 列表后 cm.activeNetwork 会指向 VPN 自己，
            // NetworkHub 的「跟随系统」取网与 DNS 重映射都会失去物理网络前提，
            // 且给自己的转发流量套限速只有自食其果
            if (r.pkg == packageName) continue
            try {
                builder.addAllowedApplication(r.pkg)
                ok++
            } catch (_: Exception) {
                // 应用已卸载等，跳过
            }
        }
        if (ok == 0) return null
        return builder
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
