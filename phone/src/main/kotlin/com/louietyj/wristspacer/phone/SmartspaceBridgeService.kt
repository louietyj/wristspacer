package com.louietyj.wristspacer.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class SmartspaceBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var smartspaceSession: Any? = null
    private lateinit var dataClient: DataClient

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        dataClient = Wearable.getDataClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        AppState.updateBridgeStatus(BridgeStatus.Starting)
        scope.launch { setupSmartspace() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroySession()
        scope.cancel()
        AppState.updateBridgeStatus(BridgeStatus.Stopped)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Smartspace session setup — all reflection, no stubs needed
    // -------------------------------------------------------------------------

    private fun setupSmartspace() {
        // Step 1: ensure ACCESS_SMARTSPACE is granted
        if (!ShizukuHelper.hasAccessSmartspace(this)) {
            val granted = ShizukuHelper.grantAccessSmartspace(packageName)
            if (!granted) {
                fail("ACCESS_SMARTSPACE grant failed — is Shizuku running?")
                return
            }
        }

        // Step 2: proactively apply the hidden_api_policy=1 fallback via Shizuku.
        // The VMRuntime double-reflection bypass can fail on Android 16; this is the
        // belt-and-suspenders approach that doesn't require a reboot to take effect.
        ShizukuHelper.applyHiddenApiPolicyViaShizuku()

        // Step 3: confirm SmartspaceManager class is visible
        try {
            Class.forName("android.app.smartspace.SmartspaceManager")
        } catch (e: ClassNotFoundException) {
            fail("SmartspaceManager not available on this device (requires API 31)")
            return
        }

        // Step 4: get the SmartspaceManager instance
        val manager: Any = getSystemService("smartspace") ?: run {
            fail("getSystemService(smartspace) returned null even after hidden_api_policy=1.\n" +
                 "Run manually: adb shell settings put global hidden_api_policy 1")
            return
        }

        // Step 4: build SmartspaceConfig via reflection
        val config = try {
            val builderClass = Class.forName("android.app.smartspace.SmartspaceConfig\$Builder")
            val builder = builderClass
                .getConstructor(Context::class.java, String::class.java)
                .newInstance(this, "home")
            builderClass.getMethod("setSmartspaceTargetCount", Int::class.java).invoke(builder, 5)
            builderClass.getMethod("build").invoke(builder)
        } catch (e: Exception) {
            fail("Failed to build SmartspaceConfig: ${e.message}")
            return
        }

        // Step 5: create SmartspaceSession
        val session = try {
            val managerClass = Class.forName("android.app.smartspace.SmartspaceManager")
            val configClass = Class.forName("android.app.smartspace.SmartspaceConfig")
            managerClass.getMethod("createSmartspaceSession", configClass).invoke(manager, config)
        } catch (e: Exception) {
            fail("Failed to create SmartspaceSession: ${e.message}")
            return
        }
        smartspaceSession = session

        // Step 6: register listener via dynamic proxy
        val sessionClass = Class.forName("android.app.smartspace.SmartspaceSession")
        val listenerInterface = Class.forName(
            "android.app.smartspace.SmartspaceSession\$OnTargetsAvailableListener"
        )

        val listenerProxy = Proxy.newProxyInstance(
            listenerInterface.classLoader,
            arrayOf(listenerInterface)
        ) { proxy, method, args ->
            // SmartspaceSession stores the listener in an ArrayMap keyed by hashCode,
            // so the proxy MUST return a valid int from hashCode() — null causes an NPE
            // when the runtime tries to unbox it to a primitive int.
            when (method.name) {
                "onTargetsAvailable" -> {
                    if (args != null) {
                        @Suppress("UNCHECKED_CAST")
                        processTargets(args[0] as List<Any?>)
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.firstOrNull()
                "toString" -> "WristspacerListener@${Integer.toHexString(System.identityHashCode(proxy))}"
                else       -> null
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        sessionClass.getMethod(
            "addOnTargetsAvailableListener",
            java.util.concurrent.Executor::class.java,
            listenerInterface
        ).invoke(session, executor, listenerProxy)

        // Step 7: request the first update
        sessionClass.getMethod("requestSmartspaceUpdate").invoke(session)

        Log.d(TAG, "SmartspaceSession created and listening")
        AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
    }

    // -------------------------------------------------------------------------
    // Target processing
    // -------------------------------------------------------------------------

    /**
     * Finds the first FEATURE_CALENDAR target (featureType == 2) and extracts the event
     * title and time. SmartspaceTarget layout for calendar events:
     *   baseAction.title    = event title  (e.g. "Weekly Standup")
     *   baseAction.subtitle = location / organiser (optional)
     *   headerAction.title  = time string  (e.g. "10:00 AM") — often empty on some builds
     * We try baseAction first, then headerAction, and log everything for diagnostics.
     */
    private fun processTargets(targets: List<Any?>) {
        val calendarTarget = targets.firstOrNull { target ->
            target?.let {
                val featureType = it.javaClass.getMethod("getFeatureType").invoke(it) as? Int
                featureType == FEATURE_CALENDAR
            } ?: false
        }

        // Fall back to the first available target when there's no upcoming calendar event.
        val target = calendarTarget ?: targets.firstOrNull { it != null }
        val isCalendar = (target === calendarTarget) && calendarTarget != null

        if (target == null) {
            Log.d(TAG, "No Smartspace targets — clearing watch complication")
            AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
            pushToWatch(null)
            return
        }

        // Data lives in templateData (Android 12+), not in the action title/subtitle fields.
        // Path: getTemplateData() → getPrimaryItem() → getText() → getText()  (event title)
        //       getTemplateData() → getSubtitleItem() → getText() → getText() (time range)
        val templateData = runCatching {
            target.javaClass.getMethod("getTemplateData").invoke(target)
        }.getOrNull()

        fun textFromSubItem(subItem: Any?): String? = runCatching {
            val textObj = subItem?.javaClass?.getMethod("getText")?.invoke(subItem) ?: return@runCatching null
            textObj.javaClass.getMethod("getText").invoke(textObj)?.toString()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val primaryItem  = runCatching { templateData?.javaClass?.getMethod("getPrimaryItem")?.invoke(templateData) }.getOrNull()
        val subtitleItem = runCatching { templateData?.javaClass?.getMethod("getSubtitleItem")?.invoke(templateData) }.getOrNull()

        val title    = textFromSubItem(primaryItem)
        val subtitle = textFromSubItem(subtitleItem) ?: ""

        if (title.isNullOrEmpty()) {
            Log.w(TAG, "Target present but could not extract title from templateData — skipping")
            return
        }

        Log.d(TAG, "${if (isCalendar) "Calendar" else "Fallback"} event: '$title' / '$subtitle'")
        val event = CalendarEvent(title = title, subtitle = subtitle)
        AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
        pushToWatch(event)
    }

    // -------------------------------------------------------------------------
    // Watch sync via DataLayer
    // -------------------------------------------------------------------------

    private fun pushToWatch(event: CalendarEvent?) {
        scope.launch {
            try {
                val request = PutDataMapRequest.create(DataKeys.PATH).apply {
                    if (event != null) {
                        dataMap.putString(DataKeys.KEY_TITLE, event.title)
                        dataMap.putString(DataKeys.KEY_SUBTITLE, event.subtitle)
                        dataMap.putLong(DataKeys.KEY_TIMESTAMP, System.currentTimeMillis())
                    } else {
                        dataMap.putString(DataKeys.KEY_TITLE, "")
                        dataMap.putString(DataKeys.KEY_SUBTITLE, "")
                        dataMap.putLong(DataKeys.KEY_TIMESTAMP, System.currentTimeMillis())
                    }
                }.asPutDataRequest().setUrgent()
                dataClient.putDataItem(request).addOnFailureListener { e ->
                    Log.e(TAG, "DataLayer push failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "pushToWatch error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private fun destroySession() {
        val session = smartspaceSession ?: return
        try {
            session.javaClass.getMethod("destroy").invoke(session)
        } catch (e: Exception) {
            Log.w(TAG, "destroySession: ${e.message}")
        }
        smartspaceSession = null
    }

    // -------------------------------------------------------------------------
    // Error helper
    // -------------------------------------------------------------------------

    private fun fail(message: String) {
        Log.e(TAG, message)
        AppState.updateBridgeStatus(BridgeStatus.Error(message))
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Foreground notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wristspacer Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the work calendar bridge running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wristspacer")
            .setContentText("Bridging work calendar to watch…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "SmartspaceBridgeService"
        private const val CHANNEL_ID = "wristspacer_bridge"
        private const val NOTIFICATION_ID = 1001
        private const val FEATURE_CALENDAR = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SmartspaceBridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmartspaceBridgeService::class.java))
        }
    }
}
