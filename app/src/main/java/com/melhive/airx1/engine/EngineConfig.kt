package com.melhive.airx1.engine

enum class HandPreference { ANY, LEFT, RIGHT }

/** CURSOR: aim and pinch. SCROLL: raise/lower the hand to scroll, flick for next. */
enum class InputMode { CURSOR, SCROLL }

/** Which point of the hand drives the cursor. */
enum class CursorAnchor {
    /** Index knuckle (landmark 5): barely moves when you pinch, so taps land where you aim. */
    KNUCKLE,
    /** Index fingertip (landmark 8): more intuitive pointing, but shifts during a pinch. */
    FINGERTIP,
}

data class EngineConfig(
    /** Active region of the camera image (normalized) that maps to the full screen. */
    val regionLeft: Float = 0.2f,
    val regionTop: Float = 0.2f,
    val regionRight: Float = 0.8f,
    val regionBottom: Float = 0.8f,
    /** >1 shrinks the active region around its center: smaller hand motion, faster cursor. */
    val sensitivity: Float = 1f,
    /** One Euro filter parameters (normalized screen units per second). */
    val minCutoff: Float = 1.0f,
    val beta: Float = 4f,
    /** Pinch closes below [pinchOn] and opens above [pinchOff] (hysteresis). */
    val pinchOn: Float = 0.30f,
    val pinchOff: Float = 0.45f,
    val handPreference: HandPreference = HandPreference.ANY,
    val anchor: CursorAnchor = CursorAnchor.KNUCKLE,
    /** Set true if the input image is NOT already mirrored. The pipeline mirrors, so default false. */
    val mirrorX: Boolean = false,
    val armHoldMs: Long = 1000,
    val autoDisarmMs: Long = 10_000,
    val mode: InputMode = InputMode.CURSOR,
    /** Pinches shorter than this are glitches. Held this long without moving = long press. */
    val tapMinMs: Long = 50,
    val longPressMs: Long = 600,
    /** Cursor travel (normalized screen units) while pinched that turns a pinch into a drag. */
    val dragStartDist: Float = 0.035f,
    /** Hold time for Back/Home/Recents poses, and for the open-palm launcher. */
    val poseHoldMs: Long = 600,
    val launcherHoldMs: Long = 1000,
    val poseCooldownMs: Long = 800,
    /** Dwell click: keep the cursor within [dwellRadius] for [dwellMs]. */
    val dwellEnabled: Boolean = false,
    val dwellMs: Long = 1000,
    val dwellRadius: Float = 0.025f,
    /** Scroll mode. Dead zone is a fraction of the half-height of the active area. */
    val scrollDeadZone: Float = 0.15f,
    val scrollIntervalMs: Long = 350,
    val flickSpeed: Float = 2.0f,
    /** Motion gain while precision mode is on (keyboard open). */
    val precisionGain: Float = 0.45f,
    /** Scale of the fingertip ghost dots relative to the active area. */
    val fingertipScale: Float = 0.5f,
    val tapDebounceMs: Long = 250,
    /** After arming, ignore pinches for this long (hand is still opening from the fist). */
    val armGraceMs: Long = 500,
    /** Tap lands where the cursor was this long before the pinch closed. */
    val pinchRewindMs: Long = 100,
    /** Reset smoothing if the hand was gone at least this long. */
    val handLostResetMs: Long = 500,
) {
    fun sanitized(): EngineConfig {
        val l = regionLeft.coerceIn(0f, 0.95f)
        val t = regionTop.coerceIn(0f, 0.95f)
        val on = pinchOn.coerceIn(0.05f, 1.5f)
        return copy(
            regionLeft = l,
            regionTop = t,
            regionRight = regionRight.coerceIn(l + 0.05f, 1f),
            regionBottom = regionBottom.coerceIn(t + 0.05f, 1f),
            sensitivity = sensitivity.coerceIn(0.25f, 4f),
            minCutoff = minCutoff.coerceIn(0.05f, 20f),
            beta = beta.coerceIn(0f, 100f),
            pinchOn = on,
            pinchOff = pinchOff.coerceAtLeast(on + 0.03f),
        )
    }
}
