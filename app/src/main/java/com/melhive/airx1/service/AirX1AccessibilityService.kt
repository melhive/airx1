package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.data.AppSettings
import com.melhive.airx1.data.Apps
import com.melhive.airx1.data.SettingsRepository
import com.melhive.airx1.engine.Direction
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.EngineState
import com.melhive.airx1.engine.GestureEvent
import com.melhive.airx1.engine.InputMode
import com.melhive.airx1.engine.NavAction
import com.melhive.airx1.log.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws the cursor and panels, and turns engine events into real input on the foreground
 * app: dispatchGesture (tap, long press, drag, swipe, scroll), performGlobalAction
 * (Back/Home/Recents/Notifications) and accessibility text actions (AirKeys).
 */
class AirX1AccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var wm: WindowManager? = null
    private var overlay: CursorOverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var keepScreenOn = false
    private val metrics = DisplayMetrics()

    private lateinit var injector: InputInjector
    private lateinit var snapTargets: SnapTargets
    private lateinit var haptics: Haptics
    private lateinit var repo: SettingsRepository
    private var settings = AppSettings()

    private var launcher: PanelWindow? = null
    private var keysWindow: PanelWindow? = null
    private var airKeys: AirKeys? = null
    private var imeVisible = false
    private var gboardChosen = false
    private var keysAutoShown = false
    private var lastCursorX = 0f
    private var lastCursorY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogBuffer.i("Accessibility service connected")
        GestureBus.accessibilityConnected.value = true
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = windowManager
        injector = InputInjector(this)
        snapTargets = SnapTargets(this)
        haptics = Haptics(this)
        repo = SettingsRepository(this)
        addOverlay()

        launcher = PanelWindow(this, windowManager, "Quick Launcher  ·  pinch an app", Gravity.CENTER, 0.94f, 0.6f)
        val keys = PanelWindow(this, windowManager, null, Gravity.BOTTOM, 1f, 0.42f)
        keysWindow = keys
        airKeys = AirKeys(this, keys.view, onHide = { useGboard -> hideAirKeys(useGboard) }, onKey = { haptics.click() })

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            repo.flow.collect {
                settings = it
                haptics.enabled = it.haptics
                if (!it.smartSnap) snapTargets.clear()
            }
        }
        s.launch {
            combine(GestureBus.snapshot, GestureBus.engineRunning) { snap, running -> snap to running }
                .collect { (snap, running) -> render(snap, running) }
        }
        s.launch { GestureBus.events.collect(::handle) }
        s.launch {
            combine(GestureBus.engineRunning, GestureBus.snapshot.map { it.armed }) { r, a -> r && a }
                .distinctUntilChanged()
                .collect { active -> if (!active) hidePanels() }
        }
    }

    // ---------------------------------------------------------------- overlay

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
            LogBuffer.i("Cursor overlay ready")
        } catch (e: Exception) {
            LogBuffer.e("Could not add cursor overlay", e)
        }
    }

    /** Panels are added after the cursor; re-add the cursor so it stays on top. */
    private fun bringCursorToFront() {
        val v = overlay ?: return
        val p = params ?: return
        runCatching {
            wm?.removeViewImmediate(v)
            wm?.addView(v, p)
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }

    private val snapRadius get() = 28f * resources.displayMetrics.density

    private fun render(snap: EngineSnapshot, running: Boolean) {
        val view = overlay ?: return
        val (w, h) = screenSize()
        var x = snap.cursorX * w
        var y = snap.cursorY * h
        lastCursorX = x
        lastCursorY = y

        val active = running && snap.armed && snap.handPresent && snap.mode == InputMode.CURSOR
        val target = if (active && snap.state != EngineState.DRAGGING) findTarget(x, y) else null
        if (target != null && snap.state == EngineState.HOVER) {
            // Gentle magnet: draw the cursor part of the way towards the target's center.
            x += (target.exactCenterX() - x) * 0.3f
            y += (target.exactCenterY() - y) * 0.3f
        }
        val tips = if (settings.fingertipDots && snap.fingertips.size == 5) {
            FloatArray(10) { i -> if (i % 2 == 0) snap.fingertips[i / 2].x * w else snap.fingertips[i / 2].y * h }
        } else FloatArray(0)

        launcher?.takeIf { it.shown }?.view?.hover(x, y)
        keysWindow?.takeIf { it.shown }?.view?.hover(x, y)
        GestureBus.precision.value = running && (imeVisible || keysWindow?.shown == true)

        view.update(snap, x, y, tips, target, running, settings.handBubble)
        setKeepScreenOn(running && snap.armed)
        if (active && settings.smartSnap && !insidePanel(x, y)) snapTargets.requestScan(w * h)
    }

    private fun insidePanel(x: Float, y: Float) =
        (launcher?.shown == true && launcher!!.view.containsScreen(x, y)) ||
            (keysWindow?.shown == true && keysWindow!!.view.containsScreen(x, y))

    /** The tile or on-screen element the cursor should lock onto, if any. */
    private fun findTarget(x: Float, y: Float): Rect? {
        for (panel in listOfNotNull(keysWindow, launcher)) {
            if (panel.shown && panel.view.containsScreen(x, y)) return nearest(panel.view.tileRects(), x, y)
        }
        if (launcher?.shown == true) return null
        return if (settings.smartSnap) snapTargets.find(x, y, snapRadius) else null
    }

    private fun nearest(rects: List<Rect>, x: Float, y: Float): Rect? {
        rects.firstOrNull { it.contains(x.toInt(), y.toInt()) }?.let { return it }
        var best: Rect? = null
        var bestD = snapRadius
        for (r in rects) {
            val dx = max(max(r.left - x, 0f), x - r.right)
            val dy = max(max(r.top - y, 0f), y - r.bottom)
            val dd = hypot(dx, dy)
            if (dd < bestD) {
                bestD = dd
                best = r
            }
        }
        return best
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

    // ---------------------------------------------------------------- events

    private fun handle(event: GestureEvent) {
        val (w, h) = screenSize()
        when (event) {
            is GestureEvent.ArmChanged -> {
                if (event.armed) haptics.double() else haptics.heavy()
                return
            }
            // Always allowed: lifts a finger that is already down.
            is GestureEvent.DragEnd -> {
                injector.dragEnd(event.x * w, event.y * h)
                return
            }
            else -> Unit
        }
        // Safety: never inject input unless the engine is running, armed and not suppressed.
        if (!GestureBus.engineRunning.value || !GestureBus.snapshot.value.armed || GestureBus.inputSuppressed.value) {
            LogBuffer.d("Blocked $event (disarmed or suppressed)")
            return
        }
        when (event) {
            is GestureEvent.Tap -> handleTap(event.x * w, event.y * h, long = false)
            is GestureEvent.LongPress -> handleTap(event.x * w, event.y * h, long = true)
            is GestureEvent.DragStart -> {
                val x = event.x * w
                val y = event.y * h
                if (!insidePanel(x, y)) injector.dragStart(x, y)
            }
            is GestureEvent.DragMove -> injector.dragMove(event.x * w, event.y * h)
            is GestureEvent.Swipe -> {
                // Finger direction. A fast flick: next/previous item in feeds.
                val x = w / 2f
                if (event.direction == Direction.UP) injector.swipe(x, h * 0.72f, x, h * 0.28f, 120)
                else injector.swipe(x, h * 0.28f, x, h * 0.72f, 120)
                haptics.click()
            }
            is GestureEvent.Scroll -> {
                val x = w / 2f
                val dist = (h * (0.12f + 0.3f * event.amount) * settings.scrollSpeed).coerceAtMost(h * 0.5f)
                // Content direction: scrolling UP reveals content above = finger moves down.
                if (event.direction == Direction.UP) injector.swipe(x, h * 0.3f, x, h * 0.3f + dist, 260)
                else injector.swipe(x, h * 0.7f, x, h * 0.7f - dist, 260)
            }
            is GestureEvent.GlobalAction -> handleGlobal(event.action)
            GestureEvent.OpenLauncher -> toggleLauncher()
            is GestureEvent.Zoom -> LogBuffer.d("Zoom is not supported yet")
            else -> Unit
        }
    }

    private fun handleTap(x: Float, y: Float, long: Boolean) {
        // Panels first: their tiles are handled directly, no injected touch.
        for (panel in listOfNotNull(keysWindow, launcher)) {
            if (panel.shown && panel.view.containsScreen(x, y)) {
                val r = nearest(panel.view.tileRects(), x, y)
                val tile = if (r != null) panel.view.tileAtScreen(r.exactCenterX(), r.exactCenterY()) else null
                if (tile != null) {
                    if (panel === launcher) haptics.click()
                    tile.onTap()
                }
                return
            }
        }
        if (launcher?.shown == true) {
            hideLauncher() // tap outside closes the launcher
            return
        }
        val target = if (settings.smartSnap) snapTargets.find(x, y, snapRadius) else null
        val tx = target?.exactCenterX() ?: x
        val ty = target?.exactCenterY() ?: y
        if (long) {
            injector.longPress(tx, ty)
            haptics.heavy()
            LogBuffer.i("Long press at (${tx.toInt()}, ${ty.toInt()})")
        } else {
            injector.tap(tx, ty)
            haptics.click()
            LogBuffer.i("Tap at (${tx.toInt()}, ${ty.toInt()})" + if (target != null) " [snapped]" else "")
        }
        snapTargets.invalidate()
    }

    private fun handleGlobal(action: NavAction) {
        if (action == NavAction.BACK && (launcher?.shown == true || keysWindow?.shown == true)) {
            hidePanels()
            haptics.click()
            return
        }
        hideLauncher()
        val id = when (action) {
            NavAction.BACK -> GLOBAL_ACTION_BACK
            NavAction.HOME -> GLOBAL_ACTION_HOME
            NavAction.RECENTS -> GLOBAL_ACTION_RECENTS
            NavAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        }
        val ok = performGlobalAction(id)
        haptics.click()
        LogBuffer.i("${action.name.lowercase().replaceFirstChar { it.uppercase() }}" + if (ok) "" else " FAILED")
    }

    // ---------------------------------------------------------------- panels

    private fun toggleLauncher() {
        val l = launcher ?: return
        if (l.shown) {
            hideLauncher()
            return
        }
        hideAirKeys(false)
        val apps = runCatching { Apps.favorites(this, settings.favorites) }.getOrDefault(emptyList())
        val appRows = apps.map { app ->
            Tile("app_${app.pkg}", app.label, icon = app.icon) {
                hideLauncher()
                if (!Apps.launch(this, app.pkg)) LogBuffer.w("Could not open ${app.label}")
                else LogBuffer.i("Opened ${app.label}")
            }
        }.chunked(4).map { row -> row + List(4 - row.size) { Tile("empty$it", "", weight = 1f) {} } }
        val scroll = settings.mode == InputMode.SCROLL
        val actions = listOf(
            Tile("notif", "Alerts", accent = false) { hideLauncher(); performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) },
            Tile("qs", "Quick set.") { hideLauncher(); performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) },
            Tile("mode", if (scroll) "Cursor" else "Scroll", accent = true) {
                hideLauncher()
                setMode(if (scroll) InputMode.CURSOR else InputMode.SCROLL)
            },
            Tile("keys", "Keys") { hideLauncher(); showAirKeys(auto = false) },
            Tile("close", "✕", weight = 0.6f) { hideLauncher() },
        )
        l.view.setRows(appRows + listOf(actions))
        val (w, h) = screenSize()
        l.show(w, h)
        bringCursorToFront()
        haptics.click()
        LogBuffer.i("Quick Launcher opened")
    }

    private fun hideLauncher() {
        launcher?.hide()
    }

    private fun setMode(mode: InputMode) {
        scope?.launch { repo.update { it.copy(mode = mode) } }
        LogBuffer.i("Mode: ${mode.name.lowercase()}")
    }

    private fun showAirKeys(auto: Boolean) {
        val k = keysWindow ?: return
        if (k.shown) return
        keysAutoShown = auto
        val (w, h) = screenSize()
        airKeys?.refresh()
        k.show(w, h)
        bringCursorToFront()
        LogBuffer.i("AirKeys opened")
    }

    private fun hideAirKeys(useGboard: Boolean) {
        if (useGboard) gboardChosen = true
        keysWindow?.hide()
        keysAutoShown = false
    }

    private fun hidePanels() {
        hideLauncher()
        hideAirKeys(false)
    }

    // ---------------------------------------------------------------- accessibility events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                snapTargets.invalidate()
                updateIme()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                snapTargets.invalidate()
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                if (keysWindow?.shown == true) airKeys?.refresh()
        }
    }

    private fun updateIme() {
        val visible = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        if (visible == imeVisible) return
        imeVisible = visible
        if (visible) {
            val snap = GestureBus.snapshot.value
            if (GestureBus.engineRunning.value && snap.armed && settings.airKeysAuto &&
                settings.mode == InputMode.CURSOR && !gboardChosen
            ) {
                showAirKeys(auto = true)
            }
        } else {
            gboardChosen = false
            if (keysAutoShown) hideAirKeys(false)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        LogBuffer.i("Accessibility service disconnected")
        GestureBus.accessibilityConnected.value = false
        GestureBus.precision.value = false
        scope?.cancel()
        scope = null
        hidePanels()
        if (::snapTargets.isInitialized) snapTargets.shutdown()
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
        super.onDestroy()
    }
}
