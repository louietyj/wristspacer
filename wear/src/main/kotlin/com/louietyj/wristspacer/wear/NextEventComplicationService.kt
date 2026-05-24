package com.louietyj.wristspacer.wear

import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.KEY_SUBTITLE
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.KEY_TIMESTAMP
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.KEY_TITLE
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.PREFS_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Provides a complication showing the next work calendar event.
 *
 * Supported types:
 *   SHORT_TEXT  — event title only (fits small corner/side slots)
 *   LONG_TEXT   — subtitle • title (fits wide slots)
 *
 * Normal updates are push-driven from the phone (minute-level Smartspace ticks).
 * UPDATE_PERIOD_SECONDS=300 acts as a watchdog: if the phone bridge dies, the
 * 5-min system tick triggers onComplicationRequest, which detects stale data
 * (last push > STALE_THRESHOLD) and sends /request_update to wake the bridge.
 */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Called when this complication is added to a watch face slot.
     * Send a message to the phone so it pushes immediately — without this the
     * slot would be blank until the next Smartspace minute-tick.
     */
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        Log.d(TAG, "Complication activated ($complicationInstanceId) — requesting immediate update")
        scope.launch { sendRequestUpdate() }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortText("Standup")
            ComplicationType.LONG_TEXT  -> longText("3:00 PM", "Weekly Standup")
            else                        -> null
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs    = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val title    = prefs.getString(KEY_TITLE, "") ?: ""
        val subtitle = prefs.getString(KEY_SUBTITLE, "") ?: ""
        val lastTs   = prefs.getLong(KEY_TIMESTAMP, 0L)

        // Watchdog: if the last push is stale the bridge may be dead — ping the phone.
        // This runs on every 5-min system tick, covering the crash/kill recovery case.
        val ageMs = System.currentTimeMillis() - lastTs
        if (ageMs > STALE_THRESHOLD_MS) {
            Log.d(TAG, "Data stale (${ageMs / 1000}s) — sending request_update to phone")
            scope.launch { sendRequestUpdate() }
        }

        Log.d(TAG, "onComplicationRequest type=${request.complicationType} title='$title'")

        if (title.isEmpty()) {
            return when (request.complicationType) {
                ComplicationType.SHORT_TEXT -> noData()
                ComplicationType.LONG_TEXT  -> noData()
                else                        -> null
            }
        }

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> shortText(title)
            ComplicationType.LONG_TEXT  -> longText(subtitle.ifEmpty { title }, title)
            else                        -> null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sendRequestUpdate() {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(this).connectedNodes
            )
            nodes.forEach { node ->
                Tasks.await(
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, PATH_REQUEST_UPDATE, ByteArray(0))
                )
                Log.d(TAG, "Sent request_update to ${node.displayName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send request_update: ${e.message}")
        }
    }

    private fun shortText(title: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(title).build(),
            contentDescription = PlainComplicationText.Builder("Next event: $title").build()
        ).build()

    private fun longText(subtitle: String, title: String): LongTextComplicationData =
        LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(title).build(),
            contentDescription = PlainComplicationText.Builder("Next event: $title").build()
        ).setTitle(
            PlainComplicationText.Builder(subtitle).build()
        ).build()

    private fun noData(): NoDataComplicationData = NoDataComplicationData()

    companion object {
        private const val TAG = "NextEventComplication"
        private const val PATH_REQUEST_UPDATE = "/request_update"
        /** If the last push is older than this, the bridge is considered dead and pinged. */
        private const val STALE_THRESHOLD_MS = 3 * 60 * 1000L  // 3 minutes
    }
}
