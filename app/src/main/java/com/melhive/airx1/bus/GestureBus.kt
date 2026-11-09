package com.melhive.airx1.bus

import android.graphics.Bitmap
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.GestureEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process hub connecting HandTrackingService (producer), AirX1AccessibilityService
 * (consumer: cursor + input injection) and the UI (status, debug).
 */
object GestureBus {
    private val _events = MutableSharedFlow<GestureEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GestureEvent> = _events

    fun emit(event: GestureEvent) {
        _events.tryEmit(event)
    }

    /** Latest engine state, updated every processed frame. */
    val snapshot = MutableStateFlow(EngineSnapshot())

    val engineRunning = MutableStateFlow(false)
    val accessibilityConnected = MutableStateFlow(false)
    val fps = MutableStateFlow(0f)
    val delegate = MutableStateFlow("")

    /**
     * When true, the accessibility service drops every input event (used while calibrating,
     * so pinches on the calibration screen don't click anything).
     */
    val inputSuppressed = MutableStateFlow(false)

    /** True while a keyboard (Gboard or AirKeys) is visible: the engine slows the cursor. */
    val precision = MutableStateFlow(false)

    /** Camera frames for the Debug view. Only produced while [previewViewers] > 0; never saved. */
    val preview = MutableStateFlow<Bitmap?>(null)
    val previewViewers = AtomicInteger(0)
}
