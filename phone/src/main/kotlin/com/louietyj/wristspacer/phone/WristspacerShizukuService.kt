package com.louietyj.wristspacer.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.UserHandle  // used via reflection only — getMethod("myUserId")
import android.util.Log

/**
 * Shizuku user service — runs in the shell (UID 2000) process.
 *
 * Its sole job is to call IActivityManager.bindServiceInstance with
 * callingPackage = "com.android.systemui", which makes Android System
 * Intelligence accept the bind as if System UI requested it.
 *
 * Everything here is done via reflection because:
 *  a) we need no hidden-API compile stubs in the main module, and
 *  b) the shell UID has no hidden-API enforcement, so reflection works freely.
 */
class WristspacerShizukuService : IWristspacerShizukuService.Stub() {

    private val activityManager: Any? by lazy { resolveActivityManager() }

    // -------------------------------------------------------------------------
    // AIDL implementation
    // -------------------------------------------------------------------------

    override fun bindSmartspaceService(
        serviceConnection: IBinder,
        applicationThread: IBinder,
        intentBytes: ByteArray
    ): Int {
        val am = activityManager ?: run {
            Log.e(TAG, "IActivityManager unavailable"); return 0
        }

        return try {
            val intent = unmarshallIntent(intentBytes)

            // Intent must be prepared for cross-process delivery
            try {
                Intent::class.java.getMethod("prepareToLeaveProcess", Boolean::class.java)
                    .invoke(intent, false)
            } catch (_: Exception) { /* not available on all versions */ }

            // Wrap raw IBinder args as their actual AIDL interface types
            val thread = wrapAs("android.app.IApplicationThread\$Stub", applicationThread)
            val conn   = wrapAs("android.app.IServiceConnection\$Stub", serviceConnection)

            callBindService(am, thread, conn, intent)
        } catch (e: Exception) {
            Log.e(TAG, "bindSmartspaceService failed: ${e.message}", e)
            0
        }
    }

    override fun destroy() {
        // Nothing to clean up — the process will be killed by Shizuku.
    }

    // -------------------------------------------------------------------------
    // IActivityManager resolution
    // -------------------------------------------------------------------------

    private fun resolveActivityManager(): Any? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder ?: return null
        Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    } catch (e: Exception) {
        Log.e(TAG, "resolveActivityManager: ${e.message}"); null
    }

    // -------------------------------------------------------------------------
    // Version-aware bindServiceInstance call
    // -------------------------------------------------------------------------

    /**
     * Calls the right overload of bindServiceInstance / bindIsolatedService depending on
     * the Android API level.
     *
     * API 34+ (U):  bindServiceInstance(IApplicationThread, IBinder, Intent, String,
     *                                    IServiceConnection, long, String, String, int)
     * API 33   (T): bindServiceInstance(IApplicationThread, IBinder, Intent, String,
     *                                    IServiceConnection, int,  String, String, int)
     * API 31-32(S): bindIsolatedService (same signature as T, different name)
     */
    private fun callBindService(
        am: Any,
        thread: Any?,
        conn: Any?,
        intent: Intent
    ): Int {
        val sdkInt  = Build.VERSION.SDK_INT
        val flags   = Context.BIND_AUTO_CREATE
        // UserHandle.myUserId() is @hide — access via reflection (unrestricted in shell process)
        val userId  = runCatching {
            UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
        }.getOrDefault(0)
        val caller  = "com.android.systemui"

        val (methodName, flagType) = when {
            sdkInt >= 34 -> "bindServiceInstance" to Long::class.java
            sdkInt >= 33 -> "bindServiceInstance" to Int::class.java
            else         -> "bindIsolatedService"  to Int::class.java
        }

        val method = am.javaClass.methods.firstOrNull { m ->
            m.name == methodName &&
            m.parameterCount == 9 &&
            m.parameterTypes[5] == flagType
        } ?: run {
            Log.e(TAG, "Could not find $methodName(…,${flagType.simpleName},…) in IActivityManager")
            return 0
        }

        val flagArg: Any = if (flagType == Long::class.java) flags.toLong() else flags

        return method.invoke(
            am,
            thread,     // IApplicationThread caller
            null,       // IBinder token
            intent,     // Intent service
            null,       // String resolvedType
            conn,       // IServiceConnection connection
            flagArg,    // int/long flags
            null,       // String instanceName
            caller,     // String callingPackage ← the magic spoof
            userId      // int userId
        ) as? Int ?: 0
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun unmarshallIntent(bytes: ByteArray): Intent {
        val parcel = Parcel.obtain()
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        return try {
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /** Wraps [binder] by calling StubClass.asInterface(binder) reflectively. */
    private fun wrapAs(stubClassName: String, binder: IBinder): Any? = try {
        Class.forName(stubClassName)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    } catch (e: Exception) {
        Log.w(TAG, "wrapAs($stubClassName): ${e.message}")
        binder // fall back to raw IBinder — Android may accept it anyway
    }

    companion object {
        private const val TAG = "WristspacerShizuku"
    }
}
