package com.melhive.airx1.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/** One button on a panel. [weight] = relative width within its row. */
class Tile(
    val id: String,
    val label: String,
    val icon: Drawable? = null,
    val weight: Float = 1f,
    val accent: Boolean = false,
    val onTap: () -> Unit,
)

/**
 * A grid of big tiles drawn on a canvas (Quick Launcher, AirKeys). Air taps are routed here
 * directly by the accessibility service (no injected touch needed); real finger taps work too.
 */
@SuppressLint("ViewConstructor")
class TilePanelView(context: Context, private val title: String?) : View(context) {
    private val d = resources.displayMetrics.density
    private var rows: List<List<Tile>> = emptyList()
    private val rects = HashMap<Tile, RectF>()
    private var hovered: Tile? = null
    private val loc = IntArray(2)
    var message: String? = null
        set(value) { field = value; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 18, 22, 28) }
    private val tileBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 40, 46, 56) }
    private val tileAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 0, 105, 98) }
    private val tileHover = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.rgb(0x4F, 0xD1, 0xC5)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 18 * d
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 12 * d
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textSize = 14 * d
    }

    fun setRows(newRows: List<List<Tile>>) {
        rows = newRows
        hovered = null
        layoutTiles()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutTiles()

    private fun layoutTiles() {
        rects.clear()
        if (width == 0 || rows.isEmpty()) return
        val pad = 6 * d
        val top = if (title != null || message != null) 26 * d else pad
        val rowH = (height - top - pad) / rows.size
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = pad
            val unit = (width - pad * (row.size + 1)) / total
            row.forEach { t ->
                val w = unit * t.weight
                rects[t] = RectF(x, top + ri * rowH + pad / 2, x + w, top + (ri + 1) * rowH - pad / 2)
                x += w + pad
            }
        }
    }

    /** Screen-space rects of all tiles (for Smart Snap). */
    fun tileRects(): List<Rect> {
        getLocationOnScreen(loc)
        return rects.values.map { r ->
            Rect((r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(), (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt())
        }
    }

    fun containsScreen(x: Float, y: Float): Boolean {
        if (!isShown) return false
        getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + width && y >= loc[1] && y < loc[1] + height
    }

    fun tileAtScreen(x: Float, y: Float): Tile? {
        getLocationOnScreen(loc)
        val lx = x - loc[0]
        val ly = y - loc[1]
        return rects.entries.firstOrNull { it.value.contains(lx, ly) }?.key
    }

    fun hover(x: Float, y: Float) {
        val t = if (containsScreen(x, y)) tileAtScreen(x, y) else null
        if (t !== hovered) {
            hovered = t
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = 18 * d
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
        val header = message ?: title
        if (header != null) canvas.drawText(header, 12 * d, 18 * d, titlePaint)
        for ((t, rect) in rects) {
            canvas.drawRoundRect(rect, 12 * d, 12 * d, if (t.accent) tileAccent else tileBg)
            if (t === hovered) canvas.drawRoundRect(rect, 12 * d, 12 * d, tileHover)
            val icon = t.icon
            if (icon != null) {
                val s = (minOf(rect.width(), rect.height()) * 0.5f).toInt()
                val cx = rect.centerX().toInt()
                val top = (rect.top + rect.height() * 0.12f).toInt()
                icon.setBounds(cx - s / 2, top, cx + s / 2, top + s)
                icon.draw(canvas)
                canvas.drawText(t.label.take(12), rect.centerX(), rect.bottom - 8 * d, small)
            } else {
                canvas.drawText(t.label, rect.centerX(), rect.centerY() + text.textSize / 3, text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            rects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key?.onTap?.invoke()
        }
        return true
    }
}
