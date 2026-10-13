# RK3399 控制台

这是运行在 RK3399 本机上的简易 Android 控制面板，针对当前已部署的
`/data/local/rk3399-nas` 与 `/data/local/rk3399-hotspot` 环境。

## 功能

2026-10-13 新增统一单 WAN 路由与五个场景预设，修复热点/USB WAN 下 mDNS 发现。统一管理启用后，旧单网口静态 IP/PPPoE 设置不再接管网口。配置方式、上下游访问、AP+STA 限制和实测范围见 [路由管理说明](ROUTER_CN.md)。用户反馈当前测试暂未发现问题，长期及边界场景仍需继续验证。

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
- 查看两个 USB3 控制器角色；本机与手机可选 USB 启动预设 A/B，写入后下次重启生效。
- 在“存储”页面管理外部 USB 盘：查看设备信息、容量、已用/可用空间，安全弹出、重新挂载及格式化。

## 外部 USB 存储管理（2026-10-12）

打开底部“存储”页面，每个 USB 公共存储卷单独显示。页面每五秒刷新，展示固定编号、状态、厂商、型号、卷标、文件系统、UUID、总容量和可用空间。离线盘保留编号记录；内置 NAS 和系统盘不提供格式化入口。

停止传输后选择“安全弹出”，确认成功再拔盘。仍连接的已卸载盘可重新“挂载”。格式化当前仅支持已实测的 FAT32（vfat），必须输入完整 USB 编号确认；只读盘不能格式化。格式化期间不要拔盘或断电。通过本功能格式化会保留 USB 编号，完成后短暂重启文件服务，更新文件索引和目录路径。

本机通过 `UsbStorageBackend.kt` 调用 Alpine 内的 `nas-storage.py`。应用初始化时从 assets 部署脚本到 `/data/local/alpine-nas/opt/nas-storage.py`，需要 root，以及现有 Alpine/Python、多盘 NAS 和安全卸载脚本。格式化会暂停新 USB 共享生成，因此配套 `nas_usb.sh` 也应为支持格式化暂停标记的版本。

远程接口：`GET /api/v1/storage` 查询，`POST /api/v1/storage` 执行操作，监听现有 8765 控制端口并沿用控制密码。操作请求要求 `X-Storage-Request: 1`；浏览器请求检查来源，后端校验 UUID、USB 设备路径及当前设备身份，操作锁防止并发执行。旧页面、换盘或重新挂载后失效的身份不能继续操作。

网页统一入口为 `http://a.local/`，三个入口分别是文件管理 `/files/`、网络测速 `/speedtest/`、存储管理 `/storage/`。网页 `/api/storage` 由 Nginx 转发到上述控制接口，使用远程控制密码，与 NAS 文件服务密码独立。网页及系统配套脚本位于 RK3399 部署项目，此 Android 仓库不包含完整网页部署包。

当前多盘部署由 Copyparty 提供 FTP、SFTP、网页和 WebDAV，Samba 提供 SMB。检测到 `/etc/copyparty-base.conf` 时，控制脚本将文件服务启动交给 NAS 守护脚本，并关闭旧 Primitive FTPd 自动启动，避免抢占端口。下文 Primitive FTPd 启停、端口与密码说明属于旧部署环境，不代表本次已验证 Copyparty 下这些控制项。

本次验证：最终 `assembleDebug` 成功并安装到 AIO-3399C-AI；原生存储页面显示正常，板端重新挂载成功，手机安全弹出成功。网页经同一后端完成两次真实格式化，USB2 编号保留，最终 UUID 为 `0516-1125`，容量约 58.6 GiB；格式化后五种协议读写、跨协议内容校验和删除通过，测试文件已清理。认证、错误 UUID、旧身份及错误格式化确认文本的拒绝检查通过。原 USB1 已拔出，保留只读记录；本次未格式化 USB1，也未额外重启板子验证新功能。

## 使用前提

