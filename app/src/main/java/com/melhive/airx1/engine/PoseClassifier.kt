package com.melhive.airx1.engine

/**
 * Static hand poses, detected from finger extension. Finger counting is used because
 * MediaPipe is far more reliable at it than at subtle thumb or wrist angles.
 */
enum class HandPose { NONE, FIST, POINT, TWO, THREE, FOUR, OPEN_PALM, OTHER }

object PoseClassifier {
    private val FINGERS = listOf(
        intArrayOf(Lm.INDEX_TIP, Lm.INDEX_PIP, Lm.INDEX_MCP),
        intArrayOf(Lm.MIDDLE_TIP, Lm.MIDDLE_PIP, Lm.MIDDLE_MCP),
        intArrayOf(Lm.RING_TIP, Lm.RING_PIP, Lm.RING_MCP),
        intArrayOf(Lm.PINKY_TIP, Lm.PINKY_PIP, Lm.PINKY_MCP),
    )

    /** Tip clearly beyond both its PIP and MCP joints (measured from the wrist). */
    internal fun extended(lm: List<Landmark>, aspect: Float): BooleanArray = BooleanArray(4) { i ->
        val (tip, pip, mcp) = FINGERS[i].let { Triple(it[0], it[1], it[2]) }
        val dTip = lm.dist(tip, Lm.WRIST, aspect)
        dTip > lm.dist(pip, Lm.WRIST, aspect) * 1.12f && dTip > lm.dist(mcp, Lm.WRIST, aspect) * 1.25f
    }

    /** Tip folded back to about the knuckle line or closer to the wrist. */
    internal fun curled(lm: List<Landmark>, aspect: Float): BooleanArray = BooleanArray(4) { i ->
        val tip = FINGERS[i][0]
        val mcp = FINGERS[i][2]
        lm.dist(tip, Lm.WRIST, aspect) < lm.dist(mcp, Lm.WRIST, aspect) * 1.1f
    }

    fun thumbOut(lm: List<Landmark>, aspect: Float): Boolean {
        val size = lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect)
        return lm.dist(Lm.THUMB_TIP, Lm.INDEX_MCP, aspect) > 0.6f * size
    }

    fun classify(lm: List<Landmark>, aspect: Float): HandPose {
        if (lm.size < 21) return HandPose.NONE
        if (lm.dist(Lm.WRIST, Lm.MIDDLE_MCP, aspect) < 1e-4f) return HandPose.NONE
        if (curled(lm, aspect).all { it }) return HandPose.FIST
        val e = extended(lm, aspect)
        return when {
            e[0] && !e[1] && !e[2] && !e[3] -> HandPose.POINT
            e[0] && e[1] && !e[2] && !e[3] -> HandPose.TWO
            e[0] && e[1] && e[2] && !e[3] -> HandPose.THREE
            e[0] && e[1] && e[2] && e[3] -> if (thumbOut(lm, aspect)) HandPose.OPEN_PALM else HandPose.FOUR
            else -> HandPose.OTHER
        }
    }
}
