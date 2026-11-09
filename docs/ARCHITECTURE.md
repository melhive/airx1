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
     │       • SnapTargets: clickable nodes from all windows       [scan thread]
     │       • InputInjector: tap / long press / swipe / chained-stroke drag
     │       • PanelWindow + TilePanelView: Quick Launcher, AirKeys
     │       • TextInjector: ACTION_SET_TEXT into the focused field
     │       • performGlobalAction, Haptics, keyboard detection → precision
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
| `service/SnapTargets.kt` | Smart Snap: scans clickable/long-clickable/checkable nodes (and keyboard keys) across windows, picks the target under or near the cursor in the topmost window. |
| `service/InputInjector.kt` | dispatchGesture wrappers. Drag = continued strokes chained on completion (24 ms segments). |
| `service/TilePanelView.kt`, `PanelWindow.kt` | Canvas-drawn tile grids in non-focusable overlay windows. Air taps are routed to tiles directly. |
| `service/AirKeys.kt`, `TextInjector.kt` | Big-key keyboard; edits the focused field with ACTION_SET_TEXT / SET_SELECTION / IME_ENTER. |
| `service/AirX1TileService.kt`, `ui/StartActivity.kt` | Quick Settings tile; starts the camera through an invisible activity (foreground rule). |
| `data/Apps.kt` | Launchable apps and Quick Launcher favorites. |
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
DISARMED ──fist 1s──► HOVER ──pinch closes──► PINCHING ──opens <600ms──► Tap
    ▲                  │ ▲                       │  ├─ held 600ms ─► LongPress
    │                  │ └───── pinch opens ─────┘  └─ moves > 3.5% ─► DRAGGING ─opens─► DragEnd
    │                  └─ pose held: ✌️ Back · 🤟 Home · 🖖 Recents (600ms) · 🖐️ Launcher (1s)
    └──fist 1s / 10s no hand / screen off / Stop (any drag is ended first)
```

Scroll mode replaces pinch handling: hand height outside a dead zone → `Scroll(dir, amount)`
every 350 ms; vertical speed > 2 screens/s → `Swipe(dir)`; recentering is required after a flick.

- Pinch ratio = |thumb tip − index tip| / |wrist − middle MCP|. It closes below `pinchOn` and opens above `pinchOff`.
- A tap lands where the cursor was about 100 ms before the pinch closed (pinch rewind). The cursor is frozen while pinching.
- A fist always overrides a pinch, since the thumb can rest on the index finger in a fist.
- For 500 ms after arming, pinches are ignored because the hand is still opening from the fist.

## Threads

- Analysis thread: CameraX analyzer and bitmap conversion.
- MediaPipe internal thread: result callback, which posts to the engine.
- Engine thread: single-thread executor. Every `GestureEngine` call happens here.
- Main thread: overlay rendering and gesture dispatch (collects the flows).
