package com.melhive.airx1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.data.AppSettings
import com.melhive.airx1.data.SettingsRepository
import com.melhive.airx1.service.HandTrackingService

private val HAND_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
)

/** Live camera view with the detected hand skeleton and the numbers the engine sees. Nothing is saved. */
@Composable
fun DebugScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        GestureBus.previewViewers.incrementAndGet()
        onDispose {
            if (GestureBus.previewViewers.decrementAndGet() <= 0) GestureBus.preview.value = null
        }
    }
    val running by GestureBus.engineRunning.collectAsStateWithLifecycle()
    val bmp by GestureBus.preview.collectAsStateWithLifecycle()
    val snap by GestureBus.snapshot.collectAsStateWithLifecycle()
    val fps by GestureBus.fps.collectAsStateWithLifecycle()
    val delegate by GestureBus.delegate.collectAsStateWithLifecycle()
    val repo = remember { SettingsRepository(ctx) }
    val settings by repo.flow.collectAsStateWithLifecycle(initialValue = AppSettings())

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!running) {
            Button(onClick = { HandTrackingService.start(ctx) }) { Text("Start AirX1 to see the camera") }
        }
        val image = bmp
        val aspect = image?.let { it.width.toFloat() / it.height } ?: 0.75f
        Box(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).aspectRatio(aspect).background(Color.Black)) {
            if (image != null) {
                Image(image.asImageBitmap(), "Camera preview", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Active region (the part of the image mapped to the whole screen).
                drawRect(
                    Color(0xAAFFFFFF),
                    topLeft = Offset(settings.regionLeft * w, settings.regionTop * h),
                    size = Size((settings.regionRight - settings.regionLeft) * w, (settings.regionBottom - settings.regionTop) * h),
                    style = Stroke(width = 2f),
                )
                val lm = snap.landmarks
                if (lm.size >= 21) {
                    for ((a, b) in HAND_CONNECTIONS) {
                        drawLine(AirAccent, Offset(lm[a].x * w, lm[a].y * h), Offset(lm[b].x * w, lm[b].y * h), strokeWidth = 4f)
                    }
                    for (p in lm) drawCircle(Color.White, 5f, Offset(p.x * w, p.y * h))
                    drawCircle(Color.Yellow, 10f, Offset(snap.rawX * w, snap.rawY * h))
                }
            }
        }

        PinchMeter(snap.pinchRatio, settings.pinchOn, settings.pinchOff)

        val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        Text("State     ${snap.state}  (armed=${snap.armed})", style = mono)
        Text("Hand      ${if (snap.handPresent) "${snap.handedness}" else "—"}", style = mono)
        Text("Pose      ${snap.pose}", style = mono)
        Text("Pinch     ${if (snap.pinchRatio.isNaN()) "—" else "%.2f".format(snap.pinchRatio)}  (closes < %.2f, opens > %.2f)".format(settings.pinchOn, settings.pinchOff), style = mono)
        Text("Cursor    %.2f, %.2f".format(snap.cursorX, snap.cursorY), style = mono)
        Text("Camera    %.1f fps  ·  %s".format(fps, delegate.ifEmpty { "—" }), style = mono)
        Text(
            "Yellow dot = cursor anchor. White box = active area. Preview frames stay in memory only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinchMeter(ratio: Float, on: Float, off: Float) {
    val max = 1.5f
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val w = size.width
        val h = size.height
        drawRect(Color(0x33FFFFFF), size = size)
        if (!ratio.isNaN()) {
            val fill = (ratio / max).coerceIn(0f, 1f) * w
            drawRect(if (ratio < on) AirGood else AirAccent, size = Size(fill, h))
        }
        drawLine(AirBad, Offset(on / max * w, 0f), Offset(on / max * w, h), strokeWidth = 4f)
        drawLine(Color.White, Offset(off / max * w, 0f), Offset(off / max * w, h), strokeWidth = 3f)
    }
}
