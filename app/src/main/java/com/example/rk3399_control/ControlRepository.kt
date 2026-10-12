package com.example.rk3399_control

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

data class ServiceInfo(
    val id: String,
    val name: String,
    val port: Int,
    val running: Boolean,
    val enabled: Boolean,
    val editablePort: Boolean = false,
    val controllable: Boolean = true
)

data class DeviceSnapshot(
    val root: Boolean = false,
    val model: String = "—",
    val android: String = "—",
    val bootMode: String = "—",
    val ethRole: String = "—",
    val ethIp: String = "—",
    val ethMode: String = "auto",
    val ethAutoWait: Int = 6,
    val ethGateway: String = "—",
    val pppoeStatus: String = "—",
    val wlanIp: String = "—",
    val wifiMode: String = "—",
    val wifiDhcpRunning: Boolean = false,
    val ssid: String = "—",
    val wifiBand: String = "2g",
    val channel: String = "—",
    val apSsid: String = "—",
    val apBand: String = "2g",
    val apChannel: String = "1",
    val usbCDeviceTree: String = "—",
    val usbCMode: String = "—",
    val usbADeviceTree: String = "—",
    val usbAMode: String = "—",
    val usbASwitchable: Boolean = false,
    val usbAAttached: Int = 0,
    val usbGadgetState: String = "—",
    val usbGadgetUdc: String = "—",
    val usbPresetActive: String = "custom",
    val usbPresetNext: String = "custom",
    val services: List<ServiceInfo> = emptyList(),
    val error: String? = null
)

data class EthernetRequest(
    val mode: String,
    val autoWaitSeconds: Int = 6,
    val ip: String = "",
    val prefix: Int = 24,
    val gateway: String = "",
    val dns1: String = "",
    val dns2: String = "",
    val pppoeUser: String = "",
    val pppoePassword: String = ""
)

