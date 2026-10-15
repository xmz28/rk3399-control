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

无线上网需要先在 WiFi 页面切 STA 并连接上游，否则应用会拒绝且不改变配置。选择此场景之前，两个有线端口应仅接下游设备，不能仍接已有路由器 LAN。场景应用若调整无线模式，可能断开手机，需重新连接后在 120 秒内确认。WiFi 页面主动改变模式不会被预设反复覆盖；再次应用预设才恢复其无线模式。

## 高级接口选择

| 上行选择 | 行为 |
| --- | --- |
| 无上行 | 不选择 WAN，仅在所选 LAN 成员间通信 |
| 检测到的有线接口 | 单选 DHCP WAN，包括板载、USB 千兆及 USB 蜂窝/共享网卡，暂时无租约时等待连接 |
| WiFi WWAN | 已连接 WiFi 的 STA 作为上行 |

选择「高级自定义」后只有两个角色选择器：WAN 单选下拉（可选无上行），LAN（br-lan）多选下拉。有线接口和无线 AP 可加入 LAN，也可全部不选；WAN 自动从 LAN 选项中排除，服务端再次校验角色不能重叠。未选中的接口不参与本机路由或 DHCP，也不加入网桥。空 LAN 或取消 AP 会弹出断连提醒，仍保留 120 秒确认/回滚。

动态识别 sysfs 中的以太网类型接口，名称支持 ethN、usbN、enxMAC；当前板子显示 eth0、eth1、eth2。无线 AP 是逻辑成员 ap，实际映射独立热点 wlan0 或并发热点 ap0；wlan0 STA 只可选作 WAN，不直接加入 LAN。无线模式仍在 WiFi 页配置。已选 LAN 网卡拔出后仍保留配置，可在列表中取消选择。

自动识别依据 DHCP 租约：有 DHCP 上游的端口作为 WAN 候选；未收到响应的端口在设定检测时间后加入 LAN。时间可设置 3–120 秒，默认 20 秒。明确下游的端口可指定「仅 LAN」。第二个收到上游 DHCP 的端口被隔离，不能把它直接桥接进本机 DHCP LAN。静态上游、纯 PPPoE 自动识别不在本版范围；PPPoE 本身不保证存在 DHCP。

LAN 默认 192.168.31.1/24，可设置 192.168.x.1–99/24，DHCP 池 .100–.200。LAN 和 WAN 子网不能重叠。预设将 AP 加入 LAN；高级模式只桥接所选 LAN 成员。LAN→WAN 开启 IPv4 NAT，WAN 不主动转发到下游客户端。

预设应用按实际断连风险判断：统一管理仍开启、LAN 地址不变、当前 AP 已桥接且链路正常、目标无线模式不变并保留 AP 时直接保留，不强制确认。有线 WAN 或成员变化本身不要求确认。LAN 地址变化、无线模式变化、移除 AP、关闭管理或当前 AP 不可用时，仍需在 120 秒内重新连接并「确认保留」；超时恢复上一份配置，也可立即回滚。未确认就重启会恢复上一份配置。

高级自定义提供「安全回滚」开关，默认开启（强制确认），关闭时会额外提示并直接保留配置，不会超时恢复。API 的 rollbackPolicy 支持 auto/on/off，缺省 auto；预设始终使用 auto。自动判断只保证热点控制入口未被主动改变，不保证原有线控制会话不中断。切换上行且 LAN 地址不变时保留热点和 LAN 桥；ARP 按网卡自身地址回应，避免隔离端口代答 WiFi 地址。

「仅 LAN」或「全 LAN」会对接入设备发放 DHCP：不要在此模式下把对应端口接到已有路由器 LAN，否则可能出现两个 DHCP 服务器。

## 上下游访问 NAS 与管理

2026-10-15 配套 NAS 更新：直连私网上游按原来源子网限制增加 SSH TCP 22、Windows WSD UDP 3702 / TCP 5357；LAN 接口沿用原规则。没有开放通用 WAN 到 LAN 转发。WSD 多播通常只覆盖同一链路，不能据此认定跨子网“网络”列表自动发现已通过；本轮只完成源码与构建检查，尚未重新实测这些端口。

热点密码查看：通过控制密码认证后，手机 Wi-Fi 页从 GET /api/v1/wifi/ap-password 读取实际 hostapd 中保存的热点密码，填入密码框并默认显示；本机配置页也自动读取。两端提供隐藏按钮，不覆盖编辑中的密码。接口设置 Cache-Control: no-store，不将密码放入普通状态；HTTP 传输仍是明文，仅在可信局域网使用。

网页入口 nginx 监听 80；直连私网上游也放行 TCP 80，修复 WAN 侧能访问 :3923 却无法打开 http://a.local/ 的问题。此规则沿用私有 WAN 子网来源限制，不开放公网入站。

