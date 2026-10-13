# RK3399 Control

运行在 RK3399 本机上的 Android 控制台，管理 NAS 服务、Wi-Fi 热点、有线网络、外部 USB 存储和 USB 启动预设，并为手机端提供局域网控制接口。

当前目标设备为 **AIO-3399C-AI，Android 7.1.2，已 root**。项目围绕这台板子和现有服务环境开发，不提供其他板子的兼容性保证。

## 功能

- 查看系统、root、网卡地址、服务状态和日志
- 管理 NAS、OpenSpeedTest、iperf3 与 mDNS 服务
- 设置服务开机启用状态与端口
- 切换 Wi-Fi AP / STA，设置热点频段、信道和密码
- 配置网口自动 WAN / LAN、固定 LAN、DHCP、静态 IP 与 PPPoE
- 新增统一 IPv4 单 WAN 路由：板载 / USB 网口自动识别、WiFi WWAN、LAN 桥接与 DHCP、120 秒确认回滚
- 实验性 AP+STA 并发（当前板子热点跟随上游 WiFi 频段和信道）
- 查看外部 USB 卷，安全弹出、重新挂载及格式化 FAT32
- 切换当前固件专用的 USB 启动预设 A / B
- 通过 8765 端口提供密码认证的局域网控制 API

详细功能及各项验证范围见 [中文使用说明](README_CN.md)。

新增路由管理的配置、实测和限制见 [路由管理说明](ROUTER_CN.md)。启用后由统一管理器接管网口，旧单网口静态 IP / PPPoE 页面停用。

## 使用前提

1. App 安装在 RK3399 本机，最低 Android 7.0（API 24）。
2. `su -c` 可用，首次打开时允许 root。
3. 板子已部署 Alpine、NAS、热点及相关服务。App 是控制台，不是完整 NAS 安装器。
4. 外部存储管理需要配套的多盘 NAS、Python 和安全卸载脚本。

配套仓库：[RK3399 Android NAS](https://github.com/xmz28/rk3399-android-nas)。其首次公开版本是旧版脚本快照，后续 NAS 更新需同步使用；不能据此认定旧快照已支持本 App 全部功能。

## 从源码构建

使用 Android Studio 打开项目，安装 Gradle 构建要求的 Android SDK，并让 IDE 完成依赖同步。

- Gradle Wrapper：9.4.1
- Android Gradle Plugin：9.2.1
- Gradle JVM：JDK 21（仓库提供 toolchain 配置）
- compileSdk：36，minor API 1；targetSdk：36

PowerShell 构建：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

仅在依赖已缓存时使用 `--offline`。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本仓库不包含签名密钥、设备镜像、构建缓存或发布 APK。

## USB 启动预设

| 预设 | Type-C | USB3 Type-A |
| --- | --- | --- |
| A | Host | OTG |
| B | OTG | Host |

预设绑定当前板子的分区布局和固件哈希。写入的是 resource / boot 分区，下次重启生效；镜像不随源码发布。源码公开不意味着其他板子可以套用，完整准备与恢复说明见 [USB 预设说明](USB_PRESETS_CN.md)。

## 远程控制与配置

控制接口使用 HTTP Basic，账号 `admin`，初始密码 `admin`。保留现有协议供手机端联调，并可通过认证后的接口修改控制密码。接口仅用于可信局域网，不应映射到公网。

本次整理将包含密码的 `rk3399_control.xml` 排除出 Android 云备份和设备迁移。正常运行和覆盖安装仍保留本机配置；清除 App 数据或迁移设备后需重新设置密码、网络及相关配置。见 [安全说明](SECURITY.md)。

## 项目结构

```text
app/src/main/java/       Kotlin 与 Compose 界面、控制逻辑、远程 API
app/src/main/assets/     root 控制脚本与存储管理后端
app/src/main/res/        界面资源与备份规则
tools/                  固件预设离线生成工具与网口服务配置示例
gradle/                 Wrapper 与依赖版本
README_CN.md            详细中文说明与历史验证记录
USB_PRESETS_CN.md       当前固件 USB 预设说明
```

## 项目状态

这是当前开发源码的公开整理，不增加其他板子的支持，也不在本次更新 NAS 服务。历史实机记录与本次构建验证应分别理解；具体限制见中文说明。

## 致谢与许可

本仓库自有代码与文档以 [MIT License](LICENSE) 发布。AndroidX、Compose、Kotlin、Gradle 及其他构建依赖保留各自许可，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。
