package com.melhive.airx1.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import com.melhive.airx1.log.LogBuffer

/**
 * A [TilePanelView] in its own accessibility overlay window. Not focusable, so the app
 * underneath keeps keyboard focus (needed for AirKeys).
 */
class PanelWindow(
    context: Context,
    private val wm: WindowManager,
    title: String?,
    private val gravity: Int,
    private val widthFrac: Float,
    private val heightFrac: Float,
    private val yOffsetFrac: Float = 0f,
) {
    val view = TilePanelView(context, title)
    var shown = false
        private set

    fun show(screenW: Float, screenH: Float) {
        if (shown) return
        val p = WindowManager.LayoutParams(
            (screenW * widthFrac).toInt(),
            (screenH * heightFrac).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = this@PanelWindow.gravity
            y = (screenH * yOffsetFrac).toInt()
            title = "AirX1 panel"
        }
        try {
            wm.addView(view, p)
            shown = true
        } catch (e: Exception) {
            LogBuffer.e("Could not show panel", e)
        }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(view) }
        shown = false
    }
}
