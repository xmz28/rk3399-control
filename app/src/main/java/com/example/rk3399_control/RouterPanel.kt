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
    var members by remember { mutableStateOf(setOf<String>()) }
    var wanMenu by remember { mutableStateOf(false) }
    var lanMenu by remember { mutableStateOf(false) }
    var emptyWarning by remember { mutableStateOf(false) }
    var safetyRollback by remember { mutableStateOf(true) }
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
                uplink = values["uplink"].orEmpty().let { if (it.isBlank() || it == "auto") values["activeWan"].orEmpty().ifBlank { "none" } else it }
                members = if (values["interfaceMode"] == "explicit") values["lanMembers"].orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
                else listOf("eth0", "eth1").filter { it != uplink && (values["$it.role"] == "lan" || values["${it}Mode"] == "lan" || values["uplink"] == "none") }.toSet() +
                    if (values["wifiOperation"] in setOf("1", "2")) setOf("ap") else emptySet()
                wait = values["wait"].orEmpty().ifBlank { "20" }
                ip = values["lanIp"].orEmpty().ifBlank { "192.168.31.1" }
                preset = values["preset"].orEmpty().ifBlank { if (values["enabled"].isNullOrBlank()) "board" else "custom" }
                safetyRollback = values["rollbackPolicy"] != "off"
                loaded = true
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(5000) } }
    fun complete(result: ShellResult) { busy = false; message = result.output; refresh() }
    val interfaces = values["interfaces"].orEmpty().split(' ').filter { it.isNotBlank() }.distinct()
    fun label(id: String) = values["$id.label"].orEmpty().ifBlank { if (id == "none") "无上行" else id }
    fun apply() {
        busy = true
        if (preset == "custom") repository.configureNetworkInterfaces(enabled, uplink, members.toList(), wait.toIntOrNull() ?: 0, ip, ::complete, if (safetyRollback) "on" else "off")
        else repository.configureRouterPreset(preset, enabled, wait.toIntOrNull() ?: 0, ip, ::complete)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("接口路由 · 最多一个 WAN", style = MaterialTheme.typography.titleMedium)
            Text("当前上行：${values["activeWan"].orEmpty().ifBlank { "无" }} · LAN ${values["lanIp"].orEmpty()}")
            for (iface in interfaces.filter { it != "wlan0" && it != "ap" }) Text("${label(iface)} · ${values["$iface.role"].orEmpty()} · ${values["$iface.ip"].orEmpty()}")
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
            Text(routerPresets.firstOrNull { it.id == preset }?.description ?: "WAN 单选 · br-lan 成员多选 · 未选接口不参与路由", style = MaterialTheme.typography.bodySmall)
            if (preset == "wireless") Text("请先在 WiFi 页面连接上游。并发热点跟随 STA 信道；该场景仍为实验功能。")
            Text("上游接 WAN，下游接 LAN。选择与当前接线对应的场景；仅 LAN/离线 NAS 不要接已有路由器 LAN。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
            if (advanced) {
            Row { Switch(enabled, { enabled = it }, enabled = !busy); Text("启用统一路由管理", Modifier.padding(top = 12.dp)) }
            if (preset == "custom") {
            Box {
                OutlinedButton(onClick = { wanMenu = true }, enabled = !busy) { Text("WAN：${label(uplink)} ▾") }
                DropdownMenu(wanMenu, { wanMenu = false }) {
                    (listOf("none") + interfaces.filter { it != "ap" }).forEach { id ->
                        DropdownMenuItem(text = { Text(label(id)) }, onClick = { uplink = id; members = members - id; wanMenu = false })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { lanMenu = true }, enabled = !busy) { Text("LAN（br-lan）：${if (members.isEmpty()) "无成员" else members.joinToString()} ▾") }
                DropdownMenu(lanMenu, { lanMenu = false }) {
                    (interfaces + members).distinct().filter { it != "wlan0" && it != uplink }.forEach { id ->
                        DropdownMenuItem(text = { Text(label(id)) }, leadingIcon = { Checkbox(id in members, null) },
                            onClick = { members = if (id in members) members - id else members + id })
                    }
                    DropdownMenuItem(text = { Text("完成") }, onClick = { lanMenu = false })
                }
            }
            Text("WiFi STA 作为 WAN 前需先连接上游；无线模式仍在 WiFi 页设置。空 LAN 或取消 AP 可能断开手机。", style = MaterialTheme.typography.bodySmall)
            Row { Switch(safetyRollback, { safetyRollback = it }, enabled = !busy); Text("安全回滚（120 秒确认）", Modifier.padding(top = 12.dp)) }
            Text(if (safetyRollback) "高级配置应用后需确认，超时恢复。" else "已关闭：配置直接保留，断连后不会自动恢复。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(wait, { wait = it.filter(Char::isDigit).take(3) }, label = { Text("DHCP 检测时间 3–120 秒") }, singleLine = true)
            OutlinedTextField(ip, { ip = it }, label = { Text("LAN 地址 /24") }, singleLine = true)
            }
            Text("预设按风险判断：AP、LAN 地址和无线模式不变时无需确认。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (preset == "custom" && (!safetyRollback || members.isEmpty() || "ap" !in members)) emptyWarning = true else apply() }, enabled = loaded && !busy && values["pendingUntil"].isNullOrBlank()) { Text("应用配置") }
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
    if (emptyWarning) AlertDialog(onDismissRequest = { emptyWarning = false }, title = { Text("可能断开控制连接") },
        text = { Text(if (!safetyRollback) "安全回滚已关闭，配置将直接保留。若 LAN 地址、接口或热点不可用，需通过其他网口、本机或 ADB 恢复。" else "LAN 无成员或未包含热点 AP。应用后可能需要从 WAN 地址重新连接；120 秒内未确认会回滚。") },
        confirmButton = { TextButton(onClick = { emptyWarning = false; apply() }) { Text("仍然应用") } },
        dismissButton = { TextButton(onClick = { emptyWarning = false }) { Text("取消") } })
}
