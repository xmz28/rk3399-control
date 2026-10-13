# 统一接口与路由管理

## 使用方式

板端「网络」和手机端远程控制「路由」页面提供相同配置。启用统一路由管理后，替代旧网口角色脚本和热点 DHCP 脚本；不开启时保留原控制方式。当前实现是 Android root 环境的 IPv4 路由管理器，不是安装 OpenWrt，也不等同于完整 LuCI。

## 简洁场景预设

日常页面选择场景后应用即可，检测时间、LAN 地址、管理开关和逐接口自定义收进「高级设置」。升级不会自动覆盖现有配置；首次未配置时界面默认选中「日常默认」，仍需用户点击应用。

| 场景 | 板载网口 | USB 网口 | 无线 |
| --- | --- | --- | --- |
| 日常默认 | 固定 DHCP WAN | 固定 LAN | LAN AP |
| USB 千兆网卡上网 | 固定 LAN | 固定 DHCP WAN | LAN AP |
| 无线上网（实验） | 固定 LAN | 固定 LAN | STA WWAN + 同信道 LAN AP |
| 离线 NAS | 固定 LAN | 固定 LAN | LAN AP |
| 双网口全自动 | 自动 WAN/LAN | 自动 WAN/LAN | LAN AP |

固定 WAN 即使没有 DHCP 也不会降级成 LAN；全自动最多选中一个 WAN，板载优先、USB 备用，第二个有 DHCP 上游的端口隔离。全自动场景不使用 WiFi 作为上行。

无线上网需要先在 WiFi 页面切 STA 并连接上游，否则应用会拒绝且不改变配置。选择此场景之前，两个有线端口应仅接下游设备，不能仍接已有路由器 LAN。场景应用会调整无线模式，可能断开手机，请重新连接后在 120 秒内确认。WiFi 页面主动改变模式不会被预设反复覆盖；再次应用预设才恢复其无线模式。

## 高级逐接口配置

| 上行选择 | 行为 |
| --- | --- |
| 自动 | 按板载 eth0、USB eth1、WiFi STA wlan0 优先级选择一个可用上行 |
| 板载 WAN / USB WAN | 指定网口 DHCP 上行，暂时无租约时等待连接 |
| WiFi WWAN | 已连接 WiFi 的 STA 作为上行 |
| 全 LAN | 两个有线端口都加入 LAN，无外部上行 |

自动识别依据 DHCP 租约：有 DHCP 上游的端口作为 WAN 候选；未收到响应的端口在设定检测时间后加入 LAN。时间可设置 3–120 秒，默认 20 秒。明确下游的端口可指定「仅 LAN」。第二个收到上游 DHCP 的端口被隔离，不能把它直接桥接进本机 DHCP LAN。静态上游、纯 PPPoE 自动识别不在本版范围；PPPoE 本身不保证存在 DHCP。

LAN 默认 192.168.31.1/24，可设置 192.168.x.1–99/24，DHCP 池 .100–.200。LAN 和 WAN 不能重叠。AP 加入 LAN；STA 作为 WWAN 或备用上行。LAN→WAN 开启 IPv4 NAT，WAN 不主动转发到下游客户端。

每次应用后必须在 120 秒内重新连接并「确认保留」；超时恢复上一份配置，也可立即回滚。未确认就重启会恢复上一份配置。切换上行且 LAN 地址不变时保留热点和 LAN 桥，避免主动断开手机。ARP 按网卡自身地址回应，切换上行发送更新通知，避免隔离端口代答 WiFi 地址。

「仅 LAN」或「全 LAN」会对接入设备发放 DHCP：不要在此模式下把对应端口接到已有路由器 LAN，否则可能出现两个 DHCP 服务器。

## 上下游访问 NAS 与管理

下游通过 LAN 地址访问 RK3399；上游通过选中 WAN 的 DHCP 地址访问。仅直接连接的私有上游子网放行控制 API 8765、SMB 445/139、HTTP、FTP/SFTP、测速服务端口；NAS 与后台控制仍需要原账号密码。FTP 被动端口从现有 copyparty 的 ftp-pr 配置读取（当前 5678），限制范围最多 101 个端口。

不提供公网管理、WAN→LAN 端口映射或 IPv6 路由。统一管理开启时阻断原始上行端口 IPv6 入站与转发，避免绕过 IPv4 访问规则。HTTP Basic 仍是明文传输上的认证，只适用于可信局域网。

## 实验：AP + STA

