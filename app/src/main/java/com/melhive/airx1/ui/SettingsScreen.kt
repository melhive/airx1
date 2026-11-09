package com.melhive.airx1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.airx1.data.AppSettings
import com.melhive.airx1.data.SettingsRepository
import com.melhive.airx1.engine.CursorAnchor
import com.melhive.airx1.engine.HandPreference
import com.melhive.airx1.engine.InputMode
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

/** All settings apply live to the running engine. */
@Composable
fun SettingsScreen(onCalibrate: () -> Unit, onFavorites: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx) }
    val scope = rememberCoroutineScope()
    val s by repo.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    fun save(t: (AppSettings) -> AppSettings) = scope.launch { repo.update(t) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (s.calibrated) "Calibrated for your hand. Fine-tune below." else "Not calibrated yet. Calibration sets these for you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) { Text("Run calibration") }
        HorizontalDivider()

        Text("Mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.mode == InputMode.CURSOR, onClick = { save { it.copy(mode = InputMode.CURSOR) } },
                label = { Text("Cursor (aim + pinch)") })
            FilterChip(selected = s.mode == InputMode.SCROLL, onClick = { save { it.copy(mode = InputMode.SCROLL) } },
                label = { Text("Scroll (feeds)") })
        }
        SwitchRow("Smart Snap", "Cursor locks onto buttons and icons; taps hit their center.", s.smartSnap) { v -> save { it.copy(smartSnap = v) } }
        SwitchRow("Fingertip dots", "Show your five fingertips as small dots around the cursor.", s.fingertipDots) { v -> save { it.copy(fingertipDots = v) } }
        SwitchRow("AirKeys when typing", "Open the big-key AirX1 keyboard automatically when a keyboard appears (while armed).", s.airKeysAuto) { v -> save { it.copy(airKeysAuto = v) } }
        SwitchRow("Vibration", "Feel taps, Back/Home and arming.", s.haptics) { v -> save { it.copy(haptics = v) } }
        SwitchRow("Dwell click", "Hold the cursor still to tap (for when pinching is hard).", s.dwellEnabled) { v -> save { it.copy(dwellEnabled = v) } }
        if (s.dwellEnabled) {
            SettingSlider("Dwell time", s.dwellMs / 1000f, 0.6f..2.0f, "%.1f s", "How long to hold still before it taps.") { v ->
                save { it.copy(dwellMs = v * 1000f) }
            }
        }
        SettingSlider("Scroll distance", s.scrollSpeed, 0.5f..2f, "%.1f×", "Scroll mode: how far each scroll step moves.") { v ->
            save { it.copy(scrollSpeed = v) }
        }
        OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.favorites.isEmpty()) "Choose Quick Launcher apps" else "Quick Launcher apps (${s.favorites.size})")
        }
        HorizontalDivider()

        SettingSlider("Sensitivity", s.sensitivity, 0.5f..2.5f, "%.2f×",
            "Higher = less hand movement to cross the screen.") { v -> save { it.copy(sensitivity = v) } }
        SettingSlider("Smoothing", s.smoothing, 0f..1f, "%.2f",
            "Higher = steadier cursor but more lag.") { v -> save { it.copy(smoothing = v) } }
        SettingSlider("Active area size", (s.regionRight - s.regionLeft), 0.3f..0.9f, "%.2f",
            "Part of the camera view that maps to the whole screen (re-centers it).") { v ->
            save { it.copy(regionLeft = 0.5f - v / 2, regionRight = 0.5f + v / 2, regionTop = 0.5f - v / 2, regionBottom = 0.5f + v / 2) }
        }
        SettingSlider("Pinch closes below", s.pinchOn, 0.08f..0.7f, "%.2f",
            "Thumb–index distance ÷ hand size. Lower = must pinch tighter.") { v ->
            save { it.copy(pinchOn = v, pinchOff = maxOf(it.pinchOff, v + 0.05f)) }
        }
        SettingSlider("Pinch opens above", s.pinchOff, 0.12f..0.9f, "%.2f",
            "Must be above the close value; the gap prevents flicker.") { v ->
            save { it.copy(pinchOff = maxOf(v, it.pinchOn + 0.05f)) }
        }

        Text("Hand", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HandPreference.entries.forEach { h ->
                FilterChip(selected = s.hand == h, onClick = { save { it.copy(hand = h) } },
                    label = { Text(h.name.lowercase().replaceFirstChar { c -> c.uppercase() }) })
            }
        }
        Text("Cursor follows", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.anchor == CursorAnchor.KNUCKLE, onClick = { save { it.copy(anchor = CursorAnchor.KNUCKLE) } },
                label = { Text("Knuckle (steady taps)") })
            FilterChip(selected = s.anchor == CursorAnchor.FINGERTIP, onClick = { save { it.copy(anchor = CursorAnchor.FINGERTIP) } },
                label = { Text("Fingertip") })
        }
        HorizontalDivider()
        OutlinedButton(onClick = { scope.launch { repo.reset() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset all settings")
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    help: String,
    onCommit: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format.format(local), style = MaterialTheme.typography.titleSmall)
        }
        Slider(value = local.coerceIn(range), onValueChange = { local = it }, valueRange = range,
            onValueChangeFinished = { onCommit(local) })
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
