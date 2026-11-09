# AirX1 Architecture

Single process. Components communicate through the `GestureBus` singleton.

```
 Front camera
     │  CameraX ImageAnalysis (640x480 RGBA, KEEP_ONLY_LATEST)        [analysis thread]
     ▼
 HandTracker ── rotate upright + mirror ──► MediaPipe HandLandmarker   [MediaPipe thread]
     │                                        (LIVE_STREAM, 1 hand, CPU)
     ▼  HandFrame(21 landmarks, handedness, aspect) | null
 HandTrackingService (foreground, type=camera)                          [engine thread]
     │  GestureEngine.process(frame, now)  ← EngineConfig ← DataStore settings
     ▼
 GestureBus ── snapshot: StateFlow<EngineSnapshot>  (cursor, state, debug values)
           └─ events:   SharedFlow<GestureEvent>    (Tap, ArmChanged, …)
     │
     ├──► AirX1AccessibilityService                                     [main thread]
     │       • CursorOverlayView (TYPE_ACCESSIBILITY_OVERLAY, not touchable)
     │       • dispatchGesture / performGlobalAction
     │       • FLAG_KEEP_SCREEN_ON on the overlay while armed
     └──► MainActivity (Compose): Home, Calibrate, Debug, Settings, Log, How to use
```

## Components

| File | Role |
|---|---|
| `engine/GestureEngine.kt` | Pure Kotlin state machine: cursor mapping, One Euro smoothing, pinch hysteresis, poses, arm/disarm, taps. No Android imports; unit-tested. |
| `engine/PoseClassifier.kt` | Finger-count poses (fist, point, two, three, four, open palm). |
| `engine/OneEuroFilter.kt` | Adaptive low-pass filter for the cursor. |
| `service/HandTracker.kt` | CameraX → bitmap → MediaPipe. Frame throttling, fps, screen-rotation tracking. |
| `service/HandTrackingService.kt` | Foreground service that owns the tracker and engine; notification with Stop; disarms and releases the camera when the screen turns off. |
| `service/AirX1AccessibilityService.kt` | Overlay cursor and input injection. Re-checks `engineRunning && armed && !inputSuppressed` before every injection. |
| `bus/GestureBus.kt` | Flows shared by all components. |
| `data/Settings.kt` | DataStore settings → `EngineConfig`. |
| `log/LogBuffer.kt` | 500-line in-memory ring buffer, mirrored to logcat tag `AirX1`. |
| `ui/*` | Compose screens. `Calibrator` (in `CalibrationScreen.kt`) is pure and unit-tested. |

## Coordinate spaces

1. **Camera image**: CameraX delivers sensor-oriented frames. `HandTracker` rotates them by `rotationDegrees`, where the target rotation follows the display rotation, then mirrors horizontally. Landmarks are then in a selfie view that matches what you see, which is also the input MediaPipe's handedness labels assume.
2. **Active region**: a rectangle in the image (default the center 60%, or set by calibration) that maps to the full screen, then is scaled around its center by `sensitivity`.
3. **Screen**: the engine emits normalized [0,1] coordinates. The accessibility service multiplies them by the real display size.

## Engine state machine

```
DISARMED ──fist 1s──► HOVER ──pinch closes──► PINCHING ──opens <600ms──► Tap → HOVER
    ▲                    │                        │
    └──fist 1s / 10s no hand / screen off / Stop ─┘
```

- Pinch ratio = |thumb tip − index tip| / |wrist − middle MCP|. It closes below `pinchOn` and opens above `pinchOff`.
- A tap lands where the cursor was about 100 ms before the pinch closed (pinch rewind). The cursor is frozen while pinching.
- A fist always overrides a pinch, since the thumb can rest on the index finger in a fist.
- For 500 ms after arming, pinches are ignored because the hand is still opening from the fist.

## Threads

- Analysis thread: CameraX analyzer and bitmap conversion.
- MediaPipe internal thread: result callback, which posts to the engine.
- Engine thread: single-thread executor. Every `GestureEngine` call happens here.
- Main thread: overlay rendering and gesture dispatch (collects the flows).
