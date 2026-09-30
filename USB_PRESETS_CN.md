# USB 启动预设 A/B（仅本机固件）

这台 AIO-3399C-AI 不带 SPR2801S NPU。A 是原固件布局：Type-C Host、
USB3 Type-A OTG；B 是 Type-C OTG、USB3 Type-A Host。B 同时修改 `resource`
分区设备树的两个 `dr_mode` 和 `boot` 分区 ramdisk 中的
`sys.usb.controller=fe800000.dwc3`。只改其中一个会让 ADB Gadget 绑定错误。

`tools/prepare_usb_presets.py` 只离线生成镜像，**不会刷机**。输入必须是这台板
当前固件的完整 `resource`（16 MiB）和 `boot`（32 MiB）备份。程序校验 RSCE
条目、DTB 节点及 KRNL/gzip/newc 结构；如果固件结构不同，直接拒绝生成。
生成的二进制镜像不加入 Git。

本次原始备份保存在
`C:\Users\10145294\Documents\ChatGPT\rk3399开发\usb-preset-research`；
设备上的四个镜像在 `/data/local/rk3399-control/usb-presets/`。在本机固件上校验
过的 SHA-1 是：

| 文件 | SHA-1 |
| --- | --- |
| `resource-mode-a.img` | `e013e40124d32e1f0ba36e23b6aa0dfada7d1b96` |
| `boot-mode-a.img` | `480677902d028b2ef26bab7f6944ec6cdae05efa` |
| `resource-mode-b.img` | `0938854fa45cbd74b4ac6a81aa555b366e018486` |
| `boot-mode-b.img` | `7b53384da8481a1cb28b3254b1dbb057ae24212d` |

App/手机端按下 A 或 B 时先校验镜像、分区大小、当前分区是否仍为已知 A/B，
再写入 `mmcblk1p4`（resource）和 `mmcblk1p6`（boot），读回校验后标记“下次
启动预设”。这一步**不自动重启**。当前线缆连接状态不参与判断；真正换角色发生
在下一次重启。启动分区不是冗余 A/B，意外断电可能需要通过 Type-C MaskRom
刷回完整固件或上述原始镜像。不要把镜像、硬编码校验值用于另一块板。

本机已通过控制 API 完成 A→B→A 两次写入和重启验证：选中 B、尚未重启时状态为
`当前 A / 下次 B`；重启后为 `当前 B / 下次 B`，Type-C 设备树为 `otg`、
Type-A 为 `host`，Gadget UDC 是 `fe800000.dwc3`。选中 A、尚未重启时为
`当前 B / 下次 A`；重启后为 `当前 A / 下次 A`，两口分别回到 `host`、
`otg`，Gadget UDC 为 `fe900000.dwc3`。最终实机保留原始预设 A。

模式 B 下未验证 Type-A 外接存储设备和 Type-C 插拔/OTG 自动角色判断。
另外实机网口处于自适应模式时，本次重启后落到 LAN `192.168.1.1`，
没有回到路由器分配的 WAN 地址；这不影响上述 USB 状态验证，但可能导致手机
暂时无法从原局域网地址访问控制页。网口等待值已恢复原设定 6 秒。