- 设备为已 root 的 Android，且 `su -c` 对此 App 可用。
- 已在板子上部署 NAS、热点及相关服务；配套项目为
  [RK3399 Android NAS](https://github.com/xmz28/rk3399-android-nas)。存储管理需要后续多盘脚本，旧版 NAS 源码快照不包含全部依赖。
- 第一次打开 App 后允许 root；App 会安装控制脚本到
  `/data/local/rk3399-control/controller.sh`。

FTP 和 SFTP 由 Primitive FTPd 的同一进程管理，因此共用一个启停/开机开关，
但端口可分别修改。切换 AP/STA 会中断当前 Wi-Fi 连接。OpenSpeedTest 没有可用的
后台启动接口，服务器离线时需要短暂拉起其界面并点击启动。

远程控制账号固定为 `admin`，默认密码为 `admin`。每次请求都必须通过 HTTP Basic
认证；手机端可记住密码，并可在验证后修改控制密码。只接受私网/本机来源，远程
接口不开放 root 终端或文件读写。当前是明文 HTTP，简单密码仅适合可信家用局域网；
不要将 8765 端口映射到公网。远程切换 AP/STA 或网口角色可能使当前连接断开。
包含控制、Wi-Fi 和 PPPoE 密码的 `rk3399_control.xml` 已排除出云备份和设备迁移。
正常运行和覆盖安装继续使用原配置；清除数据或换设备后需重新填写该文件中的密码与设置。
NAS 密码与控制密码是两套独立凭据；改 NAS 密码时会同步修改 Samba 账号、
Copyparty 账号和 Primitive FTPd 的 FTP/SFTP 账号。NAS 密码限定为 4–64 位
字母、数字或 `._@#-`，避免供应商配置格式与脚本转义不一致。

Primitive FTPd 的偏好文件必须保持应用 UID 与 SELinux 标签。控制脚本仅在
开机开关变化时原位修改其偏好；修改 FTP/SFTP 端口也原位写入，避免服务无法启动。

本机 AIO-3399C-AI 无 SPR2801S NPU，已用自身启动分区备份制作并重启验证两套预设：

| 预设 | Type-C `fe800000` | USB3 Type-A `fe900000` | ADB Gadget |
| --- | --- | --- | --- |
| A | Host | OTG | `fe900000.dwc3` |
| B | OTG | Host | `fe800000.dwc3` |

预设切换会校验并写入 **resource 与 boot 启动分区**，不会立即改变当前角色，
也不自动重启。下次重启生效，因此选择时无须拔当前数据线。因没有真正的 A/B
冗余启动分区，写入中断仍有风险，必须保留完整固件与 MaskRom 恢复方式。镜像
和哈希只适用于此板当前固件，其他板或更新固件后会拒绝写入。准备过程见
[`USB_PRESETS_CN.md`](USB_PRESETS_CN.md)。

实机 Wi-Fi 驱动支持 2.4 GHz 信道 1–13，以及 5 GHz 信道
36/40/44/48/52/56/60/64/149/153/157/161/165。热点固定使用 20 MHz；切换频段或信道时会
重启热点，已连接的客户端需要重新连接。板载 Android 7 `hostapd` 不接受标准
`ieee80211ac` 配置项，因此当前 5 GHz 使用 HT20，而不是 802.11ac/VHT。
52/56/60/64 为屏蔽箱实验信道；当前驱动没有雷达检测能力，不得在开放环境使用。

自动 WAN/LAN 模式会停止旧固件的 `ethernet_role` 服务，每次开机接管时清除其
遗留的 LAN 地址并重新应用模式，然后用 `dhcptool` 探测上游 DHCP。超过设定
时间仍未获得地址时，还会检查 `192.168.1.1` 是否已有路由器响应；若有，
继续重试 DHCP，不会启动与路由器竞争的 LAN DHCP。确实没有上游时，才把 `eth0` 切为
`192.168.1.1/24` 并启动 LAN DHCP。固定 WAN DHCP 也直接请求租约，避免该固件的
Android EthernetService 未跟踪 `eth0` 时停留在“连接中”。固定 LAN 使用相同
地址段。WAN 静态 IP 需要填写地址、前缀、网关和 DNS。PPPoE 账号与密码只保存在
本机 App 私有配置和 root 配置目录中；切换网口模式会短暂中断有线网络。当前
实机自动探测等待值设为 20 秒。

这台实机的旧固件还在 `sys.boot_completed=1` 时自动启动 `ethernet_role`，会在
App 接管前抢先把路由器网线误配成 LAN。已备份原文件到
`/data/local/rk3399-control/ethernet-role.original.rc`（电脑副本在
`usb-preset-research/ethernet-role.original.rc`），并用
`tools/ethernet-role.disabled.rc` 替换 `/system/etc/init/ethernet-role.rc`：只移除
自动启动触发，保留可手动启动的服务定义。恢复时以 root 把备份复制回原路径，
设置 0644 权限、执行 `restorecon`，再重启。其他固件不要直接套用此文件。

AP 状态页同时显示热点 DHCP 是否运行。热点客户端使用 `192.168.31.0/24`；
控制脚本会把该网段加入 Android 的 `eth0` 策略路由表，确保客户端访问本机服务
时回复仍走 `wlan0`，而不是错误地发向有线路由器。实机已在路由器接入的自动
WAN 模式下重启验证：网口为 `192.168.1.152`，手机接入热点获
`192.168.31.116`，能访问板端控制接口。固定 LAN 与 AP 同时运行未在本轮复测。

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
- USB：模式 B 实机重启后读到 Type-C `otg/peripheral`、Type-A `host/host`，
  `sys.usb.controller` 和 Gadget UDC 均为 `fe800000.dwc3`；随后选择预设 A 时
  当前角色保持 B，重启后恢复 Type-C `host`、Type-A `otg/peripheral`，Gadget
  回到 `fe900000.dwc3`。两套镜像写后分区哈希均与本机生成文件一致。
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
