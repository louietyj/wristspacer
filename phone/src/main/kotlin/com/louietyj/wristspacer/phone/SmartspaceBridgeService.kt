package com.louietyj.wristspacer.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
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
        if (smartspaceSession != null) return

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

    private fun processTargets(targets: List<Any?>) {

        // Collect all FEATURE_CALENDAR targets with their extracted titles
        data class Candidate(val target: Any, val title: String, val subtitle: String)

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
            Candidate(target, title, subtitle)
        }

        // Remove [Mirror] X when X also exists — then pick the first remaining candidate.
        val dedupedCalendar = if (calendarCandidates.isNotEmpty()) {
            val nonMirroredTitles = calendarCandidates
                .map { it.title }
                .filter { !it.startsWith("[Mirror]", ignoreCase = true) }
                .toSet()
            calendarCandidates.filter { c ->
                val stripped = c.title.removePrefix("[Mirror] ").removePrefix("[mirror] ")
                // Keep if it's not a mirror, OR if there's no non-mirrored twin
                !c.title.startsWith("[Mirror]", ignoreCase = true) || stripped !in nonMirroredTitles
            }
        } else emptyList()

        val chosen: Candidate? = dedupedCalendar.firstOrNull()

        if (chosen == null) {
            // No calendar targets — iterate all targets until one yields an extractable title.
            // Some targets (e.g. featureType=72 placeholders) have null templateData and null
            // action titles; skipping them lets us reach the real content further down the list.
            val fallbackEvent = targets.asSequence().filterNotNull().mapNotNull { target ->
                val templateData = runCatching {
                    target.javaClass.getMethod("getTemplateData").invoke(target)
                }.getOrNull()
                // Smartspacer uses two distinct rendering paths that must not be mixed:
                //   Template path (templateData != null): primaryItem = main line,
                //     subtitleItem = second line. For weather+date: primary=date, subtitle=temp.
                //   Feature path (templateData == null): headerAction.title = main line,
                //     headerAction.subtitle = second line. Used by commute, image cards, etc.
                // Mixing paths causes duplicates (e.g. headerAction.title carries temperature
                // for weather cards, not the date, so falling through gives "16°C / 16°C").
                val (title, subtitle) = if (templateData != null) {
                    // Template path: primaryItem / subtitleItem
                    val t = textFromSubItem(templateData, "getPrimaryItem") ?: return@mapNotNull null
                    val s = textFromSubItem(templateData, "getSubtitleItem") ?: ""
                    Pair(t, s)
                } else {
                    val headerTitle = textFromAction(target, "getHeaderAction")
                    if (headerTitle != null) {
                        // Feature path: headerAction has plain text
                        val s = textFromActionSubtitle(target, "getHeaderAction") ?: ""
                        Pair(headerTitle, s)
                    } else {
                        // DrawInstructions path: text is baked into RemoteViews binary (commute card)
                        val rv = runCatching {
                            target.javaClass.getMethod("getRemoteViews").invoke(target) as? RemoteViews
                        }.getOrNull() ?: return@mapNotNull null
                        val texts = extractDrawInstructionsTexts(rv)
                        if (texts.isEmpty()) return@mapNotNull null
                        Pair(texts[0], texts.getOrElse(1) { "" })
                    }
                }
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

        Log.d(TAG, "Calendar event: '${chosen.title}' / '${chosen.subtitle}'")
        val event = CalendarEvent(title = chosen.title, subtitle = chosen.subtitle)
        AppState.updateBridgeStatus(BridgeStatus.Running(event = event))
        pushToWatch(event)
    }

    // -------------------------------------------------------------------------
    // DrawInstructions text extraction
    // -------------------------------------------------------------------------

    /**
     * Walks a RemoteViews action tree looking for SetDrawInstructionAction, which wraps a
     * RemoteCompose binary document. Text strings are stored as TextData operations (opcode 102)
     * inside that document and are accessible via reflection on the parsed operation list.
     *
     * Uses identity-hash cycle detection + depth cap to avoid infinite loops in nested trees.
     */
    private fun extractDrawInstructionsTexts(rv: RemoteViews): List<String> {
        val out = mutableListOf<String>()
        walkForDrawInstructions(rv, HashSet(), out, 0)
        return out
    }

    private fun walkForDrawInstructions(
        rv: RemoteViews,
        visited: HashSet<Int>,
        out: MutableList<String>,
        depth: Int
    ) {
        if (depth > 30 || !visited.add(System.identityHashCode(rv))) return
        val actions = runCatching {
            rv.javaClass.getDeclaredField("mActions").also { it.isAccessible = true }.get(rv) as? List<*>
        }.getOrNull() ?: return

        for (action in actions) {
            action ?: continue
            when (action.javaClass.simpleName) {
                "ReflectionAction" -> {
                    // Text is stored in BaseReflectionAction.mMethodName / ReflectionAction.mValue.
                    // Both fields may live in a superclass of the concrete action class.
                    val methodName = findField(action, "mMethodName") as? String
                    if (methodName == "setText") {
                        val value = findField(action, "mValue")?.toString()
                        if (!value.isNullOrBlank()) out.add(value)
                    }
                }
                "ViewGroupActionAdd" -> {
                    // Find nested RemoteViews — field is "nestedViews" in AOSP; scan all fields
                    // in case it's been renamed in the OEM build.
                    var nested: RemoteViews? = runCatching {
                        action.javaClass.getDeclaredField("nestedViews")
                            .also { it.isAccessible = true }.get(action) as? RemoteViews
                    }.getOrNull()
                    if (nested == null) {
                        outer@ for (cls in generateSequence(action.javaClass) { it.superclass }) {
                            for (f in cls.declaredFields) {
                                val v = runCatching { f.also { it.isAccessible = true }.get(action) }.getOrNull()
                                if (v is RemoteViews) { nested = v; break@outer }
                            }
                        }
                    }
                    nested?.let { walkForDrawInstructions(it, visited, out, depth + 1) }
                }
            }
        }
    }

    /** Walks [obj]'s class hierarchy (including superclasses) to find and read a declared field. */
    private fun findField(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                return cls.getDeclaredField(name).also { it.isAccessible = true }.get(obj)
            } catch (_: NoSuchFieldException) {}
            cls = cls.superclass
        }
        return null
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SmartspaceBridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmartspaceBridgeService::class.java))
        }
    }
}
