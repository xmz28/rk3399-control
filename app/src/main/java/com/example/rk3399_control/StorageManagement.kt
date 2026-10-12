package com.example.rk3399_control

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
fun StorageManagement(load: suspend () -> JSONObject, perform: suspend (JSONObject) -> JSONObject) {
    var response by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在读取 USB 存储…") }
    var pending by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }
    var confirmation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        try {
            val result = load()
            if (!result.optBoolean("ok")) error(result.optString("error", "读取失败"))
            response = result
            if (message == "正在读取 USB 存储…") message = "外部 USB 存储"
        } catch (e: Exception) { message = e.message ?: "读取失败" }
    }
    LaunchedEffect(load) { refresh() }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (!busy && pending == null) refresh() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("外部 USB 存储", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(message)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("刷新磁盘") }
        val disks = response?.optJSONArray("disks")
        if (disks == null || disks.length() == 0) Text("未发现外部 USB 存储")
        if (disks != null) for (i in 0 until disks.length()) {
            val disk = disks.getJSONObject(i)
            val state = disk.optString("state")
            val connected = state != "disconnected"
            val mounted = disk.optBoolean("mounted")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(disk.optString("name"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(when { !connected -> "已断开"; mounted -> if (disk.optBoolean("readonly")) "已挂载 · 只读" else "已挂载 · 读写"; state == "unmountable" -> "无法挂载"; else -> "已连接 · 未挂载" })
                    if (connected) Text(listOf(disk.optString("vendor"), disk.optString("model"), disk.optString("filesystem")).filter { it.isNotBlank() }.joinToString(" · "))
                    Text("UUID：${disk.optString("uuid")}")
                    disk.optString("label").takeIf { it.isNotBlank() }?.let { Text("卷标：$it") }
                    if (connected) Text("容量：${storageSize(disk.optLong("totalBytes", disk.optLong("sizeBytes")))}")
                    if (mounted) {
                        val total = disk.optLong("totalBytes")
                        val free = disk.optLong("availableBytes")
                        Text("已用 ${storageSize(total - disk.optLong("freeBytes"))} · 可用 ${storageSize(free)}")
                        if (total > 0) LinearProgressIndicator(progress = { ((total - free).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (connected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pending = disk to if (mounted) "eject" else "mount"; confirmation = "" }, enabled = !busy) { Text(if (mounted) "安全弹出" else "挂载") }
                        OutlinedButton(onClick = { pending = disk to "format"; confirmation = "" }, enabled = !busy && disk.optBoolean("canFormat")) { Text("格式化") }
                    }
                }
            }
        }
        Text("仅管理外部 USB 卷；格式化采用系统 FAT 格式，清空该卷全部文件并保留 USB 编号。")
    }
    pending?.let { (disk, action) ->
        val formatting = action == "format"
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text(if (formatting) "格式化 ${disk.optString("name")}" else if (action == "eject") "安全弹出" else "挂载磁盘") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${disk.optString("name")} · ${storageSize(disk.optLong("sizeBytes"))}\nUUID：${disk.optString("uuid")}")
                Text(if (formatting) "此操作会清空该卷全部数据。格式：系统 FAT。输入盘名确认。" else if (action == "eject") "停止该盘传输后继续；成功提示后再拔盘。" else "重新挂载此 USB 卷。")
                if (formatting) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("输入 ${disk.optString("name")}") }, singleLine = true)
            } },
            confirmButton = { TextButton(enabled = !busy && (!formatting || confirmation == disk.optString("name")), onClick = {
                val body = JSONObject().put("action", action).put("uuid", disk.optString("uuid")).put("identity", disk.optString("identity"))
                    .put("confirm", confirmation).put("filesystem", "vfat")
                pending = null
                scope.launch {
                    busy = true
                    try {
                        val result = perform(body)
                        if (!result.optBoolean("ok")) error(result.optString("error", "操作失败"))
                        response = result
                        message = result.optString("message", "操作完成")
                    } catch (e: Exception) { message = e.message ?: "操作失败" }
                    finally { busy = false; refresh() }
                }
            }) { Text(if (formatting) "清空并格式化" else "确认") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
}

private fun storageSize(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
