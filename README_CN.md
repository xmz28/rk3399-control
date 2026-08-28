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

## 使用前提

- 设备为已 root 的 Android，且 `su -c` 对此 App 可用。
- 已使用同目录 RK3399 NAS 部署包安装 NAS、热点和相关 APK。
- 第一次打开 App 后允许 root；App 会安装控制脚本到
  `/data/local/rk3399-control/controller.sh`。

FTP 和 SFTP 由 Primitive FTPd 的同一进程管理，因此共用一个启停/开机开关，
但端口可分别修改。切换 AP/STA 会中断当前 Wi-Fi 连接。OpenSpeedTest 没有可用的
后台启动接口，服务器离线时需要短暂拉起其界面并点击启动。

实机 Wi-Fi 驱动支持 2.4 GHz 信道 1–13，以及 5 GHz 非 DFS 信道
36/40/44/48/149/153/157/161/165。热点固定使用 20 MHz；切换频段或信道时会
重启热点，已连接的客户端需要重新连接。板载 Android 7 `hostapd` 不接受标准
`ieee80211ac` 配置项，因此当前 5 GHz 使用 HT20，而不是 802.11ac/VHT。5 GHz
不开放 DFS 信道，以避免雷达检测带来的启动等待和运行中停播。

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

- 固定 LAN：系统静态地址为 `192.168.1.1/24`，有线 DHCP 与热点 DHCP 可同时运行。
- WAN 静态 IP：IP、前缀、网关和两个 DNS 均能写入系统以太网配置。
- WAN DHCP：可恢复系统 DHCP；自动 WAN/LAN 的等待时间可保存，最终默认值为 6 秒。
- 5 GHz AP：信道 36 启动成功，实测频率 `5180 MHz`，状态为 `ENABLED`。
- 2.4 GHz AP：从 5 GHz 切回信道 1 成功，实测频率 `2412 MHz`，状态为 `ENABLED`。
- PPPoE：板上 `pppd`、`pppoe-start` 和系统 PPPoE 服务均存在，App 配置流程已接入；
  因没有真实运营商账号和桥接光猫，尚未验证实际拨号。

这里的 WAN 表示 `eth0` 作为上游联网接口，并不自动启用 NAT 或把设备变成路由器。
切换 Wi-Fi 频段、信道、AP/STA 或以太网模式都会造成对应网络短暂中断。
