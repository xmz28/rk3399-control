# RK3399 控制台

这是运行在 RK3399 本机上的简易 Android 控制面板，针对当前已部署的
`/data/local/rk3399-nas` 与 `/data/local/rk3399-hotspot` 环境。

## 功能

- 查看 RK3399 型号、Android/API、root、启动方式、eth0/wlan0 地址和 AP 状态。
- 查看并控制 Samba、HTTP 文件管理、Primitive FTPd、OpenSpeedTest、iperf3 和 mDNS。
- 为各服务保存开机启用状态；App 收到 `BOOT_COMPLETED` 后恢复这些状态。
- 修改 HTTP、FTP、SFTP、iperf3 端口并重启对应服务。
- 修改热点 SSID、WPA2 密码、2.4/5 GHz 频段和信道，在 AP 与 STA 模式间切换。
- 切换有线网口的自动 WAN/LAN、固定 LAN、WAN DHCP、WAN 静态 IP、WAN PPPoE
  模式，并可调整自动识别等待时间。
- 查看系统、NAS、Wi-Fi、控制器日志。
- 执行带 60 秒超时的 root shell 命令。
- 在局域网开放 8765 端口供手机端远程查看状态、切换服务、修改端口和网络、读取日志。
- 远程切换 STA 后扫描附近网络并连接 WPA/WPA2 热点；远程一次修改 Samba、HTTP、
  FTP 和 SFTP 的共用 `admin` 密码。
- 查看两个 USB3 控制器角色；对设备树原始模式为 OTG 的 USB3 Type-A 提供临时
  Host / Device / OTG 切换，本机与手机远程控制均可操作。

## 使用前提

- 设备为已 root 的 Android，且 `su -c` 对此 App 可用。
- 已使用同目录 RK3399 NAS 部署包安装 NAS、热点和相关 APK。
- 第一次打开 App 后允许 root；App 会安装控制脚本到
  `/data/local/rk3399-control/controller.sh`。

FTP 和 SFTP 由 Primitive FTPd 的同一进程管理，因此共用一个启停/开机开关，
但端口可分别修改。切换 AP/STA 会中断当前 Wi-Fi 连接。OpenSpeedTest 没有可用的
后台启动接口，服务器离线时需要短暂拉起其界面并点击启动。

远程控制账号固定为 `admin`，默认密码为 `admin`。每次请求都必须通过 HTTP Basic
认证；手机端可记住密码，并可在验证后修改控制密码。只接受私网/本机来源，远程
接口不开放 root 终端或文件读写。当前是明文 HTTP，简单密码仅适合可信家用局域网；
不要将 8765 端口映射到公网。远程切换 AP/STA 或网口角色可能使当前连接断开。
NAS 密码与控制密码是两套独立凭据；改 NAS 密码时会同步修改 Samba 账号、
Copyparty 账号和 Primitive FTPd 的 FTP/SFTP 账号。NAS 密码限定为 4–64 位
字母、数字或 `._@#-`，避免供应商配置格式与脚本转义不一致。

Primitive FTPd 的偏好文件必须保持应用 UID 与 SELinux 标签。控制脚本仅在
开机开关变化时原位修改其偏好；修改 FTP/SFTP 端口也原位写入，避免服务无法启动。

本机 AIO-3399C-AI 的 Type-C 对应 `fe800000`，设备树为固定 `host`；当前内核
不允许 App 运行时改变这个控制器。USB3 Type-A 对应 `fe900000`，设备树为 `otg`，
可通过厂商 `dwc3_mode` 接口切换。切换前必须拔下电脑数据线或安全卸载并拔下
Type-A 外设；脚本会检查 Gadget 连接状态和外设数量并拒绝带线切换。切换 USB
可能断开 ADB，且不写入设备树，重启后回到固件默认角色。Type-C Device/ADB 迁移
需要修改并刷入设备树、配置 Android Gadget，不能仅靠本 App 的按钮完成。

