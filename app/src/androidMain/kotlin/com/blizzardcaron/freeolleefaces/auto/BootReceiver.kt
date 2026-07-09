package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the auto-update chain after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoUpdateScheduler.reschedule(context)
        }
    }
}
