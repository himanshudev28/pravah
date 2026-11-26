package dev.pravah.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after reboot / app update so charge sessions keep being recorded. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startForegroundService(Intent(context, BatteryMonitorService::class.java))
        }
    }
}
