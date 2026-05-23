package com.louietyj.wristspacer.wear

import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives DataItem updates from the phone's SmartspaceBridgeService.
 * On each update, stores the latest event in SharedPreferences and
 * triggers a complication refresh.
 */
class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == PATH_NEXT_EVENT
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val title    = dataMap.getString(KEY_TITLE, "") ?: ""
                val subtitle = dataMap.getString(KEY_SUBTITLE, "") ?: ""
                val ts       = dataMap.getLong(KEY_TIMESTAMP, 0L)
                Log.d(TAG, "Received event: title='$title' sub='$subtitle' ts=$ts")
                storeEvent(title, subtitle, ts)
                requestComplicationUpdate()
            }
        }
        events.release()
    }

    private fun storeEvent(title: String, subtitle: String, timestamp: Long) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_SUBTITLE, subtitle)
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
    }

    private fun requestComplicationUpdate() {
        ComplicationDataSourceUpdateRequester
            .create(
                context = this,
                complicationDataSourceComponent = android.content.ComponentName(
                    this, NextEventComplicationService::class.java
                )
            )
            .requestUpdateAll()
    }

    companion object {
        private const val TAG = "PhoneDataListener"
        const val PREFS_NAME = "wristspacer_event"
        const val PATH_NEXT_EVENT = "/next_event"
        const val KEY_TITLE = "title"
        const val KEY_SUBTITLE = "subtitle"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
