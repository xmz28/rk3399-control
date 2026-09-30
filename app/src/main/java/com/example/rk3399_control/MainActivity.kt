package com.example.rk3399_control

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rk3399_control.ui.theme.Rk3399_controlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, RemoteControlService::class.java))
        enableEdgeToEdge()
        setContent { Rk3399_controlTheme(dynamicColor = false) { ControlApp() } }
    }
}

private enum class Page(val title: String, val glyph: String) {
    OVERVIEW("概览", "●"), SERVICES("服务", "▣"), NETWORK("网络", "⌁"), LOGS("日志", "≡"), TERMINAL("终端", ">_")
}

@Composable
private fun ControlApp() {
    val context = LocalContext.current
    val repository = remember { ControlRepository(context.applicationContext) }
    var snapshot by remember { mutableStateOf(DeviceSnapshot()) }
    var page by rememberSaveable { mutableStateOf(Page.OVERVIEW) }
    var busy by remember { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        busy = true
        repository.refresh { snapshot = it; busy = false }
    }
    fun result(message: String, shell: ShellResult) {
        scope.launch { snackbar.showSnackbar(if (shell.code == 0) message else "失败：${shell.output}") }
        refresh()
    }
    LaunchedEffect(Unit) {
        repository.initialize { shell ->
            if (shell.code != 0) scope.launch { snackbar.showSnackbar("Root 初始化失败：${shell.output}") }
            refresh()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Text(item.glyph, fontWeight = FontWeight.Bold) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                Header(snapshot, busy, ::refresh)
                Box(Modifier.fillMaxSize()) {
                    when (page) {
                        Page.OVERVIEW -> OverviewPage(snapshot)
                        Page.SERVICES -> ServicesPage(snapshot.services, busy,
                            onToggle = { service, checked ->
                                busy = true
                                repository.setServiceEnabled(service.id, checked) { result("${service.name} 已${if (checked) "开启" else "停止"}", it) }
                            },
                            onPort = { service, port ->
                                busy = true
                                repository.updatePort(service.id, port) { result("${service.name} 端口已改为 $port", it) }
                            }
                        )
                        Page.NETWORK -> NetworkPage(snapshot, busy,
                            onMode = { ap -> busy = true; repository.setWifiMode(ap) { result("已切换到 ${if (ap) "AP" else "STA"} 模式", it) } },
                            onApply = { ssid, pass, band, channel -> busy = true; repository.updateAp(ssid, pass, band, channel) { result("热点配置已保存", it) } },
                            onEthernet = { request -> busy = true; repository.setEthernet(request) { result("网口模式已应用", it) } },
                            onUsbPreset = { mode -> busy = true; repository.setUsbPreset(mode) { result("USB 启动预设 $mode 已保存，下次重启生效", it) } }
                        )
                        Page.LOGS -> LogsPage(repository)
                        Page.TERMINAL -> TerminalPage(repository)
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 20.dp).size(24.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun Header(snapshot: DeviceSnapshot, busy: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("RK3399 控制台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${snapshot.model}  ·  Android ${snapshot.android}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        StatusDot(snapshot.root)
        Spacer(Modifier.width(8.dp))
        Text(if (snapshot.root) "Root 已连接" else "Root 未连接", color = if (snapshot.root) Color(0xFF18794E) else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = refresh, enabled = !busy) { Text("刷新") }
    }
    HorizontalDivider()
}

@Composable
private fun StatusDot(ok: Boolean) = Box(Modifier.size(10.dp).background(if (ok) Color(0xFF20A464) else Color(0xFFD64545), CircleShape))

@Composable
private fun PageContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 1050.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content
    )
}

@Composable
private fun OverviewPage(s: DeviceSnapshot) = PageContainer {
    s.error?.let { WarningCard(it) }
    SectionTitle("设备与网络")
    InfoCard("启动方式", s.bootMode, "开机脚本类型")
    InfoCard("有线网络", "${s.ethRole.uppercase()}  ·  ${s.ethIp}", "eth0")
    InfoCard("无线网络", "${s.wifiMode}  ·  ${s.ssid}", "${s.wlanIp}  ·  ${bandLabel(s.wifiBand)} CH ${s.channel}")
    SectionTitle("服务概况")
    val running = s.services.count { it.running }
    InfoCard("服务在线", "$running / ${s.services.size}", s.services.filterNot { it.running }.joinToString("、") { it.name }.ifBlank { "全部正常" })
    WarningCard("本 App 直接操作 root 服务。切换 AP/STA 会断开当前无线连接；终端命令不会二次确认，请只执行已确认的命令。")
}

@Composable
private fun ServicesPage(services: List<ServiceInfo>, busy: Boolean, onToggle: (ServiceInfo, Boolean) -> Unit, onPort: (ServiceInfo, Int) -> Unit) {
    var editing by remember { mutableStateOf<ServiceInfo?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp).widthIn(max = 1050.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("服务状态与开机启动") }
        items(services, key = { it.id }) { service -> ServiceCard(service, busy, { onToggle(service, it) }, { editing = service }) }
        item { WarningCard("FTP 与 SFTP 由 Primitive FTPd 同一进程管理，因此共用一个开机开关；两项端口仍可分别修改。") }
    }
    editing?.let { service -> PortDialog(service, { editing = null }) { editing = null; onPort(service, it) } }
}

@Composable
private fun ServiceCard(service: ServiceInfo, busy: Boolean, onToggle: (Boolean) -> Unit, onEditPort: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(service.running); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(service.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("端口 ${service.port}  ·  ${if (service.running) "运行中" else "未运行"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (service.editablePort) TextButton(onClick = onEditPort, enabled = !busy) { Text("改端口") }
            if (service.controllable) {
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = service.enabled, onCheckedChange = onToggle, enabled = !busy)
                    Text("开机启动", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PortDialog(service: ServiceInfo, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var value by remember { mutableStateOf(service.port.toString()) }
    val valid = value.toIntOrNull() in 1..65535
    AlertDialog(onDismissRequest = onDismiss, title = { Text("修改 ${service.name} 端口") },
        text = { OutlinedTextField(value, { value = it.filter(Char::isDigit).take(5) }, label = { Text("端口（1–65535）") }, singleLine = true, isError = !valid) },
        confirmButton = { Button(onClick = { onApply(value.toInt()) }, enabled = valid) { Text("保存并重启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun NetworkPage(
    s: DeviceSnapshot,
    busy: Boolean,
    onMode: (Boolean) -> Unit,
    onApply: (String, String, String, Int) -> Unit,
    onEthernet: (EthernetRequest) -> Unit,
    onUsbPreset: (String) -> Unit
) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var band by rememberSaveable { mutableStateOf("2g") }
    var channel by rememberSaveable { mutableIntStateOf(1) }
    var ethMode by rememberSaveable { mutableStateOf("auto") }
    var autoWait by rememberSaveable { mutableStateOf("6") }
    var staticIp by rememberSaveable { mutableStateOf("192.168.1.2") }
    var prefix by rememberSaveable { mutableStateOf("24") }
    var gateway by rememberSaveable { mutableStateOf("192.168.1.1") }
    var dns1 by rememberSaveable { mutableStateOf("223.5.5.5") }
    var dns2 by rememberSaveable { mutableStateOf("1.1.1.1") }
    var pppoeUser by rememberSaveable { mutableStateOf("") }
    var pppoePassword by rememberSaveable { mutableStateOf("") }
    var pendingUsbPreset by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.apSsid) { if (ssid.isBlank() && s.apSsid != "—") ssid = s.apSsid }
    LaunchedEffect(s.apBand, s.apChannel) {
        band = s.apBand.ifBlank { "2g" }
        s.apChannel.toIntOrNull()?.let { channel = it }
    }
    LaunchedEffect(s.ethMode, s.ethAutoWait) { ethMode = s.ethMode.ifBlank { "auto" }; autoWait = s.ethAutoWait.toString() }
    PageContainer {
        SectionTitle("Wi‑Fi 模式")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onMode(true) }, enabled = !busy && s.wifiMode != "AP") { Text("切换到 AP 热点") }
            OutlinedButton(onClick = { onMode(false) }, enabled = !busy && s.wifiMode != "STA") { Text("切换到 STA 客户端") }
        }
        Text("当前：${s.wifiMode}  ·  ${s.ssid}  ·  ${bandLabel(s.wifiBand)} CH ${s.channel}  ·  ${s.wlanIp}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 4.dp)); SectionTitle("热点配置")
        OutlinedTextField(ssid, { ssid = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("SSID") }, singleLine = true)
        OutlinedTextField(password, { password = it.take(63) }, Modifier.fillMaxWidth(), label = { Text("新密码（8–63 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("频段")
            EthModeButton("2g", "2.4 GHz", band) { band = it; if (channel !in 1..13) channel = 1 }
            EthModeButton("5g", "5 GHz", band) { band = it; if (channel !in channelsForBand(it)) channel = 36 }
        }
        ChannelSelector(band, channel, busy) { channel = it }
        Button(onClick = { onApply(ssid, password, band, channel) }, enabled = !busy && ssid.isNotBlank() && password.length in 8..63 && channel in channelsForBand(band), modifier = Modifier.height(52.dp)) { Text("保存配置并重启热点") }
        WarningCard("实机驱动支持 2.4 GHz 与 5 GHz AP。本 App 固定使用兼容性更好的 20 MHz。52/56/60/64 是仅供屏蔽箱测试的 DFS 实验信道，当前驱动没有雷达检测能力，请勿在开放环境使用。")

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("有线网口模式")
        Text("当前配置：${ethModeLabel(s.ethMode)}  ·  状态 ${s.ethRole}  ·  ${s.ethIp}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("默认路由：${s.ethGateway}  ·  PPPoE ${s.pppoeStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EthModeButton("auto", "自动 WAN/LAN", ethMode, { ethMode = it })
            EthModeButton("lan", "固定 LAN", ethMode, { ethMode = it })
            EthModeButton("wan_dhcp", "WAN DHCP", ethMode, { ethMode = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EthModeButton("wan_static", "WAN 静态 IP", ethMode, { ethMode = it })
            EthModeButton("pppoe", "WAN PPPoE", ethMode, { ethMode = it })
        }
        if (ethMode == "auto") {
            OutlinedTextField(autoWait, { autoWait = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("等待上游 DHCP 的时间（1–120 秒）") }, singleLine = true)
            Text("超时仍未取得上游地址时，自动进入 LAN：192.168.1.1/24。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ethMode == "lan") {
            InfoCard("固定 LAN 参数", "192.168.1.1/24", "DHCP 192.168.1.100–200")
        }
        if (ethMode == "wan_static") {
            OutlinedTextField(staticIp, { staticIp = it.take(15) }, Modifier.fillMaxWidth(), label = { Text("静态 IP") }, singleLine = true)
            OutlinedTextField(prefix, { prefix = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("前缀长度，例如 24") }, singleLine = true)
            OutlinedTextField(gateway, { gateway = it.take(15) }, Modifier.fillMaxWidth(), label = { Text("网关") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(dns1, { dns1 = it.take(15) }, Modifier.weight(1f), label = { Text("DNS 1") }, singleLine = true)
                OutlinedTextField(dns2, { dns2 = it.take(15) }, Modifier.weight(1f), label = { Text("DNS 2（可空）") }, singleLine = true)
            }
        }
        if (ethMode == "pppoe") {
            OutlinedTextField(pppoeUser, { pppoeUser = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("PPPoE 账号") }, singleLine = true)
            OutlinedTextField(pppoePassword, { pppoePassword = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("PPPoE 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            WarningCard("PPPoE 密码保存在本机私有配置中且不会在状态页回显。此模式需要运营商光猫处于桥接模式。")
        }
        val ethernetValid = autoWait.toIntOrNull() in 1..120 && prefix.toIntOrNull() in 1..30 &&
            (ethMode != "pppoe" || (pppoeUser.isNotBlank() && pppoePassword.isNotBlank()))
        Button(onClick = {
            onEthernet(EthernetRequest(ethMode, autoWait.toInt(), staticIp, prefix.toInt(), gateway, dns1, dns2, pppoeUser, pppoePassword))
        }, enabled = !busy && ethernetValid, modifier = Modifier.height(52.dp)) { Text("应用网口配置") }
        WarningCard("切换网口模式会重建 eth0 地址和默认路由，可能立即中断通过网线进行的 ADB、终端或页面连接。")

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("USB 启动预设（下次重启生效）")
        Text("Type-C · fe800000 · 设备树 ${s.usbCDeviceTree} · 当前 ${s.usbCMode}")
        Text("USB3 Type-A · fe900000 · 设备树 ${s.usbADeviceTree} · 当前 ${s.usbAMode}")
        Text("ADB Gadget：${s.usbGadgetUdc} · ${s.usbGadgetState}；Type-A 已接外设 ${s.usbAAttached} 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("当前：${s.usbPresetActive} · 下次启动：${s.usbPresetNext}", color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pendingUsbPreset = "A" }, enabled = !busy && s.usbPresetNext != "A") { Text("A · C Host / A OTG") }
            OutlinedButton(onClick = { pendingUsbPreset = "B" }, enabled = !busy && s.usbPresetNext != "B") { Text("B · C OTG / A Host") }
        }
        WarningCard("选择预设只写入已校验的启动镜像，不会立即切换或自动重启。重启时 USB ADB 所在端口会改变；请确认能通过有线网络或 MaskRom 恢复。无须拔掉当前 USB 数据线。")
    }
    pendingUsbPreset?.let { mode ->
        AlertDialog(onDismissRequest = { pendingUsbPreset = null }, title = { Text("写入 USB 启动预设 $mode？") },
            text = { Text("将校验并写入 resource 与 boot 启动分区。当前连接不会改变；下次重启后切换生效。失败时会尝试回滚，仍建议保留完整固件。") },
            confirmButton = { Button(onClick = { pendingUsbPreset = null; onUsbPreset(mode) }) { Text("确认写入") } },
            dismissButton = { TextButton(onClick = { pendingUsbPreset = null }) { Text("取消") } })
    }
}

@Composable
private fun EthModeButton(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    if (id == selected) Button(onClick = { onSelect(id) }) { Text(label) }
    else OutlinedButton(onClick = { onSelect(id) }) { Text(label) }
}

@Composable
private fun ChannelSelector(band: String, channel: Int, busy: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
            Text("信道 $channel")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            channelsForBand(band).forEach { value ->
                DropdownMenuItem(text = { Text("信道 $value") }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

private fun channelsForBand(band: String): List<Int> =
    if (band == "5g") listOf(36, 40, 44, 48, 52, 56, 60, 64, 149, 153, 157, 161, 165) else (1..13).toList()

private fun bandLabel(band: String): String = if (band == "5g") "5 GHz" else "2.4 GHz"

private fun ethModeLabel(mode: String): String = when (mode) {
    "lan" -> "固定 LAN"
    "wan_dhcp" -> "WAN DHCP"
    "wan_static" -> "WAN 静态 IP"
    "pppoe" -> "WAN PPPoE"
    else -> "自动 WAN/LAN"
}

@Composable
private fun LogsPage(repository: ControlRepository) {
    var selected by rememberSaveable { mutableStateOf("system") }
    var output by remember { mutableStateOf("请选择来源并刷新。") }
    var loading by remember { mutableStateOf(false) }
    PageContainer {
        SectionTitle("日志查看")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "系统", "nas" to "NAS", "wifi" to "Wi‑Fi", "controller" to "控制器").forEach { (id, name) ->
                if (selected == id) Button(onClick = { selected = id }) { Text(name) } else OutlinedButton(onClick = { selected = id }) { Text(name) }
            }
        }
        Button(onClick = { loading = true; repository.readLog(selected) { output = it.output.ifBlank { "暂无日志" }; loading = false } }, enabled = !loading) { Text(if (loading) "读取中…" else "刷新日志") }
        Console(output)
    }
}

@Composable
private fun TerminalPage(repository: ControlRepository) {
    var command by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("root 终端已就绪。示例：ip addr\n") }
    var running by remember { mutableStateOf(false) }
    PageContainer {
        SectionTitle("Root 终端")
        WarningCard("命令以 root 身份执行，超时上限 60 秒。不要粘贴来源不明的命令，也不要执行递归删除或修改 system 分区的命令。")
        OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("输入 shell 命令") }, placeholder = { Text("例如：df -h /data") }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val submitted = command; output += "\n# $submitted\n"; running = true
                repository.runTerminal(submitted) { output += it.output.ifBlank { "(退出码 ${it.code})" } + "\n"; running = false }
            }, enabled = command.isNotBlank() && !running) { Text(if (running) "执行中…" else "执行") }
            OutlinedButton(onClick = { output = "" }, enabled = !running) { Text("清空显示") }
        }
        Console(output)
    }
}

@Composable
private fun Console(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101820)), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFFD5E6EA), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun InfoCard(title: String, value: String, hint: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
        Text(text, Modifier.fillMaxWidth().padding(15.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