实机 Wi-Fi 驱动支持 2.4 GHz 信道 1–13，以及 5 GHz 信道
36/40/44/48/52/56/60/64/149/153/157/161/165。热点固定使用 20 MHz；切换频段或信道时会
重启热点，已连接的客户端需要重新连接。板载 Android 7 `hostapd` 不接受标准
`ieee80211ac` 配置项，因此当前 5 GHz 使用 HT20，而不是 802.11ac/VHT。
52/56/60/64 为屏蔽箱实验信道；当前驱动没有雷达检测能力，不得在开放环境使用。

自动 WAN/LAN 模式会先等待以太网通过 DHCP 获得上游地址；超过设定时间仍未获得
地址时，把 `eth0` 切为 `192.168.1.1/24` 并启动 LAN DHCP。固定 LAN 使用相同的
地址段。WAN 静态 IP 需要填写地址、前缀、网关和 DNS。PPPoE 账号与密码只保存在
本机 App 私有配置和 root 配置目录中；切换网口模式会短暂中断有线网络。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon --offline
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

最低 Android 版本为 7.0（API 24）；已在 AIO-3399C-AI Android 7.1.2
（API 25、arm64-v8a、system-init 启动方式）上安装并检查。

## 实机验证

- 远程控制 8765：未认证及错误密码均返回 401；`admin/admin` 可读取状态，
  FTP 开关和端口操作已实测；无密码无法执行控制。
- FTP 偏好文件的应用 UID/SELinux 标签在开关与端口操作后保持不变，FTP/SFTP
  均可使用 `admin/admin` 登录。
- USB：实机读取 `fe800000=host`、`fe900000=peripheral`，`fe900000` 原始
  设备树模式为 `otg`；已验证同模式请求无副作用、Type-C 请求被拒绝、连接 ADB
  时切换 Type-A Host 被拒绝。尚未拔线验证真正的角色切换与重新枚举。
- Redmi K30 5G 手机上已显示远程 USB 状态与禁用态；带线时三个切换按钮不可用。
- AP→STA 已通过有线控制接口切换，STA 扫描可返回附近 SSID、频率和信号强度；
  STA→AP 可恢复 `RK3399` 热点。后台已修复每 15 秒重复清理 STA 地址的问题。
- NAS 共用密码临时改为测试密码后，HTTP、FTP、Samba 登录均成功；随后恢复
  `admin` 并复验 HTTP、Samba。FTP/SFTP 使用同一份 Primitive FTPd 密码配置，
  本轮未单独执行 SFTP 登录。手机号端的 STA 入网操作尚待有可用密码的热点验证。

- 固定 LAN：系统静态地址为 `192.168.1.1/24`，有线 DHCP 与热点 DHCP 可同时运行。
- WAN 静态 IP：IP、前缀、网关和两个 DNS 均能写入系统以太网配置。
- WAN DHCP：可恢复系统 DHCP；自动 WAN/LAN 的等待时间可保存，最终默认值为 6 秒。
- 5 GHz AP：信道 36 启动成功，实测频率 `5180 MHz`，状态为 `ENABLED`。
- 屏蔽箱 DFS 实验信道：52/56/60/64 均启动成功，实测频率依次为
  `5260/5280/5300/5320 MHz`，状态均为 `ENABLED`，且驱动报告 `CAC=0`。
- 2.4 GHz AP：从 5 GHz 切回信道 1 成功，实测频率 `2412 MHz`，状态为 `ENABLED`。
- PPPoE：板上 `pppd`、`pppoe-start` 和系统 PPPoE 服务均存在，App 配置流程已接入；
  因没有真实运营商账号和桥接光猫，尚未验证实际拨号。

这里的 WAN 表示 `eth0` 作为上游联网接口，并不自动启用 NAT 或把设备变成路由器。
切换 Wi-Fi 频段、信道、AP/STA 或以太网模式都会造成对应网络短暂中断。