本轮实测：Windows 在直连私网上游访问 http://a.local/ 和 http://192.168.1.152/ 均返回 nginx 200；同 LAN/AP 的板载预设直接保留、无 pending；高级 rollbackPolicy=on 产生待确认状态并可立即回滚，off 无 pending；预设将 LAN 改到 192.168.32.1 仍触发确认，等待超时后恢复 192.168.31.1、板载 WAN 且清除 pending。两端新 APK 编译、覆盖安装成功。无线模式变化、关闭管理及移除 AP 的回滚分支尚未全部实机覆盖。

下游通过 LAN 地址访问 RK3399；上游通过选中 WAN 的 DHCP 地址访问。仅直接连接的私有上游子网放行控制 API 8765、SMB 445/139、HTTP、FTP/SFTP、测速服务端口；NAS 与后台控制仍需要原账号密码。FTP 被动端口从现有 copyparty 的 ftp-pr 配置读取（当前 5678），限制范围最多 101 个端口。

不提供公网管理、WAN→LAN 端口映射或 IPv6 路由。统一管理开启时阻断原始上行端口 IPv6 入站与转发，避免绕过 IPv4 访问规则。HTTP Basic 仍是明文传输上的认证，只适用于可信局域网。

## 实验：AP + STA

### 2026-10-13 独立 AP 启动路径对照

临时暂停路由及控制守护进程，保留有线地址和 ADB，比较旧独立热点的 prepare_radio/start_hostapd 路径（不启用其旧 DHCP/NAT 守护）与当前 router-ap.sh。两条路径均请求信道 64，实际 wl 报告 36/80，内核新增日志均为 move channel 64 => 36；手机扫描记录为 5180MHz。未修改雷达、监管设置、内核或固件。

此结果说明：当前固件状态下，简单回退热点启动脚本并不能恢复 64，不能把问题单独归因于新版 LAN 网桥。测试不等同于完整回退历史固件，也不能证明过去所有 DFS 成功记录均错误。旧文档中的 52–64 成功结论仍待独立频率证据复验。

测试前配置文件保存 52、实际运行 36；恢复原 52 配置时无线未正常发射，最终通过正常热点配置入口保存为 36，保留 SSID/密码及板载 WAN、192.168.31.1 LAN。诊断脚本 tools/test_ap_startup_ab.sh 仅供有 ADB 恢复通道的隔离测试，不作为日常启动脚本。

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

RNDIS：当前内核 CONFIG_USB_NET_RNDIS_HOST=y、CONFIG_USB_F_RNDIS=y、CONFIG_USB_CONFIGFS_RNDIS=y，具备 Host 接收 USB 共享网络及 Device/Gadget 提供 RNDIS 的底层支持。动态接口管理现支持 usbN 等接口名称，但未实测真正 RNDIS 设备，也没有增加 RNDIS Gadget 开关；不能把内核支持当作端到端实测通过。

### 高级模式及 USB 4G 网卡实测

- 新版两端 APK 已构建安装；原五个预设不变，新插 eth2 不自动参与原双网口预设。
- 当前 USB 4G 网卡 VID:PID 为 19d2:0536，实际工作在 CDC Ethernet 模式，由 cdc_ether 驱动生成 eth2，不是 RNDIS 模式。
- 临时选择 eth2 WAN、ap LAN：eth2 获取 192.168.1.100/24，网关 192.168.1.1；eth0/eth1 未选，不加入 LAN、无 IPv4 地址，策略路由及 NAT 出口均为 eth2。
- 手机连接 RK3399 热点，获得 192.168.31.116；访问 4G 网关 ping 2/2 成功、TTL=63，NAT 计数增长；a.local 解析 192.168.31.1、ping 2/2 成功。
- 板端可读取 4G 网卡管理网页。但网卡 DNS 对 example.com 返回 REFUSED，板端及手机公网 ping 无响应；未证明 4G 互联网可用，也未确认 SIM/APN/蜂窝注册状态。
- API 拒绝同一接口同时作 WAN 和 LAN；不确认配置后自动恢复原板载 WAN 预设。空 LAN、网卡热拔插及真正 RNDIS 尚未完成端到端测试。

## 实现入口

- router.sh：监督、DHCP 探测、br-lan、策略路由表 180、独立防火墙链和事务回滚。
- router-lease.sh：DHCP 事件只保存租约，避免未选中的上游私自改 IP/路由。
- router-ap.sh / router-apsta.sh：独立热点及并发热点初始化。
- GET /api/v1/network：接口、角色、地址、当前上行、待确认状态。
- POST /api/v1/network：新高级模式传 enabled、uplink（单接口或 none）、lanMembers（数组，如 ["eth1","ap"]）、wait、lanIp；旧 eth0Mode/eth1Mode 请求仍兼容。
- POST /api/v1/network/preset：preset=board/usb/wireless/offline/automatic，以及 enabled、wait、lanIp。
- POST /api/v1/network/confirm：token；POST /api/v1/network/rollback：立即恢复。
- POST /api/v1/wifi：mode=apsta 进入实验并发模式。

当前管理检测到的 ethN/usbN/enxMAC 有线接口和 wlan0/ap0；VLAN、任意虚拟网卡、多个 LAN 子网、IPv6、多 WAN/负载均衡尚未实现。路由日志在 /data/local/rk3399-control/logs/router.log。
