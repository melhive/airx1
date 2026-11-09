package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.GestureEvent
import com.melhive.airx1.log.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws the cursor overlay and turns engine events into real input on the foreground app
 * (dispatchGesture / performGlobalAction).
 */
class AirX1AccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var wm: WindowManager? = null
    private var overlay: CursorOverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var keepScreenOn = false
    private val metrics = DisplayMetrics()

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogBuffer.i("Accessibility service connected")
        GestureBus.accessibilityConnected.value = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(GestureBus.snapshot, GestureBus.engineRunning) { snap, running -> snap to running }
                .collect { (snap, running) -> render(snap, running) }
        }
        s.launch { GestureBus.events.collect(::handle) }
    }

    private fun addOverlay() {
        val view = CursorOverlayView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AirX1 cursor"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm?.addView(view, p)
            overlay = view
            params = p
        } catch (e: Exception) {
            LogBuffer.e("Could not add cursor overlay", e)
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }

    private fun render(snap: EngineSnapshot, running: Boolean) {
        val view = overlay ?: return
        val (w, h) = screenSize()
        view.update(snap, snap.cursorX * w, snap.cursorY * h, running)
        setKeepScreenOn(running && snap.armed)
    }

    /** While armed, keep the screen awake: you are not touching it, so it would time out. */
    private fun setKeepScreenOn(on: Boolean) {
        if (on == keepScreenOn) return
        val p = params ?: return
        val view = overlay ?: return
        keepScreenOn = on
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        runCatching { wm?.updateViewLayout(view, p) }
    }

    private fun handle(event: GestureEvent) {
        if (event is GestureEvent.ArmChanged) return // logged by the engine service
        // Safety: never inject input unless the engine is running, armed and not suppressed.
        val snap = GestureBus.snapshot.value
        if (!GestureBus.engineRunning.value || !snap.armed || GestureBus.inputSuppressed.value) {
            LogBuffer.d("Blocked $event (disarmed or suppressed)")
            return
        }
        when (event) {
            is GestureEvent.Tap -> tap(event.x, event.y)
            is GestureEvent.GlobalAction -> LogBuffer.d("Global actions arrive in the next build: $event")
            else -> LogBuffer.d("Not implemented yet: $event")
        }
    }

    private fun tap(nx: Float, ny: Float) {
        val (w, h) = screenSize()
        val x = (nx * w).coerceIn(0f, w - 1f)
        val y = (ny * h).coerceIn(0f, h - 1f)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                LogBuffer.w("Tap cancelled by the system")
            }
        }, null)
        LogBuffer.i("Tap at (${x.toInt()}, ${y.toInt()})" + if (accepted) "" else " REJECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        LogBuffer.i("Accessibility service disconnected")
        GestureBus.accessibilityConnected.value = false
        scope?.cancel()
        scope = null
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
    }
}
