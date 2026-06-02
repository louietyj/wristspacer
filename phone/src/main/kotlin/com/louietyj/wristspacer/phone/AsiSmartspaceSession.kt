package com.louietyj.wristspacer.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.UUID

/**
 * Manages a direct session with Android System Intelligence's SmartspaceService,
 * bypassing the public SmartspaceManager so that ALL targets (including the
 * commute card, featureType=3) are visible.
 *
 * Flow:
 *   1. Bind our Shizuku UserService (shell UID).
 *   2. Shizuku UserService calls IActivityManager.bindServiceInstance with
 *      callingPackage = "com.android.systemui", so ASI accepts the bind.
 *   3. In onServiceConnected (our app process) we obtain the ASI IBinder.
 *   4. We create an ISmartspaceService session via raw IBinder.transact() calls.
 *   5. We register a raw Binder as the ISmartspaceCallback; ASI calls onResult()
 *      with a ParceledListSlice<SmartspaceTarget> deserialized via the system
 *      classloader — no hidden-API compile stubs required anywhere.
 *
 * All IPC is pure reflection + transact(), matching the pattern already used in
 * SmartspaceBridgeService for the SmartspaceManager path.
 */
class AsiSmartspaceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTargets: (List<Any?>) -> Unit
) {
    // ASI service binder (set once the privileged bind succeeds)
    @Volatile private var asiService: IBinder? = null

    // SmartspaceSessionId object (created via reflection)
    @Volatile private var sessionId: Any? = null

    // The Binder we register as ISmartspaceCallback
    private var callbackBinder: Binder? = null

    // Shizuku UserService proxy
    private var shizukuBridge: IWristspacerShizukuService? = null

    // ServiceConnection for the ASI bind — lives in OUR process; ASI calls back here
    // when the privileged bind initiated by the Shizuku service succeeds.
    private val asiConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "ASI connected: $name")
            asiService = service
            createSession()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ASI disconnected — session lost")
            asiService = null
        }
    }

    // ServiceConnection for the Shizuku UserService
    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Shizuku bridge connected")
            shizukuBridge = IWristspacerShizukuService.Stub.asInterface(service)
            bindAsi()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku bridge disconnected")
            shizukuBridge = null
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Begin the binding chain: Shizuku → ASI → session. */
    fun start() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WristspacerShizukuService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("smartspace_bridge")
            .version(BUILD_VERSION)

        try {
            Shizuku.bindUserService(args, shizukuConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
        }
    }

    /**
     * Tear down the ASI session and unbind the Shizuku service.
     * Safe to call if start() never completed.
     */
    fun destroy() {
        destroyAsiSession()
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, WristspacerShizukuService::class.java.name)
            )
            Shizuku.unbindUserService(args, shizukuConnection, true)
        } catch (e: Exception) {
            Log.w(TAG, "unbindUserService: ${e.message}")
        }
    }

    /**
     * Sends requestSmartspaceUpdate to ASI if the session is live.
     * The SmartspaceBridgeService can call this when the watch asks for a refresh.
     */
    fun requestUpdate() {
        val service = asiService ?: return
        val session = sessionId ?: return
        transact(service, TRANS_REQUEST_UPDATE) {
            writeParcelable(session)
        }
    }

    // -------------------------------------------------------------------------
    // Binding helpers
    // -------------------------------------------------------------------------

    private fun bindAsi() {
        val bridge = shizukuBridge ?: run { Log.e(TAG, "bridge null"); return }

        // Locate the ASI SmartspaceService component
        val component = resolveAsiComponent() ?: run {
            Log.e(TAG, "config_defaultSmartspaceService is empty or not installed"); return
        }
        val intent = Intent(ACTION_SMARTSPACE_SERVICE).apply { setComponent(component) }

        // Context.getServiceDispatcher() — hidden API, accessible with hidden_api_policy=1
        val handler    = Handler(Looper.getMainLooper())
        val dispatcher = runCatching { getServiceDispatcher(asiConnection, handler) }.getOrElse {
            Log.e(TAG, "getServiceDispatcher failed: ${it.message}"); return
        }

        // ActivityThread.currentActivityThread().getApplicationThread()
        val appThread = runCatching { getApplicationThread() }.getOrElse {
            Log.e(TAG, "getApplicationThread failed: ${it.message}"); return
        }

        // Marshall the intent to bytes for transmission to the Shizuku process
        val intentBytes = runCatching {
            Parcel.obtain().let { p ->
                intent.writeToParcel(p, 0)
                p.marshall().also { p.recycle() }
            }
        }.getOrElse {
            Log.e(TAG, "intent marshall failed: ${it.message}"); return
        }

        try {
            val result = bridge.bindSmartspaceService(dispatcher, appThread, intentBytes)
            Log.d(TAG, "bindSmartspaceService → $result (>0 means success)")
        } catch (e: Exception) {
            Log.e(TAG, "bindSmartspaceService RPC failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Session creation
    // -------------------------------------------------------------------------

    private fun createSession() {
        val service = asiService ?: return

        // Build SmartspaceConfig via reflection (same approach as SmartspaceBridgeService)
        val config = runCatching {
            val builderClass = Class.forName("android.app.smartspace.SmartspaceConfig\$Builder")
            val builder = builderClass
                .getConstructor(Context::class.java, String::class.java)
                .newInstance(context, SURFACE_HOME)
            builderClass.getMethod("setSmartspaceTargetCount", Int::class.java).invoke(builder, 5)
            builderClass.getMethod("build").invoke(builder)!!
        }.getOrElse {
            Log.e(TAG, "SmartspaceConfig.Builder failed: ${it.message}"); return
        }

        // Build SmartspaceSessionId via reflection
        val session = runCatching {
            Class.forName("android.app.smartspace.SmartspaceSessionId")
                .getConstructor(String::class.java, UserHandle::class.java)
                .newInstance("${context.packageName}:${UUID.randomUUID()}", Process.myUserHandle())!!
        }.getOrElse {
            Log.e(TAG, "SmartspaceSessionId creation failed: ${it.message}"); return
        }
        sessionId = session

        // Create and store the callback binder
        val cb = makeCallbackBinder()
        callbackBinder = cb

        // onCreateSmartspaceSession(config, sessionId)
        transact(service, TRANS_CREATE_SESSION) {
            writeParcelable(config)
            writeParcelable(session)
        }.also { if (!it) { Log.e(TAG, "onCreateSmartspaceSession failed"); return } }

        // registerSmartspaceUpdates(sessionId, callback)
        transact(service, TRANS_REGISTER_CB) {
            writeParcelable(session)
            writeStrongBinder(cb)
        }.also { if (!it) { Log.e(TAG, "registerSmartspaceUpdates failed"); return } }

        // requestSmartspaceUpdate(sessionId) — kick off the first delivery
        transact(service, TRANS_REQUEST_UPDATE) {
            writeParcelable(session)
        }

        Log.i(TAG, "ASI session live — surface=$SURFACE_HOME, " +
                "callerPackage=${context.packageName}")
    }

    private fun destroyAsiSession() {
        val service = asiService ?: return
        val session = sessionId ?: return
        transact(service, TRANS_DESTROY_SESSION) {
            writeParcelable(session)
        }
        asiService = null
        sessionId  = null
    }

    // -------------------------------------------------------------------------
    // Callback Binder — receives onResult(ParceledListSlice<SmartspaceTarget>)
    // -------------------------------------------------------------------------

    /**
     * Raw Binder that implements ISmartspaceCallback without needing
     * ParceledListSlice or SmartspaceTarget at compile time.
     *
     * ASI serialises ParceledListSlice via writeTypedObject(), which writes:
     *   [int: presence flag (1)]  [raw Parcel data — no class-name prefix]
     * We must NOT use readParcelable() (which expects a class-name prefix);
     * instead we call ParceledListSlice.CREATOR.createFromParcel() reflectively.
     */
    private fun makeCallbackBinder() = object : Binder() {
        init {
            // Attach our descriptor so ASI can verify the interface token
            attachInterface(null, CALLBACK_INTERFACE)
        }

        @Suppress("UNCHECKED_CAST")
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_INTERFACE)

            val list: List<Any?> = runCatching {
                // Presence flag (writeTypedObject format)
                if (data.readInt() == 0) return@runCatching emptyList()

                // ParceledListSlice.CREATOR.createFromParcel(data)
                val sliceClass = Class.forName("android.content.pm.ParceledListSlice")
                val creator    = sliceClass.getField("CREATOR").get(null)
                val slice      = creator.javaClass
                    .getMethod("createFromParcel", Parcel::class.java)
                    .invoke(creator, data)

                slice?.let { s ->
                    sliceClass.getMethod("getList").invoke(s) as? List<Any?>
                } ?: emptyList()
            }.getOrElse { e ->
                Log.e(TAG, "callback deserialization failed: ${e.message}", e)
                emptyList()
            }

            scope.launch(Dispatchers.IO) { onTargets(list) }
            return true
        }
    }

    // -------------------------------------------------------------------------
    // transact helper
    // -------------------------------------------------------------------------

    /**
     * Sends a one-way transact to [binder] with the ASI interface token pre-written.
     * Returns true if the transact did not throw.
     */
    private inline fun transact(
        binder: IBinder,
        code: Int,
        block: Parcel.() -> Unit
    ): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ASI_INTERFACE)
            data.block()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Exception) {
            Log.w(TAG, "transact($code): ${e.message}")
            false
        } finally {
            data.recycle()
        }
    }

    /**
     * Writes a Parcelable (which may be a hidden framework type) using the same
     * format AIDL uses for `in` parameters: [presence int][writeToParcel data].
     */
    private fun Parcel.writeParcelable(obj: Any) {
        writeInt(1) // non-null marker
        obj.javaClass.getMethod("writeToParcel", Parcel::class.java, Int::class.java)
            .invoke(obj, this, 0)
    }

    // -------------------------------------------------------------------------
    // Hidden-API helpers (accessible because hidden_api_policy=1 is set via Shizuku)
    // -------------------------------------------------------------------------

    /**
     * Context.getServiceDispatcher() returns an IServiceConnection binder that
     * wraps a regular ServiceConnection so the system can call back into our process.
     * Tries the API-34+ long-flags version first, then the int-flags version.
     */
    private fun getServiceDispatcher(conn: ServiceConnection, handler: Handler): IBinder {
        val ctx = context
        return try {
            ctx.javaClass
                .getMethod("getServiceDispatcher",
                    ServiceConnection::class.java, Handler::class.java, Long::class.java)
                .invoke(ctx, conn, handler, 0L) as IBinder
        } catch (_: NoSuchMethodException) {
            ctx.javaClass
                .getMethod("getServiceDispatcher",
                    ServiceConnection::class.java, Handler::class.java, Int::class.java)
                .invoke(ctx, conn, handler, 0) as IBinder
        }
    }

    /** Gets the IApplicationThread binder from the current ActivityThread. */
    private fun getApplicationThread(): IBinder {
        val at = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null)
        return at.javaClass.getMethod("getApplicationThread").invoke(at) as IBinder
    }

    /** Reads config_defaultSmartspaceService from the android package resources. */
    private fun resolveAsiComponent(): ComponentName? {
        val id = context.resources.getIdentifier(
            "config_defaultSmartspaceService", "string", "android"
        )
        if (id == 0) return null
        val flat = runCatching { context.resources.getString(id) }.getOrNull() ?: return null
        if (flat.isBlank()) return null
        return ComponentName.unflattenFromString(flat)
    }

    // -------------------------------------------------------------------------
    companion object {
        private const val TAG = "AsiSmartspaceSession"

        // ISmartspaceService descriptor
        private const val ASI_INTERFACE      = "android.service.smartspace.ISmartspaceService"
        private const val CALLBACK_INTERFACE = "android.app.smartspace.ISmartspaceCallback"
        private const val ACTION_SMARTSPACE_SERVICE =
            "android.service.smartspace.SmartspaceService"
        private const val SURFACE_HOME = "home"

        // Transaction codes from ISmartspaceService.aidl (stable AOSP ordering):
        //   1 onCreateSmartspaceSession
        //   2 notifySmartspaceEvent
        //   3 requestSmartspaceUpdate
        //   4 registerSmartspaceUpdates
        //   5 unregisterSmartspaceUpdates
        //   6 onDestroySmartspaceSession
        private const val TRANS_CREATE_SESSION  = IBinder.FIRST_CALL_TRANSACTION      // 1
        private const val TRANS_REQUEST_UPDATE  = IBinder.FIRST_CALL_TRANSACTION + 2  // 3
        private const val TRANS_REGISTER_CB     = IBinder.FIRST_CALL_TRANSACTION + 3  // 4
        private const val TRANS_DESTROY_SESSION = IBinder.FIRST_CALL_TRANSACTION + 5  // 6

        // Bump this when WristspacerShizukuService's AIDL signature changes.
        private const val BUILD_VERSION = 1
    }
}
