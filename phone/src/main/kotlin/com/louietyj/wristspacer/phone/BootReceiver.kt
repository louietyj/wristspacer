package com.louietyj.wristspacer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the bridge automatically after the phone boots.
 * Shizuku runs its own persistent service and is typically ready before
 * BOOT_COMPLETED fires, so the bridge should initialise cleanly. If Shizuku
 * isn't ready in time the service will log an error and stop itself — the
 * user can open the app to restart it manually.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            SmartspaceBridgeService.start(context)
        }
    }
}
