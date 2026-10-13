package com.example.rk3399_control

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Small, password-protected LAN API. No remote shell or arbitrary file access. */
class RemoteControlService : Service() {
    private val workers = Executors.newFixedThreadPool(4)
    private var listener: ServerSocket? = null
    private val repository by lazy { ControlRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        Thread({ listen() }, "rk3399-remote-listener").start()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        listener?.close()
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun listen() {
        try {
            ServerSocket(PORT).use { server ->
                listener = server
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.execute { socket.use { serve(it) } }
                }
            }
        } catch (e: SocketException) {
            if (listener?.isClosed != true) Log.e(TAG, "Listener stopped", e)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start remote control", e)
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 270000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1" || parts[0] !in setOf("GET", "POST")) {
                respond(socket, 400, error("Invalid request")); return
            }
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                if (++headerCount > 32) throw IllegalArgumentException("Too many headers")
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            if (!socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLoopbackAddress && !socket.inetAddress.isLinkLocalAddress) {
                respond(socket, 403, error("LAN only")); return
            }
            if (!authorized(headers["authorization"])) {
                respond(socket, 401, error("Password required"), true); return
            }
            if (parts[0] == "POST" && parts[1].substringBefore('?') == "/api/v1/storage") {
                require(headers["x-storage-request"] == "1") { "Missing storage request header" }
                headers["origin"]?.let { origin ->
                    require(java.net.URI(origin).authority == headers["host"]) { "Cross-origin storage operation rejected" }
                }
            }
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            if (size !in 0..4096 || (parts[0] == "POST" && size == 0)) {
                respond(socket, 400, error("Invalid body size")); return
            }
            val body = if (size > 0) ByteArray(size).also { bytes ->
                var offset = 0
                while (offset < size) {
                    val count = input.read(bytes, offset, size - offset)
                    if (count < 0) throw IllegalArgumentException("Incomplete body")
                    offset += count
                }
            } else ByteArray(0)
            val json = if (size > 0) JSONObject(String(body, StandardCharsets.UTF_8)) else JSONObject()
            val path = parts[1].substringBefore('?')
            val response = route(parts[0], path, json, parts[1])
            respond(socket, 200, response)
        } catch (e: IllegalArgumentException) {
            runCatching { respond(socket, 400, error(e.message ?: "Invalid request")) }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
            runCatching { respond(socket, 500, error(e.message ?: "Request failed")) }
        }
    }

    private fun route(method: String, path: String, body: JSONObject, fullPath: String): JSONObject {
        if (method == "GET" && path == "/api/v1/network") {
            val shell = await { repository.networkStatus(it) }
            return result(shell).apply {
                shell.output.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) put(line.substring(0, i), line.substring(i + 1))
                }
            }
        }
        if (path == "/api/v1/storage") return UsbStorageBackend.request(if (method == "POST") body else null)
        if (method == "GET" && path == "/api/v1/status") {
            val snapshot = awaitSnapshot()
            if (snapshot.error != null) return error(snapshot.error)
            return JSONObject().put("ok", true).put("model", snapshot.model).put("android", snapshot.android)
                .put("routerEnabled", snapshot.routerEnabled).put("routerWan", snapshot.routerWan).put("lanIp", snapshot.lanIp)
                .put("ethRole", snapshot.ethRole).put("ethIp", snapshot.ethIp).put("ethMode", snapshot.ethMode)
                .put("ethAutoWait", snapshot.ethAutoWait).put("ethGateway", snapshot.ethGateway)
                .put("pppoeStatus", snapshot.pppoeStatus).put("wlanIp", snapshot.wlanIp)
                .put("wifiMode", snapshot.wifiMode).put("wifiDhcpRunning", snapshot.wifiDhcpRunning).put("ssid", snapshot.ssid)
                .put("wifiBand", snapshot.wifiBand).put("channel", snapshot.channel)
                .put("apSsid", snapshot.apSsid).put("apBand", snapshot.apBand).put("apChannel", snapshot.apChannel)
                .put("usbCDeviceTree", snapshot.usbCDeviceTree).put("usbCMode", snapshot.usbCMode)
                .put("usbADeviceTree", snapshot.usbADeviceTree).put("usbAMode", snapshot.usbAMode)
                .put("usbASwitchable", snapshot.usbASwitchable).put("usbAAttached", snapshot.usbAAttached)
                .put("usbGadgetState", snapshot.usbGadgetState).put("usbGadgetUdc", snapshot.usbGadgetUdc)
                .put("usbPresetActive", snapshot.usbPresetActive).put("usbPresetNext", snapshot.usbPresetNext)
                .put("services", JSONArray().apply {
                    snapshot.services.forEach { service -> addService(service) }
                })
        }
        if (method == "GET" && path == "/api/v1/log") {
            val type = fullPath.substringAfter("type=", "system").substringBefore('&')
            require(type in setOf("system", "nas", "wifi", "controller")) { "Invalid log type" }
            return result(await { repository.readLog(type, it) })
        }
        if (method == "GET" && path == "/api/v1/wifi/scan") {
            val scan = await { repository.scanWifi(it) }
            if (scan.code != 0) return result(scan)
            val networks = JSONArray()
            val seen = mutableSetOf<String>()
            scan.output.lineSequence().drop(2).forEach { line ->
                val fields = line.split('\t', limit = 5)
                if (fields.size == 5 && fields[4].isNotBlank() && seen.add(fields[4])) {
                    networks.put(JSONObject().put("ssid", fields[4]).put("frequency", fields[1].toIntOrNull() ?: 0)
                        .put("signal", fields[2].toIntOrNull() ?: -100).put("security", fields[3]))
                }
            }
            return JSONObject().put("ok", true).put("networks", networks)
        }
        if (method != "POST") throw IllegalArgumentException("Unknown endpoint")
        return when (path) {
            "/api/v1/network" -> result(await {
                repository.configureRouter(body.getBoolean("enabled"), body.getString("uplink"),
                    body.optInt("wait", 20), body.optString("lanIp", "192.168.31.1"),
                    body.optString("eth0Mode", "auto"), body.optString("eth1Mode", "auto"), it)
            })
            "/api/v1/network/preset" -> result(await {
                repository.configureRouterPreset(body.getString("preset"), body.optBoolean("enabled", true),
                    body.optInt("wait", 20), body.optString("lanIp", "192.168.31.1"), it)
            })
            "/api/v1/network/confirm" -> result(await { repository.confirmRouter(body.getString("token"), it) })
            "/api/v1/network/rollback" -> result(await { repository.rollbackRouter(it) })
            "/api/v1/service" -> {
                val id = body.getString("id")
                require(id in setOf("samba", "http", "ftp", "speed", "iperf", "mdns")) { "Invalid service" }
                result(await { repository.setServiceEnabled(id, body.getBoolean("enabled"), it) })
            }
            "/api/v1/port" -> {
                val id = body.getString("id")
                require(id in setOf("http", "ftp", "sftp", "iperf")) { "Invalid port service" }
                result(await { repository.updatePort(id, body.getInt("port"), it) })
            }
            "/api/v1/wifi" -> {
                val mode = body.getString("mode")
                require(mode in setOf("ap", "sta", "apsta")) { "Invalid Wi-Fi mode" }
                result(await { if (mode == "apsta") repository.setConcurrentWifi(it) else repository.setWifiMode(mode == "ap", it) })
            }
            "/api/v1/wifi/connect" -> result(await {
                repository.connectWifi(body.getString("ssid"), body.getString("password"), it)
            })
            "/api/v1/nas-password" -> result(await { repository.updateNasPassword(body.getString("password"), it) })
            "/api/v1/ap" -> result(await {
                repository.updateAp(body.getString("ssid"), body.getString("password"),
                    body.getString("band"), body.getInt("channel"), it)
            })
            "/api/v1/ethernet" -> result(await {
                repository.setEthernet(EthernetRequest(
                    mode = body.getString("mode"), autoWaitSeconds = body.optInt("autoWaitSeconds", 6),
                    ip = body.optString("ip"), prefix = body.optInt("prefix", 24),
                    gateway = body.optString("gateway"), dns1 = body.optString("dns1"), dns2 = body.optString("dns2"),
                    pppoeUser = body.optString("pppoeUser"), pppoePassword = body.optString("pppoePassword")
                ), it)
            })
            "/api/v1/usb" -> {
                val port = body.getString("port")
                val mode = body.getString("mode")
                require(port == "type_a" && mode in setOf("host", "peripheral", "otg")) { "Unsupported USB port or mode" }
                result(await { repository.setUsbMode(port, mode, it) })
            }
            "/api/v1/usb-preset" -> {
                val mode = body.getString("mode")
                require(mode in setOf("A", "B") && body.optString("confirm") == "APPLY_USB_PRESET") {
                    "Invalid USB preset confirmation"
                }
                result(await { repository.setUsbPreset(mode, it) })
            }
            "/api/v1/password" -> {
                val password = body.getString("password")
                require(password.length in 4..64 && password.none { it == '\n' || it == '\r' }) { "Password must be 4–64 characters" }
                getSharedPreferences(ControlRepository.PREFS, MODE_PRIVATE).edit().putString(PASSWORD_KEY, password).commit()
                JSONObject().put("ok", true)
            }
            else -> throw IllegalArgumentException("Unknown endpoint")
        }
    }

    private fun JSONArray.addService(s: ServiceInfo) {
        put(JSONObject().put("id", s.id).put("name", s.name).put("port", s.port)
            .put("running", s.running).put("enabled", s.enabled)
            .put("editablePort", s.editablePort).put("controllable", s.controllable))
    }

    private fun awaitSnapshot(): DeviceSnapshot {
        val latch = CountDownLatch(1)
        val value = AtomicReference<DeviceSnapshot>()
        repository.refresh { value.set(it); latch.countDown() }
        if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("Status timeout")
        return value.get()
    }

    private fun await(operation: ((ShellResult) -> Unit) -> Unit): ShellResult {
        val latch = CountDownLatch(1)
        val value = AtomicReference<ShellResult>()
        operation { value.set(it); latch.countDown() }
        if (!latch.await(55, TimeUnit.SECONDS)) throw IllegalStateException("Operation timeout")
        return value.get()
    }

    private fun authorized(header: String?): Boolean {
        if (header?.startsWith("Basic ") != true) return false
        val decoded = runCatching { String(Base64.decode(header.substring(6), Base64.DEFAULT), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val expected = "admin:" + getSharedPreferences(ControlRepository.PREFS, MODE_PRIVATE).getString(PASSWORD_KEY, "admin")
        return MessageDigest.isEqual(decoded.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readLine(input: java.io.InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 2048) {
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(ch)
        }
        throw IllegalArgumentException("Header too long")
    }

    private fun respond(socket: Socket, code: Int, json: JSONObject, challenge: Boolean = false) {
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; else -> "Internal Server Error" }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\nConnection: close\r\n" +
            (if (challenge) "WWW-Authenticate: Basic realm=\"RK3399 Control\"\r\n" else "") + "\r\n"
        socket.getOutputStream().write(header.toByteArray(StandardCharsets.US_ASCII) + payload)
    }

    private fun result(shell: ShellResult) = JSONObject().put("ok", shell.code == 0).put("code", shell.code).put("output", shell.output.takeLast(20000))
    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        const val PORT = 8765
        private const val TAG = "RkRemoteControl"
        private const val PASSWORD_KEY = "remote_password"
    }
}
