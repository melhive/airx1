package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import com.melhive.airx1.log.LogBuffer

/**
 * Real touch input via dispatchGesture. Coordinates are screen pixels. Main thread only.
 *
 * Drags use continued strokes: one finger stays down while short segments are chained,
 * each dispatched when the previous completes.
 */
class InputInjector(private val service: AccessibilityService) {

    fun tap(x: Float, y: Float) = dispatch(stroke(point(x, y), 0, TAP_MS), "tap")

    fun longPress(x: Float, y: Float) = dispatch(stroke(point(x, y), 0, LONG_PRESS_MS), "long press")

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(stroke(p, 0, durationMs), "swipe")
    }

    // ---- Drag ----
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private val dragLast = PointF()
    private var dragPending: PointF? = null
    private var dragEndPending = false
    private var inFlight = false
    val isDragging: Boolean get() = dragStroke != null

    fun dragStart(x: Float, y: Float) {
        resetDrag()
        dragLast.set(x + 1f, y)
        val p = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        val s = GestureDescription.StrokeDescription(p, 0, SEGMENT_MS, true)
        dragStroke = s
        sendSegment(s)
    }

    fun dragMove(x: Float, y: Float) {
        if (dragStroke == null) return
        dragPending = PointF(x, y)
        pump()
    }

    fun dragEnd(x: Float, y: Float) {
        if (dragStroke == null) return
        dragPending = PointF(x, y)
        dragEndPending = true
        pump()
    }

    private fun pump() {
        val prev = dragStroke ?: return
        if (inFlight) return
        val target = dragPending ?: if (dragEndPending) PointF(dragLast.x, dragLast.y) else return
        dragPending = null
        // A segment must have length; nudge half a pixel when the hand is still.
        val tx = if (target.x == dragLast.x && target.y == dragLast.y) target.x + 0.5f else target.x
        val path = Path().apply { moveTo(dragLast.x, dragLast.y); lineTo(tx, target.y) }
        val last = dragEndPending
        val next = try {
            prev.continueStroke(path, 0, SEGMENT_MS, !last)
        } catch (e: Exception) {
            LogBuffer.e("Drag continue failed", e)
            resetDrag()
            return
        }
        dragLast.set(tx, target.y)
        if (last) {
            dragStroke = null
            dragEndPending = false
        } else {
            dragStroke = next
        }
        sendSegment(next)
    }

    private fun sendSegment(s: GestureDescription.StrokeDescription) {
        inFlight = true
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    inFlight = false
                    if (dragStroke != null) LogBuffer.w("Drag cancelled by the system")
                    resetDrag()
                }
            },
            null,
        )
        if (!ok) {
            inFlight = false
            LogBuffer.w("Drag segment rejected")
            resetDrag()
        }
    }

    private fun resetDrag() {
        dragStroke = null
        dragPending = null
        dragEndPending = false
    }

    // ---- helpers ----
    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun stroke(p: Path, start: Long, duration: Long) = GestureDescription.StrokeDescription(p, start, duration)

    private fun dispatch(s: GestureDescription.StrokeDescription, what: String): Boolean {
        if (isDragging) resetDrag() // a new gesture cancels any drag
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    LogBuffer.w("${what.replaceFirstChar { it.uppercase() }} cancelled by the system")
                }
            },
            null,
        )
        if (!ok) LogBuffer.w("${what.replaceFirstChar { it.uppercase() }} rejected")
        return ok
    }

    companion object {
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 750L
        private const val SEGMENT_MS = 24L
    }
}
