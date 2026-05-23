package com.louietyj.wristspacer.wear

import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.KEY_SUBTITLE
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.KEY_TITLE
import com.louietyj.wristspacer.wear.PhoneDataListenerService.Companion.PREFS_NAME

/**
 * Provides a complication showing the next work calendar event.
 *
 * Supported types:
 *   SHORT_TEXT  — event title only (fits small corner/side slots)
 *   LONG_TEXT   — subtitle • title (fits wide slots)
 *
 * Updates are push-driven from the phone (UPDATE_PERIOD_SECONDS=0).
 * The DataLayer listener calls requestUpdateAll() whenever new data arrives.
 */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortText("Standup")
            ComplicationType.LONG_TEXT  -> longText("3:00 PM", "Weekly Standup")
            else                        -> null
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val title    = prefs.getString(KEY_TITLE, "") ?: ""
        val subtitle = prefs.getString(KEY_SUBTITLE, "") ?: ""

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
    }
}
