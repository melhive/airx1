package com.melhive.airx1.engine

/**
 * Synthetic hands (palm facing the camera, fingers pointing up, aspect 1).
 * Hand size (wrist → middle MCP) = 0.2.
 */
object TestHands {
    enum class Thumb { OUT, IN, PINCH }

    private val mcpX = floatArrayOf(0.44f, 0.50f, 0.55f, 0.60f)
    private val mcpY = floatArrayOf(0.60f, 0.60f, 0.61f, 0.63f)

    fun hand(
        index: Boolean,
        middle: Boolean,
        ring: Boolean,
        pinky: Boolean,
        thumb: Thumb = Thumb.IN,
        dx: Float = 0f,
        dy: Float = 0f,
        handedness: Handedness = Handedness.RIGHT,
    ): HandFrame {
        val p = Array(21) { Landmark(0f, 0f) }
        p[0] = Landmark(0.50f, 0.80f)
        p[1] = Landmark(0.42f, 0.75f)
        p[2] = Landmark(0.38f, 0.70f)
        p[3] = Landmark(0.35f, 0.65f)
        p[4] = when (thumb) {
            Thumb.OUT -> Landmark(0.28f, 0.62f)
            Thumb.IN -> Landmark(0.52f, 0.66f)
            Thumb.PINCH -> Landmark(0.36f, 0.52f)
        }
        val ext = booleanArrayOf(index, middle, ring, pinky)
        for (f in 0 until 4) {
            val base = 5 + f * 4
            val x = mcpX[f]
            p[base] = Landmark(x, mcpY[f])
            if (ext[f]) {
                p[base + 1] = Landmark(x, 0.50f)
                p[base + 2] = Landmark(x, 0.45f)
                p[base + 3] = Landmark(x, 0.40f)
            } else {
                p[base + 1] = Landmark(x, 0.55f)
                p[base + 2] = Landmark(x, 0.60f)
                p[base + 3] = Landmark(x, 0.66f)
            }
        }
        if (thumb == Thumb.PINCH) {
            // Index bends so its tip meets the thumb tip.
            p[6] = Landmark(0.42f, 0.52f)
            p[7] = Landmark(0.39f, 0.50f)
            p[8] = Landmark(0.37f, 0.51f)
        }
        val moved = p.map { Landmark(it.x + dx, it.y + dy) }
        return HandFrame(moved, handedness, 1f)
    }

    fun fist(dx: Float = 0f, dy: Float = 0f) = hand(false, false, false, false, Thumb.IN, dx, dy)
    fun point(dx: Float = 0f, dy: Float = 0f) = hand(true, false, false, false, Thumb.IN, dx, dy)
    fun pinch(dx: Float = 0f, dy: Float = 0f) = hand(false, false, false, false, Thumb.PINCH, dx, dy)
    fun two() = hand(true, true, false, false)
    fun three() = hand(true, true, true, false)
    fun four() = hand(true, true, true, true, Thumb.IN)
    fun openPalm() = hand(true, true, true, true, Thumb.OUT)
}
