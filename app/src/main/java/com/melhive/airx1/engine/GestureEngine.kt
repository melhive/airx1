package com.melhive.airx1.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Turns a stream of hand frames into a cursor and gesture events. Pure Kotlin, no Android,
 * single-threaded: call [process] from one thread only.
 *
 * Safety: no input event is ever emitted while disarmed, except [GestureEvent.DragEnd],
 * which lifts a finger that was already down, and [GestureEvent.ArmChanged].
 */
class GestureEngine(config: EngineConfig = EngineConfig()) {

    var config: EngineConfig = config.sanitized()
        set(value) {
            field = value.sanitized()
            applyFilterParams()
        }

    /** Set by the app while a keyboard is visible: slower, steadier cursor. */
    @Volatile var precision: Boolean = false

    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()

    // Arming
    private var armed = false
    private var lastArmChangeMs = Long.MIN_VALUE / 4
    private var lastHandMs: Long? = null
    private var armHoldStartMs: Long? = null
    private var armLatched = false

    // Pinch / tap / long press / drag
    private var pinched = false
    private var pinchStartMs = 0L
    private var pinchX = 0.5f
    private var pinchY = 0.5f
    private var pinchBaseX = 0.5f
    private var pinchBaseY = 0.5f
    private var pinchCountsAsInput = false
    private var dragging = false
    private var longPressed = false
    private var lastTapMs = Long.MIN_VALUE / 4

    // Poses (Back/Home/Recents/Launcher)
    private var holdPose: HandPose? = null
    private var poseHoldStartMs = 0L
    private var poseLatched = false
    private var poseCooldownUntil = 0L

    // Dwell
    private var dwellX = Float.NaN
    private var dwellY = Float.NaN
    private var dwellStartMs = 0L
    private var dwellFired = false

    // Scroll mode
    private var lastScrollMs = Long.MIN_VALUE / 4
    private var scrollNeedsRecenter = true
    private var flickCooldownUntil = 0L
    private val rawY = ArrayDeque<Sample>()

    // Precision (relative motion)
    private var precisionActive = false
    private var precBaseMx = 0f
    private var precBaseMy = 0f
    private var precBaseCx = 0.5f
    private var precBaseCy = 0.5f

    private var lastMode = this.config.mode
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private val history = ArrayDeque<Sample>()

    private class Sample(val t: Long, val x: Float, val y: Float)

    init {
        applyFilterParams()
    }

    val isArmed: Boolean get() = armed

    private fun applyFilterParams() {
        for (f in listOf(fx, fy)) {
            f.minCutoff = config.minCutoff.toDouble()
            f.beta = config.beta.toDouble()
        }
    }

    /** External kill switch (notification Stop, screen off, …). */
    fun disarm(reason: String, nowMs: Long): List<GestureEvent> {
        val events = ArrayList<GestureEvent>(2)
        cancelPinch(events)
        resetPose()
        if (armed) {
            armed = false
            lastArmChangeMs = nowMs
            events += GestureEvent.ArmChanged(false, reason)
        }
        return events
    }

    fun process(frame: HandFrame?, nowMs: Long): EngineOutput {
        val events = ArrayList<GestureEvent>(2)
        val c = config
        if (c.mode != lastMode) {
            cancelPinch(events)
            scrollNeedsRecenter = true
            lastMode = c.mode
        }

        val hand = frame?.takeIf { it.landmarks.size >= 21 && matchesPreference(it) }
            ?: return noHand(nowMs, events)
        val aspect = hand.aspect
        val lm = if (c.mirrorX) hand.landmarks.map { p -> p.copy(x = 1f - p.x) } else hand.landmarks
        if (lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect) < 1e-4f) return noHand(nowMs, events)

        val prevHandMs = lastHandMs
        if (prevHandMs != null && nowMs - prevHandMs >= c.handLostResetMs) resetSmoothing()
        lastHandMs = nowMs

