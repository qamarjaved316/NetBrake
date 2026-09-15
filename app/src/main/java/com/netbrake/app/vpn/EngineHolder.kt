package com.netbrake.app.vpn

import com.netbrake.app.core.Engine

/**
 * 同进程内 UI 与服务之间的引擎引用桥（进程唯一）。
 */
object EngineHolder {
    @Volatile var engine: Engine? = null
}
