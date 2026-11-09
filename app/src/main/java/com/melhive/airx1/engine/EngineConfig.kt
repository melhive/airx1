package com.melhive.airx1.engine

enum class HandPreference { ANY, LEFT, RIGHT }

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
    /** Pinches shorter than this are glitches, longer than [tapMaxMs] are not taps. */
    val tapMinMs: Long = 50,
    val tapMaxMs: Long = 600,
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
