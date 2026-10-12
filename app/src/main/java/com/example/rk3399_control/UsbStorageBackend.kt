package com.example.rk3399_control

import org.json.JSONObject

object UsbStorageBackend {
    fun request(body: JSONObject? = null): JSONObject {
        val command = "chroot /data/local/alpine-nas /usr/bin/python3 /opt/nas-storage.py " +
            if (body == null) "list" else "action ${RootShell.quote(body.toString())}"
        val result = RootShell.run(command, 240)
        return runCatching { JSONObject(result.output) }.getOrElse {
            JSONObject().put("ok", false).put("error", if (result.timedOut) "操作超时，请刷新磁盘状态后检查" else result.output.takeLast(500))
        }
    }
}
