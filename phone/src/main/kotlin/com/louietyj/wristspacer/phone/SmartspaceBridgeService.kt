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
    // Privileged ASI session (Shizuku path — sees all targets including commute)
    private var asiSession: AsiSmartspaceSession? = null
    // Fallback SmartspaceManager session (only used if Shizuku is unavailable)
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
        asiSession?.destroy()
        asiSession = null
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
        // Step 1: ensure ACCESS_SMARTSPACE is granted (needed for the fallback path)
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

        // Step 3: primary path — direct ISmartspaceService bind via Shizuku (shell UID).
        // This sees ALL Smartspace targets including featureType=3 (commute cards), which
        // the public SmartspaceManager filters out for non-system callers.
        if (ShizukuHelper.isReady()) {
            // Guard against duplicate starts (onStartCommand may fire more than once).
            if (asiSession != null) {
                Log.d(TAG, "ASI session already active — skipping duplicate setup")
                return
            }
            Log.d(TAG, "Shizuku available — using privileged ASI session")
            val session = AsiSmartspaceSession(this, scope) { targets ->
                processTargets(targets)
            }
            asiSession = session
            session.start()
            // Status is updated to Running by AsiSmartspaceSession once the ASI session
            // is established and the first target batch arrives. We return here and let
            // callbacks drive the rest of the lifecycle.
            return
        }

        // Step 4 (fallback): SmartspaceManager session — no commute card, but still useful
        // when Shizuku is absent.
        Log.w(TAG, "Shizuku not ready — falling back to SmartspaceManager (no commute card)")
        setupSmartspaceManagerFallback()
    }

    private fun setupSmartspaceManagerFallback() {
        // Confirm SmartspaceManager class is visible
        try {
            Class.forName("android.app.smartspace.SmartspaceManager")
        } catch (e: ClassNotFoundException) {
            fail("SmartspaceManager not available on this device (requires API 31)")
            return
        }

        // Get the SmartspaceManager instance
        val manager: Any = getSystemService("smartspace") ?: run {
            fail("getSystemService(smartspace) returned null even after hidden_api_policy=1.\n" +
                 "Run manually: adb shell settings put global hidden_api_policy 1")
            return
        }

        // Build SmartspaceConfig via reflection
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

        // Create SmartspaceSession
        val session = try {
            val managerClass = Class.forName("android.app.smartspace.SmartspaceManager")
            val configClass = Class.forName("android.app.smartspace.SmartspaceConfig")
            managerClass.getMethod("createSmartspaceSession", configClass).invoke(manager, config)
        } catch (e: Exception) {
            fail("Failed to create SmartspaceSession: ${e.message}")
            return
        }
        smartspaceSession = session

        // Register listener via dynamic proxy
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

        sessionClass.getMethod("requestSmartspaceUpdate").invoke(session)

        Log.d(TAG, "SmartspaceManager fallback session created and listening")
        AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
    }

    // -------------------------------------------------------------------------
    // Target processing
    // -------------------------------------------------------------------------

    /**
     * Processes a batch of SmartspaceTargets from either the ASI direct session or the
     * SmartspaceManager fallback.
     *
     * Priority:
     *   1. FEATURE_COMMUTE  (featureType=3)  — commute card, ASI path only
     *   2. FEATURE_CALENDAR (featureType=2)  — next calendar event
     *   3. First target with any extractable title (fallback)
     */
    private fun processTargets(targets: List<Any?>) {
        // Diagnostic: log every target's feature type and template data type so we can
        // understand what Smartspace is actually returning when "none" appears.
        targets.forEachIndexed { i, target ->
            target ?: return@forEachIndexed
            val featureType = runCatching {
                target.javaClass.getMethod("getFeatureType").invoke(target)
            }.getOrNull()
            val templateType = runCatching {
                target.javaClass.getMethod("getTemplateData").invoke(target)?.javaClass?.simpleName
            }.getOrNull() ?: "null"
            val headerTitle = textFromAction(target, "getHeaderAction")
            val baseTitle   = textFromAction(target, "getBaseAction")
            Log.d(TAG, "Target[$i] featureType=$featureType templateData=$templateType " +
                    "headerTitle='$headerTitle' baseTitle='$baseTitle'")
        }

        data class Candidate(val target: Any, val featureType: Int, val title: String, val subtitle: String)

        // Collect FEATURE_COMMUTE (3) and FEATURE_CALENDAR (2) targets
        val structuredCandidates = targets.mapNotNull { target ->
            target ?: return@mapNotNull null
            val featureType = runCatching {
                target.javaClass.getMethod("getFeatureType").invoke(target) as? Int
            }.getOrNull() ?: return@mapNotNull null

            if (featureType != FEATURE_COMMUTE && featureType != FEATURE_CALENDAR) return@mapNotNull null

            val templateData = runCatching {
                target.javaClass.getMethod("getTemplateData").invoke(target)
            }.getOrNull()

            // Commute cards store the text in header/base actions; calendar in templateData sub-items
            val title = when (featureType) {
                FEATURE_COMMUTE -> textFromAction(target, "getHeaderAction")
                    ?: textFromAction(target, "getBaseAction")
                    ?: textFromSubItem(templateData, "getPrimaryItem")
                else -> textFromSubItem(templateData, "getPrimaryItem")
            } ?: return@mapNotNull null

            val subtitle = when (featureType) {
                FEATURE_COMMUTE -> textFromActionSubtitle(target, "getHeaderAction")
                    ?: textFromActionSubtitle(target, "getBaseAction")
                    ?: textFromSubItem(templateData, "getSubtitleItem")
                    ?: ""
                else -> textFromSubItem(templateData, "getSubtitleItem") ?: ""
            }
            Candidate(target, featureType, title, subtitle)
        }

        // Separate commute and calendar; commute takes priority
        val commuteCandidates  = structuredCandidates.filter { it.featureType == FEATURE_COMMUTE }
        val calendarCandidates = structuredCandidates.filter { it.featureType == FEATURE_CALENDAR }

        // Commute card takes highest priority
        val chosen: Candidate? = if (commuteCandidates.isNotEmpty()) {
            commuteCandidates.first().also {
                Log.d(TAG, "Commute card: '${it.title}' / '${it.subtitle}'")
            }
        } else {
            // Remove [Mirror] X when X also exists — then pick the first remaining calendar event.
            val dedupedCalendar = if (calendarCandidates.isNotEmpty()) {
                val nonMirroredTitles = calendarCandidates
                    .map { it.title }
                    .filter { !it.startsWith("[Mirror]", ignoreCase = true) }
                    .toSet()
                calendarCandidates.filter { c ->
                    val stripped = c.title.removePrefix("[Mirror] ").removePrefix("[mirror] ")
                    !c.title.startsWith("[Mirror]", ignoreCase = true) || stripped !in nonMirroredTitles
                }
            } else emptyList()
            dedupedCalendar.firstOrNull()
        }

        if (chosen == null) {
            // No calendar targets — iterate all targets until one yields an extractable title.
            // Some targets (e.g. featureType=72 placeholders) have null templateData and null
            // action titles; skipping them lets us reach the real content further down the list.
            val fallbackEvent = targets.asSequence().filterNotNull().mapNotNull { target ->
                val templateData = runCatching {
                    target.javaClass.getMethod("getTemplateData").invoke(target)
                }.getOrNull()
                val title = textFromSubItem(templateData, "getPrimaryItem")
                    ?: textFromAction(target, "getHeaderAction")
                    ?: textFromAction(target, "getBaseAction")
                if (title.isNullOrEmpty()) return@mapNotNull null
                val subtitle = textFromSubItem(templateData, "getSubtitleItem")
                    ?: textFromActionSubtitle(target, "getHeaderAction")
                    ?: textFromActionSubtitle(target, "getBaseAction")
                    ?: ""
                CalendarEvent(title = title, subtitle = subtitle)
            }.firstOrNull()

            if (fallbackEvent == null) {
                Log.w(TAG, "No target yielded an extractable title — clearing")
                AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
                pushToWatch(null)
                return
            }
            Log.d(TAG, "Fallback event: '${fallbackEvent.title}' / '${fallbackEvent.subtitle}'")
            AppState.updateBridgeStatus(BridgeStatus.Running(event = fallbackEvent))
            pushToWatch(fallbackEvent)
            return
        }

        val label = if (chosen.featureType == FEATURE_COMMUTE) "Commute" else "Calendar"
        Log.d(TAG, "$label event: '${chosen.title}' / '${chosen.subtitle}'")
        val event = CalendarEvent(title = chosen.title, subtitle = chosen.subtitle)
        AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
        pushToWatch(event)
    }

    /** Extracts text from templateData → subItemGetter() → getText() → getText(). */
    private fun textFromSubItem(templateData: Any?, subItemGetter: String): String? = runCatching {
        val subItem = templateData?.javaClass?.getMethod(subItemGetter)?.invoke(templateData)
            ?: return@runCatching null
        val textObj = subItem.javaClass.getMethod("getText").invoke(subItem)
            ?: return@runCatching null
        textObj.javaClass.getMethod("getText").invoke(textObj)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Extracts the title string from a SmartspaceAction on the target (header or base). */
    private fun textFromAction(target: Any, actionGetter: String): String? = runCatching {
        val action = target.javaClass.getMethod(actionGetter).invoke(target)
            ?: return@runCatching null
        action.javaClass.getMethod("getTitle").invoke(action)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Extracts the subtitle string from a SmartspaceAction on the target (header or base). */
    private fun textFromActionSubtitle(target: Any, actionGetter: String): String? = runCatching {
        val action = target.javaClass.getMethod(actionGetter).invoke(target)
            ?: return@runCatching null
        action.javaClass.getMethod("getSubtitle").invoke(action)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

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
        private const val FEATURE_COMMUTE  = 3

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SmartspaceBridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmartspaceBridgeService::class.java))
        }
    }
}
