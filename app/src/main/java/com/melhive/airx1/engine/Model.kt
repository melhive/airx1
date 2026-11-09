package com.melhive.airx1.engine

import kotlin.math.hypot

/** One MediaPipe landmark, normalized to [0,1] in the (already rotated + mirrored) camera image. */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

enum class Handedness { LEFT, RIGHT, UNKNOWN }

/**
 * One detected hand. [aspect] = image width / height, used so distances are measured in
 * square units even though x and y are normalized separately.
 */
data class HandFrame(
    val landmarks: List<Landmark>,
    val handedness: Handedness = Handedness.UNKNOWN,
    val aspect: Float = 1f,
)

/** MediaPipe hand landmark indices. */
object Lm {
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20
}

internal fun List<Landmark>.dist(a: Int, b: Int, aspect: Float): Float =
    hypot((this[a].x - this[b].x) * aspect, this[a].y - this[b].y)

enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class NavAction { HOME, BACK, RECENTS, NOTIFICATIONS }

/** Discrete outputs of the engine. Coordinates are normalized screen coordinates in [0,1]. */
sealed interface GestureEvent {
    data class Tap(val x: Float, val y: Float) : GestureEvent
    data class LongPress(val x: Float, val y: Float) : GestureEvent
    data class DragStart(val x: Float, val y: Float) : GestureEvent
    data class DragMove(val x: Float, val y: Float) : GestureEvent
    data class DragEnd(val x: Float, val y: Float) : GestureEvent
    data class Swipe(val direction: Direction, val x: Float, val y: Float) : GestureEvent
    data class Scroll(val direction: Direction, val amount: Float) : GestureEvent
    data class Zoom(val scale: Float, val x: Float, val y: Float) : GestureEvent
    data class GlobalAction(val action: NavAction) : GestureEvent
    data class ArmChanged(val armed: Boolean, val reason: String) : GestureEvent
}

enum class EngineState { DISARMED, HOVER, PINCHING }

/** Continuous state, published every frame (cursor position, debug values). */
data class EngineSnapshot(
    val handPresent: Boolean = false,
    val armed: Boolean = false,
    val state: EngineState = EngineState.DISARMED,
    /** Smoothed cursor, normalized screen coordinates. */
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
    /** Unmapped anchor position in the camera image (for calibration). */
    val rawX: Float = Float.NaN,
    val rawY: Float = Float.NaN,
    /** Thumb–index distance divided by hand size. */
    val pinchRatio: Float = Float.NaN,
    /** 0 = fingers apart, 1 = pinch threshold reached. */
    val pinchProgress: Float = 0f,
    val pose: HandPose = HandPose.NONE,
    /** Progress (0..1) of the fist hold that toggles arm/disarm. */
    val armProgress: Float = 0f,
    val handedness: Handedness = Handedness.UNKNOWN,
    val landmarks: List<Landmark> = emptyList(),
)

data class EngineOutput(val snapshot: EngineSnapshot, val events: List<GestureEvent>)
