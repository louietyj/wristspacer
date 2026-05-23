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
     * ACCESS_SMARTSPACE has protectionLevel=development, which allows ADB-level callers
     * (i.e. the Shizuku shell process) to grant it at runtime.
     *
     * Shizuku 13.x made newProcess package-private in preparation for removal, so we
     * access it via reflection to avoid the Kotlin compile-time visibility check.
     * The method still works at runtime.
     */
    fun grantAccessSmartspace(packageName: String): Boolean {
        if (!isReady()) return false
        return runShizukuCommand("pm", "grant", packageName, PERMISSION_ACCESS_SMARTSPACE)
    }

    /**
     * Fallback: if the VMRuntime bypass in WristspacerApp didn't open hidden APIs, set the
     * global hidden_api_policy to 1 (warn-only) so reflection on hidden APIs succeeds.
     * Persists until next reboot.
     */
    fun applyHiddenApiPolicyViaShizuku(): Boolean {
        if (!isReady()) return false
        return runShizukuCommand("settings", "put", "global", "hidden_api_policy", "1")
    }

    fun hasAccessSmartspace(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION_ACCESS_SMARTSPACE) == PackageManager.PERMISSION_GRANTED

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs an arbitrary command via Shizuku's newProcess, which executes in the
     * ADB/root shell. Returns true if the command exits with code 0.
     *
     * We call newProcess via getDeclaredMethod+setAccessible because Shizuku 13.x
     * restricted its visibility (it is slated for removal in favour of UserService).
     * Accessing it reflectively still works and is the least-invasive fix for now.
     */
    private fun runShizukuCommand(vararg cmd: String): Boolean {
        return try {
            val stringArrayClass = emptyArray<String>().javaClass  // String[].class
            val newProcess = Shizuku::class.java
                .getDeclaredMethod("newProcess", stringArrayClass, stringArrayClass, String::class.java)
                .also { it.isAccessible = true }

            val process = newProcess.invoke(null, cmd, null, null)
                ?: return false

            val exit = process.javaClass.getMethod("waitFor").invoke(process) as? Int ?: -1
            Log.d(TAG, "Shizuku cmd=${cmd.toList()} exit=$exit")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "runShizukuCommand ${cmd.toList()} failed: ${e.message}")
            false
        }
    }
}
