package com.example.rk3399_control

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun RouterPanel(repository: ControlRepository) {
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    var enabled by remember { mutableStateOf(true) }
    var uplink by remember { mutableStateOf("auto") }
    var eth0 by remember { mutableStateOf("auto") }
    var eth1 by remember { mutableStateOf("auto") }
    var wait by remember { mutableStateOf("20") }
    var ip by remember { mutableStateOf("192.168.31.1") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("board") }
    var menu by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    fun refresh() {
        repository.networkStatus { result ->
            values = result.output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (!loaded) {
                enabled = values["enabled"] != "0"
                uplink = values["uplink"].orEmpty().ifBlank { "auto" }
                eth0 = values["eth0Mode"].orEmpty().ifBlank { "auto" }
                eth1 = values["eth1Mode"].orEmpty().ifBlank { "auto" }
                wait = values["wait"].orEmpty().ifBlank { "20" }
                ip = values["lanIp"].orEmpty().ifBlank { "192.168.31.1" }
                preset = values["preset"].orEmpty().ifBlank { if (values["enabled"].isNullOrBlank()) "board" else "custom" }
                loaded = true
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(5000) } }
    fun complete(result: ShellResult) { busy = false; message = result.output; refresh() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("单 WAN 路由 / 双网口 LAN", style = MaterialTheme.typography.titleMedium)
            Text("当前上行：${values["activeWan"].orEmpty().ifBlank { "无" }} · LAN ${values["lanIp"].orEmpty()}")
            for (iface in listOf("eth0", "eth1")) Text("$iface · ${values["$iface.role"].orEmpty()} · ${values["$iface.ip"].orEmpty()} · ${values["$iface.mac"].orEmpty()}")
            Text("DHCP ${if (values["dhcp"] == "1") "运行中" else "未运行"} · IPv4 转发 ${values["forward"]}")
            Text(values["message"].orEmpty(), style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !busy && values["pendingUntil"].isNullOrBlank()) {
                    Text("场景：${routerPresets.firstOrNull { it.id == preset }?.title ?: "高级自定义"}")
                }
                DropdownMenu(menu, { menu = false }) {
                    routerPresets.forEach { choice -> DropdownMenuItem(text = { Text(choice.title) }, onClick = { preset = choice.id; menu = false }) }
                    DropdownMenuItem(text = { Text("高级自定义") }, onClick = { preset = "custom"; advanced = true; menu = false })
                }
            }
            Text(routerPresets.firstOrNull { it.id == preset }?.description ?: "保留现有逐接口配置与无线模式", style = MaterialTheme.typography.bodySmall)
            if (preset == "wireless") Text("请先在 WiFi 页面连接上游。并发热点跟随 STA 信道；该场景仍为实验功能。")
            Text("上游接 WAN，下游接 LAN。选择与当前接线对应的场景；仅 LAN/离线 NAS 不要接已有路由器 LAN。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
            if (advanced) {
            Row { Switch(enabled, { enabled = it }, enabled = !busy); Text("启用统一路由管理", Modifier.padding(top = 12.dp)) }
            if (preset == "custom") {
            listOf("auto" to "自动选 WAN", "eth0" to "板载 WAN", "eth1" to "USB WAN", "wlan0" to "WiFi WWAN", "none" to "全 LAN").chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (id, title) ->
                    FilterChip(uplink == id, { uplink = id }, label = { Text(title) }, enabled = !busy)
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(eth0 == "lan", { eth0 = if (eth0 == "lan") "auto" else "lan" }, label = { Text("板载仅 LAN") })
                FilterChip(eth1 == "lan", { eth1 = if (eth1 == "lan") "auto" else "lan" }, label = { Text("USB 仅 LAN") })
            }
            }
            OutlinedTextField(wait, { wait = it.filter(Char::isDigit).take(3) }, label = { Text("DHCP 检测时间 3–120 秒") }, singleLine = true)
            OutlinedTextField(ip, { ip = it }, label = { Text("LAN 地址 /24") }, singleLine = true)
            }
            Button(onClick = {
                busy = true
                if (preset == "custom") repository.configureRouter(enabled, uplink, wait.toIntOrNull() ?: 0, ip, eth0, eth1, ::complete)
                else repository.configureRouterPreset(preset, enabled, wait.toIntOrNull() ?: 0, ip, ::complete)
            }, enabled = loaded && !busy && values["pendingUntil"].isNullOrBlank()) { Text("应用场景（需确认）") }
            if (!values["pendingUntil"].isNullOrBlank()) {
                Text("待确认：120 秒内验证网络，否则恢复原配置", color = MaterialTheme.colorScheme.error)
                Row {
                    Button(onClick = { busy = true; repository.confirmRouter(values["token"].orEmpty(), ::complete) }, enabled = !busy) { Text("确认保留") }
                    TextButton(onClick = { busy = true; repository.rollbackRouter(::complete) }, enabled = !busy) { Text("立即回滚") }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
