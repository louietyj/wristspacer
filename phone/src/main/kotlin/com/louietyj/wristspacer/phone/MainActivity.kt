package com.louietyj.wristspacer.phone

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            AppState.updateShizukuReady(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky {
            AppState.updateShizukuReady(ShizukuHelper.isReady())
        }
        Shizuku.addBinderDeadListener {
            AppState.updateShizukuReady(false)
        }
        AppState.updateShizukuReady(ShizukuHelper.isReady())

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StatusScreen(
                        onStartClick = { SmartspaceBridgeService.start(this) },
                        onStopClick  = { SmartspaceBridgeService.stop(this) },
                        onGrantShizuku = { ShizukuHelper.requestPermission() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}

@Composable
fun StatusScreen(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onGrantShizuku: () -> Unit,
) {
    val status by AppState.bridgeStatus.collectAsStateWithLifecycle()
    val shizukuReady by AppState.shizukuReady.collectAsStateWithLifecycle()

    val isRunning = status is BridgeStatus.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Wristspacer",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        HorizontalDivider()

        StatusRow(label = "Shizuku", ok = shizukuReady)
        StatusRow(
            label = "Bridge",
            ok = isRunning,
            detail = when (val s = status) {
                is BridgeStatus.Error   -> s.message
                is BridgeStatus.Starting -> "starting…"
                else                    -> null
            }
        )

        // Last event
        val event: CalendarEvent? = (status as? BridgeStatus.Running)?.event
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "Event",
                fontSize = 14.sp,
                modifier = Modifier.width(80.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (event != null) {
                Column {
                    Text(text = event.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (event.subtitle.isNotEmpty()) {
                        Text(
                            text = event.subtitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "none",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        HorizontalDivider()

        // Action buttons
        if (!shizukuReady) {
            Button(onClick = onGrantShizuku, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Shizuku Permission")
            }
        } else if (!isRunning) {
            Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
                Text("Start Bridge")
            }
        } else {
            OutlinedButton(onClick = onStopClick, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Bridge")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (ok) "●  OK" else "●  offline",
            fontSize = 14.sp,
            color = if (ok) Color(0xFF4CAF50) else Color(0xFFFF5722)
        )
        if (detail != null) {
            Text(
                text = "— $detail",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
