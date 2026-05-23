package com.louietyj.wristspacer.phone

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives "/request_update" messages from the watch and immediately pushes
 * the last known event back via the Data Layer.
 *
 * This fires when the complication is first added to a watch face, so the
 * slot doesn't sit blank until the next Smartspace minute-tick.
 */
class PhoneWearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DataKeys.PATH_REQUEST_UPDATE) return
        Log.d(TAG, "Watch requested update (node=${event.sourceNodeId})")

        val currentEvent = (AppState.bridgeStatus.value as? BridgeStatus.Running)?.event

        try {
            val request = PutDataMapRequest.create(DataKeys.PATH).apply {
                dataMap.putString(DataKeys.KEY_TITLE,    currentEvent?.title    ?: "")
                dataMap.putString(DataKeys.KEY_SUBTITLE, currentEvent?.subtitle ?: "")
                dataMap.putLong(DataKeys.KEY_TIMESTAMP,  System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            // onMessageReceived runs on a background thread — Tasks.await() is safe here
            Tasks.await(Wearable.getDataClient(this).putDataItem(request))
            Log.d(TAG, "Pushed on-demand event: '${currentEvent?.title}'")
        } catch (e: Exception) {
            Log.e(TAG, "On-demand push failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PhoneWearListener"
    }
}
