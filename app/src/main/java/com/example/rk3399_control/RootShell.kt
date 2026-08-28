package com.example.rk3399_control

import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(val code: Int, val output: String, val timedOut: Boolean = false)

object RootShell {
    fun run(command: String, timeoutSeconds: Long = 20): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val readerThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }.apply { start() }
            // Process.waitFor(timeout, unit) is API 26 despite Java 8 source
            // compatibility. RK3399 targets Android 7.1/API 25, so poll the
            // exit value instead.
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            var exitCode: Int? = null
            while (exitCode == null && System.currentTimeMillis() < deadline) {
                exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (exitCode == null) Thread.sleep(50)
            }
            if (exitCode == null) {
                process.destroyForcibly()
                readerThread.join(500)
                ShellResult(-1, output.append("命令执行超时").toString().trim(), true)
            } else {
                readerThread.join(500)
                ShellResult(exitCode, output.toString().trim())
            }
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
