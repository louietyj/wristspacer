package com.louietyj.wristspacer.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CalendarEvent(
    val title: String,
    val subtitle: String,
    val receivedAtMs: Long = System.currentTimeMillis(),
)

sealed interface BridgeStatus {
    data object Stopped : BridgeStatus
    data object Starting : BridgeStatus
    data class Running(val event: CalendarEvent?) : BridgeStatus
    data class Error(val message: String) : BridgeStatus
}

/**
 * Singleton state bus shared between SmartspaceBridgeService and MainActivity.
 * Both run in the same process so a simple StateFlow works fine for MVP.
 */
object AppState {
    private val _bridgeStatus = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
    val bridgeStatus: StateFlow<BridgeStatus> = _bridgeStatus.asStateFlow()

    private val _shizukuReady = MutableStateFlow(false)
    val shizukuReady: StateFlow<Boolean> = _shizukuReady.asStateFlow()

    fun updateBridgeStatus(status: BridgeStatus) {
        _bridgeStatus.value = status
    }

    fun updateShizukuReady(ready: Boolean) {
        _shizukuReady.value = ready
    }
}

/** Keys used in the Wearable DataLayer DataMap and MessageClient. */
object DataKeys {
    const val PATH = "/next_event"
    const val KEY_TITLE = "title"
    const val KEY_SUBTITLE = "subtitle"
    const val KEY_TIMESTAMP = "timestamp"

    /** MessageClient path the watch sends to request an immediate push. */
    const val PATH_REQUEST_UPDATE = "/request_update"
}