        val size = lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect)
        val ratio = lm.dist(Lm.THUMB_TIP, Lm.INDEX_TIP, aspect) / size
        val pose = PoseClassifier.classify(lm, aspect)
        val isFist = pose == HandPose.FIST
        val anchor = lm[if (c.anchor == CursorAnchor.KNUCKLE) Lm.INDEX_MCP else Lm.INDEX_TIP]
        val (mx, my) = mapToScreen(anchor.x, anchor.y, c)

        // Precision: relative motion with reduced gain while a keyboard is open.
        val usePrecision = precision && c.mode == InputMode.CURSOR
        val (tx, ty) = if (usePrecision) {
            if (!precisionActive) {
                precisionActive = true
                precBaseMx = mx; precBaseMy = my
                precBaseCx = cursorX; precBaseCy = cursorY
            }
            (precBaseCx + (mx - precBaseMx) * c.precisionGain).coerceIn(0f, 1f) to
                (precBaseCy + (my - precBaseMy) * c.precisionGain).coerceIn(0f, 1f)
        } else {
            precisionActive = false
            mx to my
        }

        val tSec = nowMs / 1000.0
        val sx = fx.filter(tx.toDouble(), tSec).toFloat()
        val sy = fy.filter(ty.toDouble(), tSec).toFloat()
        history.addLast(Sample(nowMs, sx, sy))
        while (history.size > 2 && nowMs - history.first().t > 500) history.removeFirst()

        // ---- Pinch: tap, long press, drag (cursor mode only; a fist always overrides) ----
        val canPinch = c.mode == InputMode.CURSOR && !isFist
        val wasPinched = pinched
        when {
            !canPinch -> if (pinched) cancelPinch(events)
            !pinched && ratio < c.pinchOn -> pinched = true
            pinched && ratio > c.pinchOff -> pinched = false
        }
        if (!wasPinched && pinched) {
            pinchStartMs = nowMs
            val p = positionAt(nowMs - c.pinchRewindMs, sx, sy)
            pinchX = p.first
            pinchY = p.second
            pinchBaseX = sx
            pinchBaseY = sy
            pinchCountsAsInput = armed && nowMs - lastArmChangeMs >= c.armGraceMs
            dragging = false
            longPressed = false
            dwellFired = true // a pinch replaces any pending dwell click
        }
        if (pinched && wasPinched && pinchCountsAsInput && armed) {
            val dx = sx - pinchBaseX
            val dy = sy - pinchBaseY
            if (!dragging && !longPressed && hypot(dx, dy) > c.dragStartDist) {
                dragging = true
                events += GestureEvent.DragStart(pinchX, pinchY)
            }
            if (dragging) {
                cursorX = (pinchX + dx).coerceIn(0f, 1f)
                cursorY = (pinchY + dy).coerceIn(0f, 1f)
                events += GestureEvent.DragMove(cursorX, cursorY)
            } else if (!longPressed && nowMs - pinchStartMs >= c.longPressMs) {
                longPressed = true
                events += GestureEvent.LongPress(pinchX, pinchY)
            }
        }
        if (wasPinched && !pinched && canPinch) {
            val dur = nowMs - pinchStartMs
            if (dragging) {
                events += GestureEvent.DragEnd(cursorX, cursorY)
            } else if (!longPressed && armed && pinchCountsAsInput && dur >= c.tapMinMs &&
                nowMs - lastTapMs >= c.tapDebounceMs
            ) {
                events += GestureEvent.Tap(pinchX, pinchY)
                lastTapMs = nowMs
            }
            dragging = false
            longPressed = false
        }

        if (!pinched) {
            cursorX = sx
            cursorY = sy
        } else if (!dragging) {
            cursorX = pinchX
            cursorY = pinchY
        }

        // ---- Arm / disarm: fist held, latched until the pose changes ----
        var armProgress = 0f
        if (isFist) {
            if (!armLatched) {
                val start = armHoldStartMs ?: nowMs.also { armHoldStartMs = it }
                val held = nowMs - start
                armProgress = (held.toFloat() / c.armHoldMs).coerceIn(0f, 1f)
                if (held >= c.armHoldMs) {
                    armed = !armed
                    lastArmChangeMs = nowMs
                    armLatched = true
                    armHoldStartMs = null
                    armProgress = 0f
                    resetPose()
                    events += GestureEvent.ArmChanged(armed, "fist hold")
                }
            }
        } else {
            armHoldStartMs = null
            armLatched = false
        }

        // ---- Navigation and launcher poses (armed, not pinching) ----
        var poseProgress = 0f
        var poseLabel: String? = null
        val action = if (armed && !pinched && nowMs - lastArmChangeMs >= c.armGraceMs) poseAction(pose) else null
        if (action != null && nowMs >= poseCooldownUntil) {
            if (holdPose != pose) {
                holdPose = pose
                poseHoldStartMs = nowMs
                poseLatched = false
            }
            if (!poseLatched) {
                val need = if (action == PoseAction.LAUNCHER) c.launcherHoldMs else c.poseHoldMs
                val held = nowMs - poseHoldStartMs
                poseProgress = (held.toFloat() / need).coerceIn(0f, 1f)
                poseLabel = action.label
                if (held >= need) {
                    poseLatched = true
                    poseProgress = 0f
                    poseLabel = null
                    poseCooldownUntil = nowMs + c.poseCooldownMs
                    events += action.event
                }
            }
        } else if (holdPose != pose) {
            resetPose()
        }

        // ---- Dwell click (cursor mode) ----
        var dwellProgress = 0f
        val dwellAllowed = c.dwellEnabled && armed && c.mode == InputMode.CURSOR && !pinched &&
            action == null && !isFist && nowMs - lastArmChangeMs >= c.armGraceMs
        if (dwellAllowed) {
            if (dwellX.isNaN() || hypot(cursorX - dwellX, cursorY - dwellY) > c.dwellRadius) {
                dwellX = cursorX
                dwellY = cursorY
                dwellStartMs = nowMs
                dwellFired = false
            } else if (!dwellFired) {
                val lead = 200L // don't show the ring for tiny pauses
                val held = nowMs - dwellStartMs
                dwellProgress = ((held - lead).toFloat() / (c.dwellMs - lead)).coerceIn(0f, 1f)
                if (held >= c.dwellMs) {
                    dwellFired = true
                    dwellProgress = 0f
                    if (nowMs - lastTapMs >= c.tapDebounceMs) {
                        events += GestureEvent.Tap(dwellX, dwellY)
                        lastTapMs = nowMs
                    }
                }
            }
        } else if (!pinched) {
            // Keep the anchor (already marked fired) through a pinch, so no dwell click
            // follows right after a pinch tap.
            dwellX = Float.NaN
        }

        // ---- Scroll mode ----
        var scrollLevel = 0f
        if (c.mode == InputMode.SCROLL) {
            rawY.addLast(Sample(nowMs, mx, my))
            while (rawY.size > 2 && nowMs - rawY.first().t > 300) rawY.removeFirst()
            val level = (my - 0.5f) * 2f
            val dead = c.scrollDeadZone
            scrollLevel = if (abs(level) < dead) 0f else sign(level) * ((abs(level) - dead) / (1f - dead)).coerceIn(0f, 1f)
            if (scrollLevel == 0f) scrollNeedsRecenter = false
            if (armed && !isFist && action == null && nowMs - lastArmChangeMs >= c.armGraceMs) {
                val vy = velocityY()
                if (abs(vy) > c.flickSpeed && nowMs >= flickCooldownUntil) {
                    // Hand moves up fast = finger swipes up = next item.
                    events += GestureEvent.Swipe(if (vy < 0) Direction.UP else Direction.DOWN, 0.5f, 0.5f)
                    flickCooldownUntil = nowMs + 700
                    scrollNeedsRecenter = true
                } else if (scrollLevel != 0f && !scrollNeedsRecenter && nowMs - lastScrollMs >= c.scrollIntervalMs) {
                    events += GestureEvent.Scroll(if (scrollLevel < 0) Direction.UP else Direction.DOWN, abs(scrollLevel))
                    lastScrollMs = nowMs
                }
            }
        } else {
            rawY.clear()
        }

        // ---- Fingertip ghost dots ----
        val tips = if (c.mode == InputMode.CURSOR) {
            val w = (c.regionRight - c.regionLeft) / c.sensitivity
            val h = (c.regionBottom - c.regionTop) / c.sensitivity
            val k = c.fingertipScale * (if (usePrecision) c.precisionGain else 1f)
            FINGERTIPS.map { i ->
                ScreenPoint(
                    (cursorX + (lm[i].x - anchor.x) / w * k).coerceIn(0f, 1f),
                    (cursorY + (lm[i].y - anchor.y) / h * k).coerceIn(0f, 1f),
                )
            }
        } else emptyList()

        val snapshot = EngineSnapshot(
            handPresent = true,
            armed = armed,
            state = stateOf(),
            cursorX = cursorX,
            cursorY = cursorY,
            rawX = anchor.x,
            rawY = anchor.y,
            pinchRatio = ratio,
            pinchProgress = pinchProgress(ratio, c),
            pose = pose,
            armProgress = armProgress,
            handedness = hand.handedness,
            landmarks = lm,
            mode = c.mode,
            poseProgress = poseProgress,
            poseLabel = poseLabel,
            dwellProgress = dwellProgress,
            fingertips = tips,
            scrollLevel = scrollLevel,
            precision = usePrecision,
        )
        return EngineOutput(snapshot, events)
    }

    private fun pinchProgress(ratio: Float, c: EngineConfig): Float {
        if (pinched) return 1f
        if (c.mode != InputMode.CURSOR) return 0f
        val approachStart = c.pinchOff + (c.pinchOff - c.pinchOn)
        return ((approachStart - ratio) / (approachStart - c.pinchOn)).coerceIn(0f, 1f)
    }

    /** Vertical hand speed over the last ~150 ms, in screen heights per second. */
    private fun velocityY(): Float {
        val last = rawY.lastOrNull() ?: return 0f
        val first = rawY.firstOrNull { last.t - it.t <= 150 } ?: return 0f
        val dt = (last.t - first.t) / 1000f
        if (dt < 0.08f) return 0f
        return (last.y - first.y) / dt
    }

    private fun noHand(nowMs: Long, events: MutableList<GestureEvent>): EngineOutput {
        cancelPinch(events)
        armHoldStartMs = null
        armLatched = false
        resetPose()
        dwellX = Float.NaN
        rawY.clear()
        val last = lastHandMs
        if (armed && last != null && nowMs - last >= config.autoDisarmMs) {
            armed = false
            lastArmChangeMs = nowMs
            events += GestureEvent.ArmChanged(false, "no hand for ${config.autoDisarmMs / 1000}s")
        }
        val snapshot = EngineSnapshot(
            handPresent = false,
            armed = armed,
            state = stateOf(),
            cursorX = cursorX,
            cursorY = cursorY,
            mode = config.mode,
        )
        return EngineOutput(snapshot, events)
    }

    private fun stateOf() = when {
        !armed -> EngineState.DISARMED
        dragging -> EngineState.DRAGGING
        pinched -> EngineState.PINCHING
        else -> EngineState.HOVER
    }

    /** Ends any pinch without a tap. A drag in progress is always ended so no finger stays down. */
    private fun cancelPinch(events: MutableList<GestureEvent>) {
        if (dragging) events += GestureEvent.DragEnd(cursorX, cursorY)
        pinched = false
        pinchCountsAsInput = false
        dragging = false
        longPressed = false
    }

    private fun resetPose() {
        holdPose = null
        poseLatched = false
    }

    private fun resetSmoothing() {
        fx.reset()
        fy.reset()
        history.clear()
    }

    private fun matchesPreference(f: HandFrame) = when (config.handPreference) {
        HandPreference.ANY -> true
        HandPreference.LEFT -> f.handedness != Handedness.RIGHT
        HandPreference.RIGHT -> f.handedness != Handedness.LEFT
    }

    /** Cursor position at time [t] (latest sample not after t), for pinch rewind. */
    private fun positionAt(t: Long, fallbackX: Float, fallbackY: Float): Pair<Float, Float> {
        var best: Sample? = null
        for (s in history) if (s.t <= t) best = s else break
        val b = best ?: history.firstOrNull() ?: return fallbackX to fallbackY
        return b.x to b.y
    }

    private enum class PoseAction(val label: String, val event: GestureEvent) {
        BACK("Back", GestureEvent.GlobalAction(NavAction.BACK)),
        HOME("Home", GestureEvent.GlobalAction(NavAction.HOME)),
        RECENTS("Recents", GestureEvent.GlobalAction(NavAction.RECENTS)),
        LAUNCHER("Launcher", GestureEvent.OpenLauncher),
    }

    private fun poseAction(p: HandPose) = when (p) {
        HandPose.TWO -> PoseAction.BACK
        HandPose.THREE -> PoseAction.HOME
        HandPose.FOUR -> PoseAction.RECENTS
        HandPose.OPEN_PALM -> PoseAction.LAUNCHER
        else -> null
    }

    companion object {
        private val FINGERTIPS = intArrayOf(Lm.THUMB_TIP, Lm.INDEX_TIP, Lm.MIDDLE_TIP, Lm.RING_TIP, Lm.PINKY_TIP)

        /** Maps a point in the camera image to normalized screen coordinates via the active region. */
        fun mapToScreen(x: Float, y: Float, c: EngineConfig): Pair<Float, Float> {
            val cx = (c.regionLeft + c.regionRight) / 2f
            val cy = (c.regionTop + c.regionBottom) / 2f
            val w = ((c.regionRight - c.regionLeft) / c.sensitivity).coerceAtLeast(0.02f)
            val h = ((c.regionBottom - c.regionTop) / c.sensitivity).coerceAtLeast(0.02f)
            val nx = ((x - cx) / w + 0.5f).coerceIn(0f, 1f)
            val ny = ((y - cy) / h + 0.5f).coerceIn(0f, 1f)
            return nx to ny
        }
    }
}