先切 STA、扫描并连接上游，再应用「无线上网」场景或使用 WiFi 页实验并发入口。当前 AP6256/Broadcom 固件已实测可同时连接上游和接入手机，但热点只能跟随 STA 的实际频段与信道；上游 5GHz CH60 时热点也在 CH60，不能独立选择 2.4GHz。界面显示实际并发 AP 信道。

虚拟 AP 使用独立本地 MAC，避免厂商默认 AP MAC 与 P2P MAC 冲突造成手机关联失败。该模式仍为实验功能：未完成跨上游信道漫游、长期压测、掉线重连及全部信道覆盖测试，不改变无线监管或雷达检测规则。

## 2026-10-13 实机验证

用户后续反馈：「测了目前没啥问题」。这是当前使用反馈，不扩大以下实测范围，也不代表所有场景和长期稳定性已完成验证。

- 板载接路由器、USB 接电脑：自动 eth0 WAN，eth1 LAN；电脑获得 192.168.31.159，热点手机获得 192.168.31.116；上游控制接口返回 200。
- 两根网线对调：自动 eth1 USB WAN（192.168.1.133），eth0 LAN；手机经 USB WAN ping 上游路由器 3/3 成功、TTL=63，转发计数增长。
- WiFi WWAN + 并发热点：STA 上行 192.168.1.135，手机仍在热点 192.168.31.116；上游路由器 ping 3/3、TTL=63，公网域名解析与 ping 2/2 成功，NAT 出口为 wlan0。
- 控制 API：下游热点侧 192.168.31.1 返回 200；修复隔离端口 ARP 代答后，上游电脑访问 WiFi WAN 地址返回成功。
- FTP：上游电脑通过 WiFi WAN 被动模式列目录成功。
- 故意不确认一次上行变更，120 秒后自动恢复之前的自动上行配置；过期 token 无法确认。
- 两端 APK 均已构建、覆盖安装；手机已通过密码验证进入路由页。

全 LAN 隔离环境、重启恢复、自动 WAN 故障切换长期稳定性、各 NAS 协议完整读写和吞吐未全部验证，不应把以上结果当作完整测试矩阵。

## mDNS 与 RNDIS 验证补充

新增 mdns.sh：允许接口随当前角色调整为 br-lan 和私有 WAN，在各自链路发布 a.local 及 iperf3 服务，不反射上下游其他设备。上游放行 UDP 5353，独立多播策略路由保证回复从对应接口发送。记录 iperf3 实际配置端口，停用服务时删除发布记录。

实测手机热点侧 a.local 解析 192.168.31.1、ping 成功；上游 Windows 使用 IPv4 的 a.local 解析 192.168.1.133、ping 成功。手机同时发现 RK3399 NAS 的 iperf3 服务。IPv6 未纳入此实现，Windows 默认 ping 可能命中旧 IPv6/其他发现协议地址，不代表 IPv6 已支持。

RNDIS：当前内核 CONFIG_USB_NET_RNDIS_HOST=y、CONFIG_USB_F_RNDIS=y、CONFIG_USB_CONFIGFS_RNDIS=y，具备 Host 接收 USB 共享网络及 Device/Gadget 提供 RNDIS 的底层支持。当前实测 USB 千兆网卡是 r8152/eth1，不是 RNDIS。手机 USB 共享常产生 usb0 等不同名称，本路由管理器尚未接管，也没有增加 RNDIS Gadget 开关；不能把内核支持当作端到端实测通过。

## 实现入口

- router.sh：监督、DHCP 探测、br-lan、策略路由表 180、独立防火墙链和事务回滚。
- router-lease.sh：DHCP 事件只保存租约，避免未选中的上游私自改 IP/路由。
- router-ap.sh / router-apsta.sh：独立热点及并发热点初始化。
- GET /api/v1/network：接口、角色、地址、当前上行、待确认状态。
- POST /api/v1/network：enabled、uplink、wait、lanIp、eth0Mode、eth1Mode。
- POST /api/v1/network/preset：preset=board/usb/wireless/offline/automatic，以及 enabled、wait、lanIp。
- POST /api/v1/network/confirm：token；POST /api/v1/network/rollback：立即恢复。
- POST /api/v1/wifi：mode=apsta 进入实验并发模式。

当前只管理 eth0、eth1、wlan0/ap0；其他 USB 网卡名称、VLAN、多个 LAN 子网、IPv6、多 WAN/负载均衡尚未实现。路由日志在 /data/local/rk3399-control/logs/router.log。
