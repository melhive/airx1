package com.melhive.airx1.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.service.HandTrackingService

@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var camera by remember { mutableStateOf(Permissions.camera(ctx)) }
    var notifications by remember { mutableStateOf(Permissions.notifications(ctx)) }
    var a11yEnabled by remember { mutableStateOf(Permissions.accessibilityEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        camera = Permissions.camera(ctx)
        notifications = Permissions.notifications(ctx)
        a11yEnabled = Permissions.accessibilityEnabled(ctx)
        onPauseOrDispose { }
    }
    val a11yConnected by GestureBus.accessibilityConnected.collectAsStateWithLifecycle()
    val running by GestureBus.engineRunning.collectAsStateWithLifecycle()
    val snap by GestureBus.snapshot.collectAsStateWithLifecycle()
    val fps by GestureBus.fps.collectAsStateWithLifecycle()

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your Hands Are the Interface.", style = MaterialTheme.typography.titleMedium)

        StatusRow("Camera permission", camera, if (camera) null else "Grant") {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusRow("Notifications (for the Stop button)", notifications, if (notifications) null else "Grant") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val a11yOk = a11yEnabled || a11yConnected
        StatusRow("Accessibility service", a11yOk, if (a11yOk) null else "Open settings") {
            Permissions.openAccessibilitySettings(ctx)
        }
        if (!a11yOk) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Toggle greyed out?", fontWeight = FontWeight.Bold)
                    Text(
                        "Android blocks accessibility for sideloaded apps until you allow it: " +
                            "App info → ⋮ (top right) → Allow restricted settings. Then come back and enable AirX1 " +
                            "under Accessibility → Installed apps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { Permissions.openAppInfo(ctx) }) { Text("Open App info") }
                }
            }
        }
        StatusRow(
            "Engine",
            running,
            null,
            detail = if (running) {
                "%.0f fps · hand %s · %s".format(fps, if (snap.handPresent) "seen" else "not seen", if (snap.armed) "ARMED" else "disarmed")
            } else "stopped",
        ) {}

        Button(
            onClick = { if (running) HandTrackingService.stop(ctx) else HandTrackingService.start(ctx) },
            enabled = camera,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (running) ButtonDefaults.buttonColors(containerColor = AirBad) else ButtonDefaults.buttonColors(),
        ) { Text(if (running) "Stop AirX1" else "Start AirX1", style = MaterialTheme.typography.titleMedium) }

        if (running) {
            Text(
                if (snap.armed) "Armed: pinch to tap. Make a fist for 1 s to disarm."
                else "Disarmed. Make a fist and hold it for 1 s to arm.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { onNavigate(Screen.CALIBRATE) }, Modifier.weight(1f)) { Text("Calibrate") }
            FilledTonalButton(onClick = { onNavigate(Screen.DEBUG) }, Modifier.weight(1f)) { Text("Debug view") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onNavigate(Screen.HOWTO) }, Modifier.weight(1f)) { Text("How to use") }
            OutlinedButton(onClick = { onNavigate(Screen.SETTINGS) }, Modifier.weight(1f)) { Text("Settings") }
            OutlinedButton(onClick = { onNavigate(Screen.LOGS) }, Modifier.weight(1f)) { Text("Log") }
        }
        Text(
            "Private by design: camera frames are processed on this phone only. AirX1 has no internet permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String?, detail: String? = null, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) AirGood else AirBad))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
