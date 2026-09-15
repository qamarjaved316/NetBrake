# NetBrake — 应用级网络限制器

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/kegechen/NetBrake?include_prereleases)](https://github.com/kegechen/NetBrake/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white)](#安装)

一个免 root 的 Android 弱网/流量管控工具：**按应用限制网速、模拟丢包、强制指定 WiFi 或移动数据（甚至断网）**。适合弱网测试、应用测试、流量管控等场景。

## 功能

| 功能 | 说明 |
|---|---|
| 按应用限速 | 分别设置每个应用的 **下载/上传** 速度上限（KB/s，0 = 不限） |
| 丢包模拟 | 分别设置 **下载/上传** 方向的丢包率（0–50%），TCP 自动触发重传，QUIC/UDP 表现为真实弱网 |
| 指定网络 | 每个应用可独立选择：**跟随系统 / 仅 WiFi / 仅移动数据 / 完全断网** |
| 弱网预设 | 内置 2G / 3G / 弱网 / 恶劣 一键预设 |
| 实时统计 | 每应用实时速率、总会话数 |

## 原理

```
┌──────────┐   ┌─────────────────────────────────────────┐   ┌──────────┐
│ 被管控 App │──▶│  NetBrake 本地 VPN (TUN)                 │──▶│ WiFi     │
└──────────┘   │  ┌─────────────────────────────────────┐ │   ├──────────┤
               │  │ Engine                              │ │   │ 移动数据  │
 其他 App ────▶│  │  · IP/TCP/UDP 解析                   │ │   └──────────┘
 (不受影响)     │  │  · 简化 TCP 栈（握手/重传/窗口）      │ │
               │  │  · 令牌桶限速（上下行独立）           │ │
               │  │  · 随机丢包（上下行独立）             │ │
               │  │  · ConnectionOwnerUid 识别应用        │ │
               │  │  · socket protect + bindSocket 选网   │ │
               │  └─────────────────────────────────────┘ │
               └─────────────────────────────────────────┘
```

1. 通过 `VpnService.Builder.addAllowedApplication()` 只把被勾选的应用流量导入 TUN，其他应用完全不受影响。
2. 引擎在 TUN 上对应用侧实现了一个极简 TCP 栈（SYN 握手、序号/确认、RTO 与快速重传、零窗口探测、窗口缩放），远端方向用真实 socket 转发。
3. **限速** = 双向令牌桶 + TCP 接收窗口收缩（应用拥塞窗口随之收缩，是真正的带宽限制而非丢包降速）。
4. **丢包** = 按方向随机丢弃 IP 包；TCP 由协议栈自动重传，效果与真实网络丢包一致。
5. **指定网络** = 出站 socket 先 `protect()`（防 VPN 回环）再 `Network.bindSocket()` 绑定 WiFi 或蜂窝；网络切换/丢失时自动断开旧连接，应用重连即走新网络。
6. **断网** = 静默丢弃该应用全部包（含 TCP SYN），表现为无网络。

## 安装

- 到 [Releases](https://github.com/kegechen/NetBrake/releases/latest) 下载 `NetBrake-<版本>.apk`，直接安装
- 系统要求：**Android 10（API 29）及以上**
- 首次开启时系统会弹出「VPN 连接请求」，请点击「确定」

> Release APK 默认使用 **debug 签名**（方便免配置直接安装）。debug keystore 由构建环境自动生成，
> 各次发布的签名并不相同，因此**跨版本升级需要先卸载旧版**。若 fork 本项目并在仓库 Secrets 中配置
> `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，发布流程会改用正式签名，届时可直接覆盖升级。

## 使用

1. 打开 NetBrake → 「添加应用」→ 选择要管控的应用
2. 设置规则（限速 / 丢包 / 网络），保存
3. 打开顶部总开关，允许 VPN
4. 修改规则即时生效；管控应用列表变化会自动重建 VPN

## 从源码构建

```bash
# Android Studio: 直接打开工程，Run 即可
# 命令行（需 JDK 17+、Android SDK：platforms;android-34 与 build-tools;34.0.0）：
./gradlew assembleDebug
```

国内网络环境已在 `settings.gradle` 中配置了阿里云/腾讯云的 Maven 镜像。

## 发布

推送 `v` 开头的 tag 即自动构建并发布 Release：

```bash
git tag v1.0.3
git push origin v1.0.3
```

`.github/workflows/release.yml` 会构建 release APK、创建同名 GitHub Release，
并把 `NetBrake-<tag>.apk` 作为附件上传（release notes 自动生成）。

## 技术说明与限制

- **仅 IPv4 转发**：IPv6 流量全部导入 TUN 后静默丢弃，防止应用通过 v6 绕过管控（v6-only 环境下应用将无法联网）。
- **ICMP 黑洞**：ping 不通属于预期现象（TCP/UDP 不受影响）。
- **IP 分片丢弃**：极少数超大 UDP 包（>MTU 且应用不分片的场景）会被丢弃。
- **DNS**：由系统代发的查询无法归属到具体应用，按「不限速不丢包」直接转发（可正常解析）。
- **性能**：纯 Kotlin 用户态转发，千兆带宽上限受限；限速/弱网模拟场景（≤ 数十 Mbps）完全够用。
- **指定网络的边界**：Android 不允许普通应用创建任意的 per-app 路由，本工具的「仅 WiFi/仅流量」基于 socket 绑定实现，覆盖 TCP/UDP 全部流量；强制 WiFi 但 WiFi 不可用时应用表现为无法联网（符合预期）。
- 若系统 ROM 限制 `getConnectionOwnerUid`，极少数连接可能无法识别归属应用，此时按「不限速」放行（保底可用，不会断网）。

## 目录结构

```
app/src/main/java/com/netbrake/app/
├── App.kt                     # Application 入口
├── data/
│   ├── Rules.kt               # 规则模型 + JSON 持久化仓库
│   └── AppList.kt             # 已安装应用枚举
├── vpn/
│   ├── VpnBrakeService.kt     # VpnService + 前台服务 + TUN 建立
│   └── EngineHolder.kt        # UI<->引擎桥
├── core/
│   ├── Engine.kt              # 转发引擎主体（TUN 读写/分发/UID 识别）
│   ├── IpPacket.kt            # IPv4/TCP/UDP 解析与构造（含校验和）
│   ├── TcpSession.kt          # 简化 TCP 栈 + socket 转发
│   ├── UdpFlow.kt             # UDP 转发流
│   ├── TokenBucket.kt         # 令牌桶限速
│   ├── NetworkHub.kt          # WiFi/蜂窝网络追踪与 socket 绑定
│   └── Stats.kt               # 流量统计
└── ui/
    ├── MainActivity.kt        # 主页（总开关/规则列表/实时速率）
    ├── AppPickerActivity.kt   # 应用选择器（搜索/系统应用）
    ├── RuleEditActivity.kt    # 规则编辑（网络/限速/丢包/预设）
    └── RulesAdapter.kt        # 列表适配器
```

## 许可

[MIT](LICENSE) © kegechen

本工具仅提供本机网络管控能力，请在自己拥有或已获授权的设备上使用。
