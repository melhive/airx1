package com.melhive.airx1.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.EngineState
import com.melhive.airx1.engine.InputMode

/**
 * Full-screen, non-touchable view that draws the air cursor and its feedback:
 *  - disarmed: small dim dot (+ white arc while a fist hold is in progress)
 *  - armed: teal ring that fills as your fingers approach a pinch; solid while pinching
 *  - fingertip ghost dots, Smart Snap highlight, dwell and pose progress with a label
 *  - Scroll mode: a scroll gauge on the right edge
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var snap = EngineSnapshot()
    private var xPx = 0f
    private var yPx = 0f
    private var tips: FloatArray = FloatArray(0)
    private var target: Rect? = null
    private var shown = false
    private val loc = IntArray(2)
    private val arc = RectF()
    private val tmp = RectF()

    private val accent = Color.rgb(0x4F, 0xD1, 0xC5)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(150, 0, 0, 0)
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 200, 200, 200) }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val dwellPaint = Paint(progress).apply { color = Color.rgb(0xFF, 0xC8, 0x57) }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = accent
    }
    private val highlightFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0x4F, 0xD1, 0xC5) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
    private val gauge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }

    /** [tipsPx] = x0,y0,x1,y1,… in screen pixels; [targetRect] = Smart Snap target in screen pixels. */
    fun update(snapshot: EngineSnapshot, x: Float, y: Float, tipsPx: FloatArray, targetRect: Rect?, visible: Boolean) {
        snap = snapshot
        xPx = x
        yPx = y
        tips = tipsPx
        target = targetRect
        shown = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        getLocationOnScreen(loc)
        val ox = -loc[0].toFloat()
        val oy = -loc[1].toFloat()
        if (snap.mode == InputMode.SCROLL) {
            drawScrollGauge(canvas)
        } else {
            drawCursor(canvas, xPx + ox, yPx + oy, ox, oy)
        }
        val x = if (snap.mode == InputMode.SCROLL) width - 40 * d else xPx + ox
        val y = if (snap.mode == InputMode.SCROLL) height / 2f else yPx + oy
        val r = 14 * d
        if (snap.armProgress > 0f) drawArc(canvas, x, y, r + 8 * d, snap.armProgress, progress)
        if (snap.poseProgress > 0f) {
            drawArc(canvas, x, y, r + 8 * d, snap.poseProgress, progress)
            snap.poseLabel?.let { drawLabel(canvas, it, x, y - r - 22 * d) }
        }
        if (!snap.armed && snap.armProgress > 0f) drawLabel(canvas, "Arm", x, y - r - 22 * d)
    }

    private fun drawCursor(canvas: Canvas, x: Float, y: Float, ox: Float, oy: Float) {
        val r = (if (snap.precision) 10 else 14) * d
        val alphaScale = if (snap.handPresent) 1f else 0.35f

        target?.let { t ->
            if (snap.armed && snap.handPresent) {
                tmp.set(t.left + ox, t.top + oy, t.right + ox, t.bottom + oy)
                canvas.drawRoundRect(tmp, 10 * d, 10 * d, highlightFill)
                canvas.drawRoundRect(tmp, 10 * d, 10 * d, highlight)
            }
        }

        if (snap.handPresent && tips.size == 10) {
            tipPaint.alpha = if (snap.armed) 150 else 80
            for (i in 0 until 5) {
                if (i == 1) continue // the index dot is the cursor itself
                canvas.drawCircle(tips[i * 2] + ox, tips[i * 2 + 1] + oy, 4.5f * d, tipPaint)
            }
        }

        when {
            !snap.armed -> {
                dim.alpha = (130 * alphaScale).toInt()
                canvas.drawCircle(x, y, r * 0.55f, dim)
                canvas.drawCircle(x, y, r * 0.55f, outline)
            }
            snap.state == EngineState.PINCHING || snap.state == EngineState.DRAGGING -> {
                fill.alpha = 255
                canvas.drawCircle(x, y, r * 0.8f, fill)
                canvas.drawCircle(x, y, r * 0.8f, outline)
            }
            else -> {
                ring.alpha = (255 * alphaScale).toInt()
                canvas.drawCircle(x, y, r + ring.strokeWidth / 2, outline)
                canvas.drawCircle(x, y, r, ring)
                if (snap.pinchProgress > 0f) {
                    fill.alpha = (200 * alphaScale).toInt()
                    canvas.drawCircle(x, y, r * 0.8f * snap.pinchProgress, fill)
                }
            }
        }
        if (snap.dwellProgress > 0f) drawArc(canvas, x, y, r + 4 * d, snap.dwellProgress, dwellPaint)
    }

    private fun drawScrollGauge(canvas: Canvas) {
        val cx = width - 40 * d
        val h = height * 0.5f
        val top = (height - h) / 2f
        tmp.set(cx - 14 * d, top, cx + 14 * d, top + h)
        canvas.drawRoundRect(tmp, 14 * d, 14 * d, gauge)
        val alpha = if (snap.armed && snap.handPresent) 255 else 90
        ring.alpha = alpha
        canvas.drawRoundRect(tmp, 14 * d, 14 * d, ring)
        // Marker: where the hand is (level -1..1)
        val my = top + h / 2f + snap.scrollLevel * (h / 2f - 14 * d)
        fill.alpha = alpha
        canvas.drawCircle(cx, my, 10 * d, fill)
        label.alpha = alpha
        canvas.drawText("▲", cx, top - 8 * d, label)
        canvas.drawText("▼", cx, top + h + 20 * d, label)
        label.alpha = 255
    }

    private fun drawArc(canvas: Canvas, x: Float, y: Float, radius: Float, p: Float, paint: Paint) {
        arc.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawArc(arc, -90f, 360f * p, false, paint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val w = label.measureText(text) / 2 + 8 * d
        tmp.set(x - w, y - 16 * d, x + w, y + 6 * d)
        canvas.drawRoundRect(tmp, 8 * d, 8 * d, labelBg)
        canvas.drawText(text, x, y, label)
    }
}
