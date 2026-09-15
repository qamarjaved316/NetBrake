package com.netbrake.app.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import com.netbrake.app.data.NetMode
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 追踪 WiFi / 蜂窝物理网络，并为出站 socket 执行 protect + bind，
 * 实现“指定应用走 WiFi / 走流量”。
 */
class NetworkHub(
    private val vpn: VpnService,
    private val cm: ConnectivityManager
) {

    @Volatile var wifiNet: Network? = null
        private set
    @Volatile var cellNet: Network? = null
        private set

    /** 网络丢失回调（transport -> 需要杀掉的连接类型）。 */
    var onLost: ((transport: Int) -> Unit)? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifiNet = network }
        override fun onLost(network: Network) {
            if (wifiNet == network) {
                wifiNet = null
                onLost?.invoke(TRANSPORT_WIFI)
            }
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { cellNet = network }
        override fun onLost(network: Network) {
            if (cellNet == network) {
                cellNet = null
                onLost?.invoke(TRANSPORT_CELLULAR)
            }
        }
    }

    fun start() {
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(), wifiCallback
            )
        } catch (_: Exception) {}
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), cellCallback
            )
        } catch (_: Exception) {}
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        try { cm.unregisterNetworkCallback(cellCallback) } catch (_: Exception) {}
    }

    /** 当前是否存在指定网络。 */
    fun hasNetwork(netMode: Int): Boolean = when (netMode) {
        NetMode.WIFI -> wifiNet != null
        NetMode.CELLULAR -> cellNet != null
        else -> true
    }

    /**
     * 准备一个 TCP socket：先 protect（绕过 VPN 防回环），再按规则绑定网络。
     * 必须在 connect() 之前调用。失败抛 IOException。
     */
    fun prepareTcp(socket: Socket, netMode: Int) {
        // 关键：java.net.Socket() 在 connect/bind 之前底层没有真正的 fd
        // （AbstractPlainSocketImpl 延迟到 create() 才建），此时 protect() 拿到的是
        // 无效 fd，必然返回 false，bindSocket() 同样无从生效。必须先 bind 一个
        // 任意本地端口把 fd 建出来，protect 才有意义。
        // DatagramSocket 无参构造会立即 bind，所以 UDP（DNS）一直正常，只有 TCP 全灭。
        if (!socket.isBound) socket.bind(InetSocketAddress(0))
        when (netMode) {
            NetMode.WIFI -> {
                val net = wifiNet ?: throw IOException("no wifi network")
                // protect 失败必须抛出：隧道自身的上行 socket 会被路由回 TUN 形成转发环路，
                // 远不如一次干净的 IOException -> sendRst() 快速失败
                if (!vpn.protect(socket)) throw IOException("protect failed")
                net.bindSocket(socket)
                // bindSocket 会覆盖路由，protect 语义仍在：该 socket 不再进 VPN
            }
            NetMode.CELLULAR -> {
                val net = cellNet ?: throw IOException("no cellular network")
                if (!vpn.protect(socket)) throw IOException("protect failed")
                net.bindSocket(socket)
            }
            else -> {
                // 跟随系统：protect 后走系统默认物理网络
                if (!vpn.protect(socket)) throw IOException("protect failed")
            }
        }
    }

    /** 准备一个 UDP socket（DatagramSocket 未连接状态）。 */
    fun prepareUdp(socket: DatagramSocket, netMode: Int) {
        when (netMode) {
            NetMode.WIFI -> {
                val net = wifiNet ?: throw IOException("no wifi network")
                if (!vpn.protect(socket)) throw IOException("protect failed")
                net.bindSocket(socket)
            }
            NetMode.CELLULAR -> {
                val net = cellNet ?: throw IOException("no cellular network")
                if (!vpn.protect(socket)) throw IOException("protect failed")
                net.bindSocket(socket)
            }
            else -> {
                if (!vpn.protect(socket)) throw IOException("protect failed")
            }
        }
    }

    companion object {
        /** 必须使用系统常量：TRANSPORT_CELLULAR=0、TRANSPORT_WIFI=1（此前误写 2=蓝牙，
         *  导致「仅流量」模式下永远找不到蜂窝网络，应用连接被全部 RST，表现为直接断网）。 */
        const val TRANSPORT_WIFI = NetworkCapabilities.TRANSPORT_WIFI
        const val TRANSPORT_CELLULAR = NetworkCapabilities.TRANSPORT_CELLULAR
    }
}
