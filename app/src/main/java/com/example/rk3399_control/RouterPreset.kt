package com.example.rk3399_control

data class RouterPreset(val id: String, val title: String, val description: String,
                        val uplink: String, val eth0: String, val eth1: String, val wifi: String)

val routerPresets = listOf(
    RouterPreset("board", "日常默认", "板载固定 WAN · USB LAN · 无线 LAN AP", "eth0", "auto", "lan", "ap"),
    RouterPreset("usb", "USB 千兆网卡上网", "USB 固定 WAN · 板载 LAN · 无线 LAN AP", "eth1", "lan", "auto", "ap"),
    RouterPreset("wireless", "无线上网", "WiFi WWAN · 双网口 LAN · 同信道 AP（实验）", "wlan0", "lan", "lan", "apsta"),
    RouterPreset("offline", "离线 NAS", "双网口 LAN · 无线 LAN AP · 无上行", "none", "lan", "lan", "ap"),
    RouterPreset("automatic", "双网口全自动", "板载优先、USB 备用 · 双网口自动 WAN/LAN · 无线 LAN AP", "auto", "auto", "auto", "ap")
)
