package com.melhive.airx1.engine

/**
 * Turns a stream of hand frames into a cursor and gesture events. Pure Kotlin, no Android,
 * single-threaded: call [process] from one thread only.
 *
 * Safety: no input event (anything except [GestureEvent.ArmChanged]) is ever emitted
 * while disarmed.
 */
class GestureEngine(config: EngineConfig = EngineConfig()) {

    var config: EngineConfig = config.sanitized()
        set(value) {
            field = value.sanitized()
            applyFilterParams()
        }

    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()

    private var armed = false
    private var lastArmChangeMs = Long.MIN_VALUE / 4
    private var lastHandMs: Long? = null
    private var armHoldStartMs: Long? = null
    private var armLatched = false

    private var pinched = false
    private var pinchStartMs = 0L
    private var pinchX = 0.5f
    private var pinchY = 0.5f
    private var pinchCountsAsInput = false
    private var lastTapMs = Long.MIN_VALUE / 4

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
    fun disarm(reason: String, nowMs: Long): GestureEvent? {
        cancelPinch()
        if (!armed) return null
        armed = false
        lastArmChangeMs = nowMs
        return GestureEvent.ArmChanged(false, reason)
    }

    fun process(frame: HandFrame?, nowMs: Long): EngineOutput {
        val events = ArrayList<GestureEvent>(2)
        val hand = frame?.takeIf { it.landmarks.size >= 21 && matchesPreference(it) }
            ?: return noHand(nowMs, events)
        val aspect = hand.aspect
        val lm = if (config.mirrorX) hand.landmarks.map { p -> p.copy(x = 1f - p.x) } else hand.landmarks
        if (lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect) < 1e-4f) return noHand(nowMs, events)

        val prevHandMs = lastHandMs
        if (prevHandMs != null && nowMs - prevHandMs >= config.handLostResetMs) resetSmoothing()
        lastHandMs = nowMs

        val c = config
        val size = lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect)
        val ratio = lm.dist(Lm.THUMB_TIP, Lm.INDEX_TIP, aspect) / size
        val pose = PoseClassifier.classify(lm, aspect)
        val isFist = pose == HandPose.FIST

        // Cursor
        val anchor = lm[if (c.anchor == CursorAnchor.KNUCKLE) Lm.INDEX_MCP else Lm.INDEX_TIP]
        val (mx, my) = mapToScreen(anchor.x, anchor.y, c)
        val tSec = nowMs / 1000.0
        val sx = fx.filter(mx.toDouble(), tSec).toFloat()
        val sy = fy.filter(my.toDouble(), tSec).toFloat()
        history.addLast(Sample(nowMs, sx, sy))
        while (history.size > 2 && nowMs - history.first().t > 500) history.removeFirst()

        // Pinch with hysteresis; a fist always overrides (thumb can rest on the index finger).
        val wasPinched = pinched
        when {
            isFist -> if (pinched) cancelPinch()
            !pinched && ratio < c.pinchOn -> pinched = true
            pinched && ratio > c.pinchOff -> pinched = false
        }
        if (!wasPinched && pinched) {
            pinchStartMs = nowMs
            val p = positionAt(nowMs - c.pinchRewindMs, sx, sy)
            pinchX = p.first
            pinchY = p.second
            pinchCountsAsInput = armed && nowMs - lastArmChangeMs >= c.armGraceMs
        }
        if (wasPinched && !pinched && !isFist) {
            val dur = nowMs - pinchStartMs
            if (armed && pinchCountsAsInput && dur in c.tapMinMs..c.tapMaxMs &&
                nowMs - lastTapMs >= c.tapDebounceMs
            ) {
                events += GestureEvent.Tap(pinchX, pinchY)
                lastTapMs = nowMs
            }
        }

        if (pinched) {
            cursorX = pinchX
            cursorY = pinchY
        } else {
            cursorX = sx
            cursorY = sy
        }

        // Arm / disarm: fist held for armHoldMs. Latched until the pose changes.
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
                    events += GestureEvent.ArmChanged(armed, "fist hold")
                }
            }
        } else {
            armHoldStartMs = null
            armLatched = false
        }

        val approachStart = c.pinchOff + (c.pinchOff - c.pinchOn)
        val pinchProgress = if (pinched) 1f else
            ((approachStart - ratio) / (approachStart - c.pinchOn)).coerceIn(0f, 1f)

        val snapshot = EngineSnapshot(
            handPresent = true,
            armed = armed,
            state = stateOf(),
            cursorX = cursorX,
            cursorY = cursorY,
            rawX = anchor.x,
            rawY = anchor.y,
            pinchRatio = ratio,
            pinchProgress = pinchProgress,
            pose = pose,
            armProgress = armProgress,
            handedness = hand.handedness,
            landmarks = lm,
        )
        return EngineOutput(snapshot, events)
    }

    private fun noHand(nowMs: Long, events: MutableList<GestureEvent>): EngineOutput {
        cancelPinch()
        armHoldStartMs = null
        armLatched = false
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
        )
        return EngineOutput(snapshot, events)
    }

    private fun stateOf() = when {
        !armed -> EngineState.DISARMED
        pinched -> EngineState.PINCHING
        else -> EngineState.HOVER
    }

    private fun cancelPinch() {
        pinched = false
        pinchCountsAsInput = false
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

    companion object {
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
