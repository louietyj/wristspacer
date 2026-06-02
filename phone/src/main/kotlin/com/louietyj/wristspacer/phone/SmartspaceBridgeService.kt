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

    // SmartspaceManager session — always runs; hooks into Smartspacer when it is the active
    // Smartspace service provider, giving us weather+date and calendar targets.
    private var smartspaceSession: Any? = null

    // Privileged ASI session — runs in parallel when Shizuku is available; watches exclusively
    // for commute cards (featureType=3) that SmartspaceManager filters out. When a commute card
    // is present it overrides the watch display; when it clears we revert to SmartspaceManager.
    private var asiSession: AsiSmartspaceSession? = null

    // True while the ASI session has seen a current commute card.
    // SmartspaceManager callbacks are suppressed while this is true.
    @Volatile private var commuteActive = false

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
        destroySmartspaceManagerSession()
        scope.cancel()
        AppState.updateBridgeStatus(BridgeStatus.Stopped)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Smartspace session setup — all reflection, no stubs needed
    // -------------------------------------------------------------------------

    private fun setupSmartspace() {
        // Step 1: ensure ACCESS_SMARTSPACE is granted (required for SmartspaceManager).
        if (!ShizukuHelper.hasAccessSmartspace(this)) {
            val granted = ShizukuHelper.grantAccessSmartspace(packageName)
            if (!granted) {
                fail("ACCESS_SMARTSPACE grant failed — is Shizuku running?")
                return
            }
        }

        // Step 2: apply hidden_api_policy=1 so reflection on hidden APIs works reliably.
        ShizukuHelper.applyHiddenApiPolicyViaShizuku()

        // Step 3: SmartspaceManager session — the primary data source.
        // When Smartspacer is the active Smartspace service (set via `cmd smartspace
        // set temporary-service`), this session receives Smartspacer's enriched targets:
        // weather+date, calendar events, and any other plugins Smartspacer has configured.
        // Guard against duplicate onStartCommand calls.
        if (smartspaceSession == null) {
            setupSmartspaceManagerSession()
        } else {
            Log.d(TAG, "SmartspaceManager session already active — skipping duplicate setup")
        }

        // Step 4: ASI direct session — commute-card-only override.
        // Runs alongside the SmartspaceManager session when Shizuku is available.
        // It does NOT replace SmartspaceManager; it only pushes to the watch when it sees
        // featureType=3 (commute). When commute clears it triggers a SmartspaceManager refresh.
        if (ShizukuHelper.isReady() && asiSession == null) {
            Log.d(TAG, "Shizuku available — starting ASI commute watcher")
            val session = AsiSmartspaceSession(this, scope) { targets ->
                onAsiTargets(targets)
            }
            asiSession = session
            session.start()
        }
    }

    // -------------------------------------------------------------------------
    // SmartspaceManager session
    // -------------------------------------------------------------------------

    private fun setupSmartspaceManagerSession() {
        try {
            Class.forName("android.app.smartspace.SmartspaceManager")
        } catch (e: ClassNotFoundException) {
            fail("SmartspaceManager not available on this device (requires API 31)")
            return
        }

        val manager: Any = getSystemService("smartspace") ?: run {
            fail("getSystemService(smartspace) returned null — run: " +
                 "adb shell settings put global hidden_api_policy 1")
            return
        }

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

        val session = try {
            val managerClass = Class.forName("android.app.smartspace.SmartspaceManager")
            val configClass  = Class.forName("android.app.smartspace.SmartspaceConfig")
            managerClass.getMethod("createSmartspaceSession", configClass).invoke(manager, config)
        } catch (e: Exception) {
            fail("Failed to create SmartspaceSession: ${e.message}")
            return
        }
        smartspaceSession = session

        val sessionClass      = Class.forName("android.app.smartspace.SmartspaceSession")
        val listenerInterface = Class.forName(
            "android.app.smartspace.SmartspaceSession\$OnTargetsAvailableListener"
        )

        val listenerProxy = Proxy.newProxyInstance(
            listenerInterface.classLoader,
            arrayOf(listenerInterface)
        ) { proxy, method, args ->
            // SmartspaceSession stores the listener in an ArrayMap keyed by hashCode,
            // so the proxy MUST return a valid int — null causes an NPE on unboxing.
            when (method.name) {
                "onTargetsAvailable" -> {
                    if (args != null && !commuteActive) {
                        // Commute card from ASI is showing — don't let SmartspaceManager
                        // data overwrite it mid-commute.
                        @Suppress("UNCHECKED_CAST")
                        processSmartspacerTargets(args[0] as List<Any?>)
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
        Log.d(TAG, "SmartspaceManager session created and listening")
        AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
    }

    /** Asks the SmartspaceManager session to re-deliver its current targets. */
    private fun refreshSmartspaceManagerData() {
        val session = smartspaceSession ?: return
        try {
            session.javaClass.getMethod("requestSmartspaceUpdate").invoke(session)
        } catch (e: Exception) {
            Log.w(TAG, "refreshSmartspaceManagerData: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // ASI commute-card watcher callback
    // -------------------------------------------------------------------------

    /**
     * Called by AsiSmartspaceSession when ASI delivers a new target batch.
     * We look only for featureType=3 (commute). Everything else is handled by
     * the SmartspaceManager/Smartspacer session.
     */
    private fun onAsiTargets(targets: List<Any?>) {
        val commuteTarget = targets.firstOrNull { target ->
            target ?: return@firstOrNull false
            runCatching {
                target.javaClass.getMethod("getFeatureType").invoke(target) as? Int
            }.getOrNull() == FEATURE_COMMUTE
        }

        if (commuteTarget != null) {
            val templateData = runCatching {
                commuteTarget.javaClass.getMethod("getTemplateData").invoke(commuteTarget)
            }.getOrNull()

            val title = textFromAction(commuteTarget, "getHeaderAction")
                ?: textFromAction(commuteTarget, "getBaseAction")
                ?: textFromSubItem(templateData, "getPrimaryItem")

            if (title.isNullOrEmpty()) {
                Log.w(TAG, "ASI commute target has no extractable title — ignoring")
                return
            }

            val subtitle = textFromActionSubtitle(commuteTarget, "getHeaderAction")
                ?: textFromActionSubtitle(commuteTarget, "getBaseAction")
                ?: textFromSubItem(templateData, "getSubtitleItem")
                ?: ""

            val wasActive = commuteActive
            commuteActive = true
            Log.d(TAG, "ASI commute: '$title' / '$subtitle'${if (!wasActive) " (new)" else ""}")
            val event = CalendarEvent(title = title, subtitle = subtitle)
            AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
            pushToWatch(event)

        } else if (commuteActive) {
            // Commute card just disappeared — revert to Smartspacer data.
            Log.d(TAG, "ASI commute cleared — reverting to SmartspaceManager data")
            commuteActive = false
            refreshSmartspaceManagerData()
        }
    }

    // -------------------------------------------------------------------------
    // SmartspaceManager / Smartspacer target processing
    // -------------------------------------------------------------------------

    /**
     * Processes a target batch from the SmartspaceManager session (which is
     * Smartspacer's enriched output when Smartspacer is the active provider).
     *
     * Priority:
     *   1. FEATURE_CALENDAR (featureType=2) — with [Mirror] deduplication
     *   2. First target with any extractable title — whatever Smartspacer provides
     *      (e.g. weather+date from its built-in sources)
     */
    private fun processSmartspacerTargets(targets: List<Any?>) {
        Log.d(TAG, "processSmartspacerTargets: ${targets.size} targets")
        targets.forEachIndexed { i, target ->
            target ?: return@forEachIndexed
            val featureType  = runCatching { target.javaClass.getMethod("getFeatureType").invoke(target) }.getOrNull()
            val templateData = runCatching { target.javaClass.getMethod("getTemplateData").invoke(target) }.getOrNull()
            val templateType = templateData?.javaClass?.simpleName ?: "null"
            val hTitle = textFromAction(target, "getHeaderAction")
            val bTitle = textFromAction(target, "getBaseAction")
            Log.d(TAG, "Target[$i] featureType=$featureType templateData=$templateType " +
                    "hTitle='$hTitle' bTitle='$bTitle'")
        }

        data class Candidate(val title: String, val subtitle: String)

        // ── Calendar targets ──────────────────────────────────────────────────
        val calendarCandidates = targets.mapNotNull { target ->
            target ?: return@mapNotNull null
            val featureType = runCatching {
                target.javaClass.getMethod("getFeatureType").invoke(target) as? Int
            }.getOrNull()
            if (featureType != FEATURE_CALENDAR) return@mapNotNull null

            val templateData = runCatching {
                target.javaClass.getMethod("getTemplateData").invoke(target)
            }.getOrNull()

            val title    = textFromSubItem(templateData, "getPrimaryItem")  ?: return@mapNotNull null
            val subtitle = textFromSubItem(templateData, "getSubtitleItem") ?: ""
            Candidate(title, subtitle)
        }

        // Remove [Mirror] X when X also exists — keeps only the canonical copy.
        val deduped = if (calendarCandidates.isNotEmpty()) {
            val nonMirroredTitles = calendarCandidates
                .map { it.title }
                .filter { !it.startsWith("[Mirror]", ignoreCase = true) }
                .toSet()
            calendarCandidates.filter { c ->
                val stripped = c.title.removePrefix("[Mirror] ").removePrefix("[mirror] ")
                !c.title.startsWith("[Mirror]", ignoreCase = true) || stripped !in nonMirroredTitles
            }
        } else emptyList()

        val chosen: Candidate? = deduped.firstOrNull()

        if (chosen != null) {
            Log.d(TAG, "Calendar event: '${chosen.title}' / '${chosen.subtitle}'")
            val event = CalendarEvent(title = chosen.title, subtitle = chosen.subtitle)
            AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
            pushToWatch(event)
            return
        }

        // ── No calendar — use whatever Smartspacer provides ───────────────────
        // Skip placeholder targets (null content) and take the first one that
        // has any extractable title. This is Smartspacer's enriched data —
        // weather+date, clock targets, etc.
        //
        // Extraction priority for Smartspacer's combined featureType=0 target:
        //   title:    headerAction.title (e.g. "21°C") — the primary data value
        //   subtitle: primaryItem / baseAction.title   (e.g. "Mon, Jun 1") — the label/context
        // Fallbacks: subtitleItem, action subtitles.
        val fallbackEvent = targets.asSequence().filterNotNull().mapNotNull { target ->
            val templateData = runCatching {
                target.javaClass.getMethod("getTemplateData").invoke(target)
            }.getOrNull()

            // headerAction carries the main data value (temperature, event title, etc.)
            val title = textFromAction(target, "getHeaderAction")
                ?: textFromSubItem(templateData, "getPrimaryItem")
                ?: textFromAction(target, "getBaseAction")
            if (title.isNullOrEmpty()) return@mapNotNull null

            // Subtitle: prefer primaryItem / baseAction (contextual labels like date/location),
            // then fall back to templateData subtitle / action subtitles.
            // Always suppress if it would duplicate the title.
            val rawSubtitle = listOfNotNull(
                textFromSubItem(templateData, "getPrimaryItem"),
                textFromAction(target, "getBaseAction"),
                textFromSubItem(templateData, "getSubtitleItem"),
                textFromActionSubtitle(target, "getHeaderAction"),
                textFromActionSubtitle(target, "getBaseAction"),
            ).firstOrNull { it != title } ?: ""
            Candidate(title, rawSubtitle)
        }.firstOrNull()

        if (fallbackEvent == null) {
            Log.w(TAG, "No usable Smartspacer target — clearing")
            AppState.updateBridgeStatus(BridgeStatus.Running(event = null))
            pushToWatch(null)
            return
        }

        Log.d(TAG, "Smartspacer fallback: '${fallbackEvent.title}' / '${fallbackEvent.subtitle}'")
        val event = CalendarEvent(title = fallbackEvent.title, subtitle = fallbackEvent.subtitle)
        AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
        pushToWatch(event)
    }

    // -------------------------------------------------------------------------
    // Shared text-extraction helpers (used by both paths)
    // -------------------------------------------------------------------------

    /** Extracts text from templateData.subItemGetter().getText().getText(). */
    private fun textFromSubItem(templateData: Any?, subItemGetter: String): String? = runCatching {
        val subItem = templateData?.javaClass?.getMethod(subItemGetter)?.invoke(templateData)
            ?: return@runCatching null
        val textObj = subItem.javaClass.getMethod("getText").invoke(subItem)
            ?: return@runCatching null
        textObj.javaClass.getMethod("getText").invoke(textObj)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Extracts SmartspaceAction.getTitle() from a named action getter on the target. */
    private fun textFromAction(target: Any, actionGetter: String): String? = runCatching {
        val action = target.javaClass.getMethod(actionGetter).invoke(target)
            ?: return@runCatching null
        action.javaClass.getMethod("getTitle").invoke(action)?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Extracts SmartspaceAction.getSubtitle() from a named action getter on the target. */
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

    private fun destroySmartspaceManagerSession() {
        val session = smartspaceSession ?: return
        try {
            session.javaClass.getMethod("destroy").invoke(session)
        } catch (e: Exception) {
            Log.w(TAG, "destroySmartspaceManagerSession: ${e.message}")
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
