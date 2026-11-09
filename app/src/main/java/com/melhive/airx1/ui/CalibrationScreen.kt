package com.melhive.airx1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.data.SettingsRepository
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.log.LogBuffer
import com.melhive.airx1.service.HandTrackingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Result of a calibration run (also used by unit-free logic below). */
data class Calibration(
    val pinchOn: Float,
    val pinchOff: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object Calibrator {
    private fun percentile(sorted: List<Float>, p: Float): Float =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    /** Returns the calibration, or an error message explaining what to fix. */
    fun compute(relaxed: List<Float>, pinched: List<Float>, xs: List<Float>, ys: List<Float>): Result<Calibration> {
        if (relaxed.size < 15 || pinched.size < 15 || xs.size < 30) {
            return Result.failure(IllegalStateException("Your hand wasn't seen long enough. Check the lighting and keep your hand in view."))
        }
        val o = percentile(relaxed.sorted(), 0.5f)
        val p = percentile(pinched.sorted(), 0.5f)
        if (o - p < 0.15f) {
            return Result.failure(IllegalStateException("Pinched and relaxed looked too similar. Pinch firmly, and keep your thumb relaxed when pointing."))
        }
        val sx = xs.sorted()
        val sy = ys.sorted()
        var l = percentile(sx, 0.05f)
        var r = percentile(sx, 0.95f)
        var t = percentile(sy, 0.05f)
        var b = percentile(sy, 0.95f)
        if (r - l < 0.1f || b - t < 0.1f) {
            return Result.failure(IllegalStateException("Move your hand further towards each corner."))
        }
        // Shrink slightly so the screen edges are reachable without stretching.
        val ix = (r - l) * 0.05f
        val iy = (b - t) * 0.05f
        l += ix; r -= ix; t += iy; b -= iy
        return Result.success(Calibration(p + 0.35f * (o - p), p + 0.55f * (o - p), l, t, r, b))
    }
}

private enum class Phase { INTRO, RELAXED, PINCHED, SWEEP, DONE, FAILED }

@Composable
fun CalibrationScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx) }
    val scope = rememberCoroutineScope()
    val running by GestureBus.engineRunning.collectAsStateWithLifecycle()
    val snap by GestureBus.snapshot.collectAsStateWithLifecycle()

    // Calibration must never click things: suppress all input while this screen is open.
    DisposableEffect(Unit) {
        GestureBus.inputSuppressed.value = true
        onDispose { GestureBus.inputSuppressed.value = false }
    }

    var phase by remember { mutableStateOf(Phase.INTRO) }
    var countdown by remember { mutableStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    suspend fun capture(durationMs: Long): List<EngineSnapshot> {
        for (i in 3 downTo 1) { countdown = i; delay(1000) }
        countdown = 0
        val out = mutableListOf<EngineSnapshot>()
        val start = System.currentTimeMillis()
        withTimeoutOrNull(durationMs) {
            GestureBus.snapshot.collect {
                if (it.handPresent) out += it
                progress = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
            }
        }
        progress = 0f
        return out
    }

    fun run() {
        job?.cancel()
        job = scope.launch {
            if (!GestureBus.engineRunning.value) {
                HandTrackingService.start(ctx)
                delay(1500)
            }
            phase = Phase.RELAXED
            val relaxed = capture(2500).map { it.pinchRatio }
            phase = Phase.PINCHED
            val pinched = capture(2500).map { it.pinchRatio }
            phase = Phase.SWEEP
            val sweep = capture(6000)
            Calibrator.compute(relaxed, pinched, sweep.map { it.rawX }, sweep.map { it.rawY })
                .onSuccess { c ->
                    repo.update {
                        it.copy(
                            pinchOn = c.pinchOn, pinchOff = c.pinchOff,
                            regionLeft = c.left, regionTop = c.top, regionRight = c.right, regionBottom = c.bottom,
                            sensitivity = 1f, calibrated = true,
                        )
                    }
                    LogBuffer.i("Calibrated: pinch %.2f/%.2f, area x %.2f–%.2f y %.2f–%.2f".format(c.pinchOn, c.pinchOff, c.left, c.right, c.top, c.bottom))
                    message = "Saved. Pinch closes below %.2f and opens above %.2f.".format(c.pinchOn, c.pinchOff)
                    phase = Phase.DONE
                }
                .onFailure {
                    message = it.message ?: "Calibration failed."
                    LogBuffer.w("Calibration failed: $message")
                    phase = Phase.FAILED
                }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val (title, body) = when (phase) {
            Phase.INTRO -> "About 15 seconds" to
                "Prop the phone up so the front camera sees your hand at a comfortable distance (40–70 cm). " +
                "You'll do three things: point with a relaxed thumb, pinch, then move your hand around."
            Phase.RELAXED -> "1 · Point" to "Point with your index finger, thumb relaxed, as if moving the cursor."
            Phase.PINCHED -> "2 · Pinch" to "Touch your thumb tip to your index fingertip and hold."
            Phase.SWEEP -> "3 · Move around" to "Keep pointing and slowly trace the four corners of a comfortable area, twice."
            Phase.DONE -> "Done" to message
            Phase.FAILED -> "Let's try again" to message
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyLarge)

        if (phase in listOf(Phase.RELAXED, Phase.PINCHED, Phase.SWEEP)) {
            if (countdown > 0) {
                Text("Get ready… $countdown", style = MaterialTheme.typography.displaySmall)
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (snap.handPresent) "Hand detected ✓" else "No hand detected",
                color = if (snap.handPresent) AirGood else AirBad,
            )
        }

        when (phase) {
            Phase.INTRO, Phase.FAILED -> Button(onClick = { run() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (phase == Phase.INTRO) "Start calibration" else "Try again")
            }
            Phase.DONE -> {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                OutlinedButton(onClick = { run() }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate again") }
            }
            else -> OutlinedButton(onClick = { job?.cancel(); phase = Phase.INTRO }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
        if (!running && phase == Phase.INTRO) {
            Text("AirX1 will start the camera automatically.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Input is paused on this screen, so nothing gets clicked while you calibrate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
