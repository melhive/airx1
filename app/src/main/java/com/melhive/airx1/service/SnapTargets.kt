package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.melhive.airx1.log.LogBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max


/**
 * Smart Snap: finds the tappable elements on screen (buttons, icons, links, keyboard keys)
 * through the accessibility tree, so the cursor can lock onto them and taps hit their center.
 * Scans run on a background thread, at most every [MIN_INTERVAL_MS].
 */
class SnapTargets(private val service: AccessibilityService) {

    class Target(val rect: Rect, val window: Int)

    private class Snapshot(val targets: List<Target>, val windowBounds: List<Rect>)

    @Volatile private var snapshot = Snapshot(emptyList(), emptyList())
    private val executor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    @Volatile private var lastScanMs = 0L
    @Volatile private var dirty = true

    fun invalidate() {
        dirty = true
    }

    /** Main thread: schedule a scan if the screen changed (or [force]). */
    fun requestScan(screenArea: Float, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && (!dirty && now - lastScanMs < MAX_AGE_MS)) return
        if (now - lastScanMs < MIN_INTERVAL_MS || !scanning.compareAndSet(false, true)) return
        dirty = false
        lastScanMs = now
        executor.execute {
            try {
                snapshot = scan(screenArea)
            } catch (e: Exception) {
                LogBuffer.w("Snap scan failed", e)
            } finally {
                scanning.set(false)
            }
        }
    }

    fun clear() {
        snapshot = Snapshot(emptyList(), emptyList())
    }

    fun shutdown() = executor.shutdownNow()

    /**
     * The target under (x, y) in the topmost window there, or the nearest one within [radius] px.
     */
    fun find(x: Float, y: Float, radius: Float): Rect? {
        val s = snapshot
        val px = x.toInt()
        val py = y.toInt()
        val top = s.windowBounds.indexOfFirst { it.contains(px, py) }
        if (top < 0) return null
        var best: Rect? = null
        var bestArea = Long.MAX_VALUE
        var near: Rect? = null
        var nearDist = radius
        for (t in s.targets) {
            if (t.window != top) continue
            val r = t.rect
            if (r.contains(px, py)) {
                val area = r.width().toLong() * r.height()
                if (area < bestArea) {
                    bestArea = area
                    best = r
                }
            } else if (best == null) {
                val dx = max(max(r.left - x, 0f), x - r.right)
                val dy = max(max(r.top - y, 0f), y - r.bottom)
                val d = kotlin.math.hypot(dx, dy)
                if (d < nearDist) {
                    nearDist = d
                    near = r
                }
            }
        }
        return best ?: near
    }

    private fun scan(screenArea: Float): Snapshot {
        val out = ArrayList<Target>(256)
        val bounds = ArrayList<Rect>()
        val windows = service.windows ?: return Snapshot(emptyList(), emptyList())
        for (w in windows) { // ordered top-most first
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val wb = Rect().also { w.getBoundsInScreen(it) }
            val index = bounds.size
            bounds += wb
            val root = w.root ?: continue
            walk(root, out, index, w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD, screenArea, 0)
            if (out.size >= MAX_TARGETS) break
        }
        return Snapshot(out, bounds)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<Target>,
        window: Int,
        ime: Boolean,
        screenArea: Float,
        depth: Int,
    ) {
        try {
            if (!node.isVisibleToUser || depth > 40 || out.size >= MAX_TARGETS) return
            val r = Rect()
            node.getBoundsInScreen(r)
            val area = r.width().toFloat() * r.height()
            val tappable = node.isClickable || node.isLongClickable || node.isCheckable ||
                (ime && node.childCount == 0 && (node.text != null || node.contentDescription != null))
            // Skip containers that cover most of the screen; they don't help aiming.
            if (tappable && r.width() > 4 && r.height() > 4 && area < screenArea * 0.25f) {
                out += Target(r, window)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, out, window, ime, screenArea, depth + 1)
            }
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_AGE_MS = 1500L
        private const val MAX_TARGETS = 800

        fun center(r: Rect) = r.exactCenterX() to r.exactCenterY()

    }
}
