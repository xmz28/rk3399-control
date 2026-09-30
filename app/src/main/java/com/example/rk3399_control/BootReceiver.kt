package com.example.rk3399_control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        context.startService(Intent(context, RemoteControlService::class.java))
        ControlRepository(context.applicationContext).initialize { pending.finish() }
    }
}