class ControlRepository(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun initialize(callback: (ShellResult) -> Unit = {}) {
        executor.execute {
            val local = File(context.filesDir, "rk3399_controller.sh")
            val storage = File(context.filesDir, "nas-storage.py")
            context.assets.open("nas-storage.py").use { input -> storage.outputStream().use { input.copyTo(it) } }
            context.assets.open("rk3399_controller.sh").use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
            val mkdir = "mkdir -p $REMOTE/enabled $REMOTE/config $REMOTE/logs"
            val install = "$mkdir; cp ${RootShell.quote(local.absolutePath)} $REMOTE/controller.sh; chmod 0755 $REMOTE/controller.sh; " +
                "cp ${RootShell.quote(storage.absolutePath)} /data/local/alpine-nas/opt/nas-storage.py; chmod 0600 /data/local/alpine-nas/opt/nas-storage.py"
            var result = RootShell.run(install)
            if (result.code == 0) {
                result = RootShell.run(syncCommand() + "; $REMOTE/controller.sh ethernet-bootstrap >$REMOTE/logs/ethernet-apply.log 2>&1; " +
                    "$REMOTE/controller.sh once >/dev/null 2>&1; " +
                    "if [ -s $REMOTE/controller.pid ]; then kill \$(cat $REMOTE/controller.pid) 2>/dev/null || true; fi; " +
                    "nohup $REMOTE/controller.sh daemon >$REMOTE/logs/controller.log 2>&1 & " +
                    "if [ -s $REMOTE/ethernet.pid ]; then kill \$(cat $REMOTE/ethernet.pid) 2>/dev/null || true; fi; " +
                    "nohup $REMOTE/controller.sh ethernet-daemon >$REMOTE/logs/ethernet.log 2>&1 &")
            }
            main.post { callback(result) }
        }
    }

    fun refresh(callback: (DeviceSnapshot) -> Unit) {
        executor.execute {
            val result = RootShell.run("$REMOTE/controller.sh status", 15)
            val snapshot = if (result.code == 0) parseSnapshot(result.output)
            else DeviceSnapshot(error = "Root/控制器不可用：${result.output.ifBlank { "退出码 ${result.code}" }}")
            main.post { callback(snapshot) }
        }
    }

    fun setServiceEnabled(id: String, enabled: Boolean, callback: (ShellResult) -> Unit) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
        executor.execute {
            val value = if (enabled) "1" else "0"
            val result = RootShell.run("printf '$value' > $REMOTE/enabled/${safeId(id)}; $REMOTE/controller.sh once")
            main.post { callback(result) }
        }
    }

    fun updatePort(id: String, port: Int, callback: (ShellResult) -> Unit) {
        if (port !in 1..65535) {
            callback(ShellResult(2, "端口范围必须是 1..65535"))
            return
        }
        prefs.edit().putInt("port_$id", port).apply()
        executor.execute {
            val result = RootShell.run("printf '$port' > $REMOTE/config/${safeId(id)}_port; $REMOTE/controller.sh apply-port ${safeId(id)} $port; $REMOTE/controller.sh once", 25)
            main.post { callback(result) }
        }
    }

    fun setWifiMode(ap: Boolean, callback: (ShellResult) -> Unit) {
        executor.execute {
            val value = if (ap) "1" else "0"
            val result = RootShell.run("printf '$value' > $REMOTE/config/wifi_ap; $REMOTE/controller.sh wifi ${if (ap) "ap" else "sta"}", 30)
            if (result.code == 0) prefs.edit().putBoolean("wifi_ap", ap).apply()
            main.post { callback(result) }
        }
    }

    fun scanWifi(callback: (ShellResult) -> Unit) = runAsync(
        "[ \"\$(cat $REMOTE/config/wifi_ap)\" = 0 ] || { echo '请先切换到 STA 模式'; exit 2; }; " +
            "wpa_cli -i wlan0 scan; sleep 4; wpa_cli -i wlan0 scan_results", 15, callback)

    fun connectWifi(ssid: String, password: String, callback: (ShellResult) -> Unit) {
        if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32 ||
            password.length !in 8..63 || password.any { it == '\n' || it == '\r' }) {
            callback(ShellResult(2, "SSID 或 WPA 密码无效（密码需 8–63 位）")); return
        }
        executor.execute {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val result = try {
                if (!manager.isWifiEnabled) manager.isWifiEnabled = true
                val config = WifiConfiguration().apply {
                    SSID = "\"${ssid.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    preSharedKey = "\"${password.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                }
                val id = manager.addNetwork(config)
                if (id < 0 || !manager.enableNetwork(id, true) || !manager.reconnect())
                    ShellResult(2, "Wi‑Fi 连接请求未被系统接受")
                else ShellResult(0, "已请求连接 $ssid，请稍后刷新状态确认 IP")
            } catch (e: Exception) { ShellResult(2, e.message ?: "连接失败") }
            main.post { callback(result) }
        }
    }

    fun updateNasPassword(password: String, callback: (ShellResult) -> Unit) {
        if (!password.matches(Regex("[A-Za-z0-9._@#-]{4,64}"))) {
            callback(ShellResult(2, "共用密码需 4–64 位英文字母、数字或 ._@#-")); return
        }
        executor.execute {
            val hash = Base64.encodeToString(
                MessageDigest.getInstance("SHA-512").digest((password + "H§R&q}9").toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            val root = "/data/local/alpine-nas"
            val ftp = "/data/data/org.primftpd/shared_prefs/org.primftpd_preferences.xml"
            val backup = "$REMOTE/config/nas-password-backup"
            val quoted = RootShell.quote(password)
            val command = "set -e; mkdir -p $backup; chmod 0700 $backup; " +
                "[ -f $root/var/lib/samba/private/passdb.tdb ] && [ -f $root/etc/copyparty.conf ] && [ -f $ftp ]; " +
                "cp $root/var/lib/samba/private/passdb.tdb $backup/passdb.tdb; " +
                "cp $root/etc/copyparty.conf $backup/copyparty.conf; cp $ftp $backup/ftp.xml; " +
                "printf '%s\\n%s\\n' $quoted $quoted | chroot $root /usr/bin/smbpasswd -s admin; " +
                "sed -i 's/^[[:space:]]*admin:[[:space:]].*/  admin: $password/' $root/etc/copyparty.conf; " +
                "am force-stop org.primftpd; " +
                "sed 's|<string name=\"passwordPref\">[^<]*</string>|<string name=\"passwordPref\">$hash</string>|' $ftp > $backup/ftp-new.xml; " +
                "cat $backup/ftp-new.xml > $ftp; " +
                "killall copyparty-sfx.py 2>/dev/null || true; $REMOTE/controller.sh once; " +
                "echo 'Samba、HTTP、FTP、SFTP 已更新为同一个 admin 密码'"
            val result = RootShell.run(command, 40)
            if (result.code != 0) {
                RootShell.run("[ ! -f $backup/passdb.tdb ] || cat $backup/passdb.tdb > $root/var/lib/samba/private/passdb.tdb; " +
                    "[ ! -f $backup/copyparty.conf ] || cp $backup/copyparty.conf $root/etc/copyparty.conf; " +
                    "[ ! -f $backup/ftp.xml ] || cat $backup/ftp.xml > $ftp; $REMOTE/controller.sh once", 20)
            }
            RootShell.run("rm -f $backup/passdb.tdb $backup/copyparty.conf $backup/ftp.xml $backup/ftp-new.xml; rmdir $backup", 5)
            main.post { callback(result) }
        }
    }

    fun updateAp(ssid: String, password: String, band: String, channel: Int, callback: (ShellResult) -> Unit) {
        if (ssid.isBlank() || ssid.toByteArray().size > 32 || '\n' in ssid || '\r' in ssid) {
            callback(ShellResult(2, "SSID 不能为空且最多 32 字节"))
            return
        }
        if (password.length !in 8..63 || '\n' in password || '\r' in password) {
            callback(ShellResult(2, "热点密码长度必须是 8..63 位"))
            return
        }
        val validChannels = if (band == "2g") (1..13).toSet() else setOf(36, 40, 44, 48, 52, 56, 60, 64, 149, 153, 157, 161, 165)
        if (band !in setOf("2g", "5g") || channel !in validChannels) {
            callback(ShellResult(2, "频段或信道无效"))
            return
        }
        executor.execute {
            val command = "$REMOTE/controller.sh ap-config ${RootShell.quote(ssid)} ${RootShell.quote(password)} $band $channel"
            val result = RootShell.run(command, 35)
            if (result.code == 0) prefs.edit().putString("ap_ssid", ssid).putString("ap_password", password)
                .putString("ap_band", band).putInt("ap_channel", channel).apply()
            main.post { callback(result) }
        }
    }

    fun setEthernet(request: EthernetRequest, callback: (ShellResult) -> Unit) {
        val modes = setOf("auto", "lan", "wan_dhcp", "wan_static", "pppoe")
        if (request.mode !in modes || request.autoWaitSeconds !in 1..120) {
            callback(ShellResult(2, "网口模式或检测时间无效")); return
        }
        if (request.mode == "wan_static") {
            if (!isIpv4(request.ip) || request.prefix !in 1..30 || !isIpv4(request.gateway) ||
                (request.dns1.isNotBlank() && !isIpv4(request.dns1)) ||
                (request.dns2.isNotBlank() && !isIpv4(request.dns2))) {
                callback(ShellResult(2, "静态 IP、前缀、网关或 DNS 格式无效")); return
            }
        }
        if (request.mode == "pppoe") {
            val safeCredential = Regex("[A-Za-z0-9@._+!#%:=/-]+")
            if (!safeCredential.matches(request.pppoeUser) || !safeCredential.matches(request.pppoePassword)) {
                callback(ShellResult(2, "PPPoE 账号或密码为空，或包含当前脚本不支持的字符")); return
            }
        }
        prefs.edit()
            .putString("eth_mode", request.mode)
            .putInt("eth_auto_wait", request.autoWaitSeconds)
            .putString("eth_ip", request.ip)
            .putInt("eth_prefix", request.prefix)
            .putString("eth_gateway", request.gateway)
            .putString("eth_dns1", request.dns1)
            .putString("eth_dns2", request.dns2)
            .putString("pppoe_user", request.pppoeUser)
            .putString("pppoe_password", request.pppoePassword)
            .apply()
        executor.execute {
            val writes = ethernetSyncCommands(request)
            val result = RootShell.run("$writes; chmod 0600 $REMOTE/config/pppoe_user $REMOTE/config/pppoe_password; $REMOTE/controller.sh ethernet-apply", 50)
            main.post { callback(result) }
        }
    }

    fun readLog(type: String, callback: (ShellResult) -> Unit) = runAsync("$REMOTE/controller.sh log ${safeId(type)}", 20, callback)

    fun setUsbMode(port: String, mode: String, callback: (ShellResult) -> Unit) {
        if (port != "type_a" || mode !in setOf("host", "peripheral", "otg")) {
            callback(ShellResult(2, "此固件只支持 USB3 Type-A 的 Host / Device / OTG 切换"))
            return
        }
        runAsync("$REMOTE/controller.sh usb-set type_a $mode", 20, callback)
    }

    fun setUsbPreset(mode: String, callback: (ShellResult) -> Unit) {
        if (mode !in setOf("A", "B")) {
            callback(ShellResult(2, "USB 启动预设只能是 A 或 B")); return
        }
        runAsync("$REMOTE/controller.sh usb-preset $mode", 50, callback)
    }

    fun runTerminal(command: String, callback: (ShellResult) -> Unit) = runAsync(command, 60, callback)

    private fun runAsync(command: String, timeout: Long, callback: (ShellResult) -> Unit) {
        executor.execute {
            val result = RootShell.run(command, timeout)
            main.post { callback(result) }
        }
    }

    private fun syncCommand(): String {
        val defaults = listOf("samba", "http", "ftp", "speed", "iperf", "mdns")
        val commands = defaults.map { id ->
            val enabled = prefs.getBoolean("enabled_$id", true)
            "printf '${if (enabled) 1 else 0}' > $REMOTE/enabled/$id"
        }.toMutableList()
        commands += "printf '${if (prefs.getBoolean("wifi_ap", true)) 1 else 0}' > $REMOTE/config/wifi_ap"
        listOf("http" to 3923, "ftp" to 12345, "sftp" to 1234, "iperf" to 5201).forEach { (id, default) ->
            commands += "printf '${prefs.getInt("port_$id", default)}' > $REMOTE/config/${id}_port"
        }
        commands += ethernetSyncCommands(
            EthernetRequest(
                mode = prefs.getString("eth_mode", "auto") ?: "auto",
                autoWaitSeconds = prefs.getInt("eth_auto_wait", 6),
                ip = prefs.getString("eth_ip", "") ?: "",
                prefix = prefs.getInt("eth_prefix", 24),
                gateway = prefs.getString("eth_gateway", "") ?: "",
                dns1 = prefs.getString("eth_dns1", "") ?: "",
                dns2 = prefs.getString("eth_dns2", "") ?: "",
                pppoeUser = prefs.getString("pppoe_user", "") ?: "",
                pppoePassword = prefs.getString("pppoe_password", "") ?: ""
            )
        )
        return commands.joinToString("; ")
    }

    private fun ethernetSyncCommands(r: EthernetRequest): String {
        fun write(name: String, value: String) = "printf %s ${RootShell.quote(value)} > $REMOTE/config/$name"
        return listOf(
            write("app_apk", context.applicationInfo.sourceDir),
            write("eth_mode", r.mode), write("eth_auto_wait", r.autoWaitSeconds.toString()),
            write("eth_ip", r.ip), write("eth_prefix", r.prefix.toString()),
            write("eth_gateway", r.gateway), write("eth_dns1", r.dns1), write("eth_dns2", r.dns2),
            write("pppoe_user", r.pppoeUser), write("pppoe_password", r.pppoePassword)
        ).joinToString("; ")
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }
    }

    private fun parseSnapshot(text: String): DeviceSnapshot {
        val values = mutableMapOf<String, String>()
        val services = mutableListOf<ServiceInfo>()
        text.lineSequence().forEach { line ->
            if (line.startsWith("SERVICE|")) {
                val p = line.split('|')
                if (p.size >= 7) services += ServiceInfo(
                    p[1], p[2], p[3].toIntOrNull() ?: 0, p[4] == "1", p[5] == "1", p[6] == "1", p.getOrNull(7) != "0"
                )
            } else {
                val i = line.indexOf('=')
                if (i > 0) values[line.substring(0, i)] = line.substring(i + 1).ifBlank { "—" }
            }
        }
        return DeviceSnapshot(
            root = values["ROOT"] == "0",
            model = values["MODEL"] ?: "—",
            android = values["ANDROID"] ?: "—",
            bootMode = values["BOOT_MODE"] ?: "—",
            ethRole = values["ETH_ROLE"] ?: "—",
            ethIp = values["ETH_IP"] ?: "—",
            ethMode = values["ETH_MODE"] ?: "auto",
            ethAutoWait = values["ETH_AUTO_WAIT"]?.toIntOrNull() ?: 6,
            ethGateway = values["ETH_GATEWAY"] ?: "—",
            pppoeStatus = values["PPPOE_STATUS"] ?: "—",
            wlanIp = values["WLAN_IP"] ?: "—",
            wifiMode = values["WIFI_MODE"] ?: "—",
            wifiDhcpRunning = values["WIFI_DHCP_RUNNING"] == "1",
            ssid = values["SSID"] ?: "—",
            wifiBand = values["BAND"] ?: "2g",
            channel = values["CHANNEL"] ?: "—",
            apSsid = values["AP_SSID"] ?: "—",
            apBand = values["AP_BAND"] ?: "2g",
            apChannel = values["AP_CHANNEL"] ?: "1",
            usbCDeviceTree = values["USB_C_DT"] ?: "—",
            usbCMode = values["USB_C_MODE"] ?: "—",
            usbADeviceTree = values["USB_A_DT"] ?: "—",
            usbAMode = values["USB_A_MODE"] ?: "—",
            usbASwitchable = values["USB_A_SWITCHABLE"] == "1",
            usbAAttached = values["USB_A_ATTACHED"]?.toIntOrNull() ?: 0,
            usbGadgetState = values["USB_GADGET_STATE"] ?: "—",
            usbGadgetUdc = values["USB_GADGET_UDC"] ?: "—",
            usbPresetActive = values["USB_PRESET_ACTIVE"] ?: "custom",
            usbPresetNext = values["USB_PRESET_NEXT"] ?: "custom",
            services = services
        )
    }

    companion object {
        const val PREFS = "rk3399_control"
        const val REMOTE = "/data/local/rk3399-control"
        private fun safeId(id: String) = id.filter { it.isLetterOrDigit() || it == '_' }.take(32)
    }
}
