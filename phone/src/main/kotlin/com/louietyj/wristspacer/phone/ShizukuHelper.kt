package com.louietyj.wristspacer.phone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuHelper"
private const val PERMISSION_ACCESS_SMARTSPACE = "android.permission.ACCESS_SMARTSPACE"
private const val SHIZUKU_REQUEST_CODE = 42

object ShizukuHelper {

    /** Returns true if Shizuku is running and our app has its permission. */
    fun isReady(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: IllegalStateException) {
        false // Shizuku not bound yet
    }

    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) return
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}")
        }
    }

    /**
     * Grants android.permission.ACCESS_SMARTSPACE to [packageName] via `pm grant`.
     * This works because ACCESS_SMARTSPACE has protectionLevel=development, which allows
     * ADB-level callers (i.e. the Shizuku shell process) to grant it.
     */
    fun grantAccessSmartspace(packageName: String): Boolean {
        if (!isReady()) return false
        return try {
            val process = Shizuku.newProcess(
                arrayOf("pm", "grant", packageName, PERMISSION_ACCESS_SMARTSPACE),
                null, null
            )
            val exit = process.waitFor()
            Log.d(TAG, "pm grant exit=$exit")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "pm grant failed: ${e.message}")
            false
        }
    }

    /**
     * Fallback: if the VMRuntime bypass in WristspacerApp didn't work, set the global hidden API
     * policy to 1 (warn only) so reflection on hidden APIs succeeds.
     * Only has effect until next reboot but that's fine — Shizuku restarts need this anyway.
     */
    fun applyHiddenApiPolicyViaShizuku(): Boolean {
        if (!isReady()) return false
        return try {
            val process = Shizuku.newProcess(
                arrayOf("settings", "put", "global", "hidden_api_policy", "1"),
                null, null
            )
            val exit = process.waitFor()
            Log.d(TAG, "hidden_api_policy set, exit=$exit")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "hidden_api_policy failed: ${e.message}")
            false
        }
    }

    fun hasAccessSmartspace(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION_ACCESS_SMARTSPACE) == PackageManager.PERMISSION_GRANTED
}
