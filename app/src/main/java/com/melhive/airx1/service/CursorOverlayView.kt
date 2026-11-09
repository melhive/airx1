package com.melhive.airx1.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.EngineState

/**
 * Full-screen, non-touchable view that draws the air cursor.
 *  - disarmed: small dim dot (+ white arc while a fist hold is in progress)
 *  - armed:    teal ring that fills as your fingers approach a pinch
 *  - pinching: solid teal dot
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var snap = EngineSnapshot()
    private var xPx = 0f
    private var yPx = 0f
    private var shown = false
    private val loc = IntArray(2)
    private val arcRect = RectF()

    private val accent = Color.rgb(0x4F, 0xD1, 0xC5)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(150, 0, 0, 0)
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(130, 200, 200, 200) }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }

    fun update(snapshot: EngineSnapshot, x: Float, y: Float, visible: Boolean) {
        snap = snapshot
        xPx = x
        yPx = y
        shown = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        getLocationOnScreen(loc)
        val x = xPx - loc[0]
        val y = yPx - loc[1]
        val r = 14 * d
        val alphaScale = if (snap.handPresent) 1f else 0.35f

        when {
            !snap.armed -> {
                dim.alpha = (130 * alphaScale).toInt()
                canvas.drawCircle(x, y, r * 0.55f, dim)
                canvas.drawCircle(x, y, r * 0.55f, outline)
            }
            snap.state == EngineState.PINCHING -> {
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

        if (snap.armProgress > 0f) {
            val pr = r + 8 * d
            arcRect.set(x - pr, y - pr, x + pr, y + pr)
            canvas.drawArc(arcRect, -90f, 360f * snap.armProgress, false, progress)
        }
    }
}
