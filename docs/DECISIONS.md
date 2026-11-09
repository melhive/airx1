# Decisions

Choices made while building, with reasons. Newest at the bottom of each section.

## Process and scope

- **Core first, then the rest.** The first build is a vertical slice (camera → engine → cursor → pinch-tap → calibration) so it can be tested on a real phone before the rest is built. Green CI only proves the code compiles; it can't tell whether a pinch registers as a tap.
- **Roadmap after the core test:**
  1. Navigation poses (Back/Home/Recents), long press, dwell click, Quick Launcher
  2. Pinch-drag (scroll/swipe/glide typing), Scroll mode, keyboard precision mode
  3. Quick Settings tile, haptics, full settings, How-to updates
  4. Later: two-hand zoom, AirX1 big-key keyboard

## Platform and build

- **Versions:** AGP 8.7.3, Kotlin 2.1.0 (Compose compiler plugin 2.1.0), Gradle 8.11.1, Compose BOM 2024.12.01, CameraX 1.4.1, DataStore 1.1.1. These are known to work together with compileSdk 35 and JDK 17.
- **MediaPipe tasks-vision 0.10.35**, the newest 0.10.x release. The 1.x line adds a dependency on `litertlm-android` (an LLM runtime), which this app doesn't need.
- **No Gradle wrapper jar.** CI uses `gradle/actions/setup-gradle` with a pinned `gradle-version: 8.11.1`, as the brief allows.
- **Model URL pinned to version `1`** (`…/float16/1/hand_landmarker.task`) rather than `latest`, with a SHA-256 in `model.sha256`. On 2026-11-09 both URLs served identical bytes. Pinning means an upstream model change can't silently change behavior.
- **ABI filter** arm64-v8a + armeabi-v7a only, to keep the APK smaller. Emulators (x86) aren't supported.
- **INTERNET removed** with `tools:node="remove"` (and `ACCESS_NETWORK_STATE` too), plus a CI check with `aapt2 dump permissions`.
- **Debug fallback signing:** without secrets, CI builds debug. README explains why setting the release secrets early avoids uninstall/re-enable cycles.

## Tracking pipeline

- **CPU delegate by default.** The MediaPipe GPU delegate can crash natively on some devices, and that crash can't be caught. CPU handles one hand at 640x480 comfortably on modern phones. A GPU toggle may come later.
- **Image mirroring in the pipeline, not the engine.** Frames are rotated upright and mirrored before MediaPipe, so landmarks match what you see and MediaPipe's handedness labels are right. `EngineConfig.mirrorX` exists for the engine but defaults to false.
- **Rotation:** ImageAnalysis `targetRotation` follows the display rotation, so landscape works.
- **Throttling:** about 30 fps while a hand has been seen in the last 3 s, otherwise about 12 fps (80 ms minimum interval).
- **`ImageProxy.toBitmap()`** plus a matrix copy per frame: two allocations per frame, but correct for any row stride. Optimize with buffer reuse only if profiling shows GC pressure.
- **Screen off → camera released and engine disarmed.** Screen on rebinds the camera, which is allowed because the camera foreground service is still running.
- **The foreground service is started from the activity only** (Start button or Calibrate), never from the background: Android's while-in-use camera rule.

## Gestures

- **Cursor anchor = index knuckle (landmark 5) by default**, not the fingertip. The fingertip moves toward the thumb during a pinch, so taps landed off target; the knuckle barely moves. The fingertip is available in Settings.
- **Pinch rewind (100 ms) and cursor freeze while pinching**, which fixes the same problem when using the fingertip anchor.
- **Arm/disarm = fist held 1 s**, not open palm as in the original brief. An open palm is the natural resting pose while moving the cursor, so it would toggle by accident. The open palm is reserved for the Quick Launcher, where a misfire is harmless.
- **Fist overrides pinch.** In a fist the thumb often lies on the index finger, which looks like a pinch.
- **500 ms arm grace.** Opening the hand after the fist can briefly look like a pinch.
- **Tap window:** pinch held 50–600 ms. Shorter is a glitch; longer will become a long press.
- **Planned navigation poses** (finger counts, held about 0.6 s with a progress ring and a cooldown afterwards):
  - ✌️ two fingers → Back
  - 🤟 three fingers → Home
  - 🖖 four fingers, thumb folded → Recents
  - 🖐️ open palm held still → Quick Launcher

  Finger counting is what MediaPipe detects most reliably. Holding plus cooldown prevents misfires while moving between poses (☝️ → 🖐️ passes through 2, 3 and 4 fingers).
- **Pose thresholds** (`PoseClassifier`): a finger is extended if its tip is more than 1.12× the PIP distance and more than 1.25× the MCP distance from the wrist; curled if the tip is under 1.1× the MCP distance; the thumb is out if the thumb tip to index MCP distance is over 0.6× hand size. Tuned on synthetic hands, so verify on the phone with the Debug view.

## Safety

- The accessibility service re-checks `engineRunning && armed && !inputSuppressed` before every injection, independent of the engine.
- The Calibration screen sets `inputSuppressed`, so pinching during calibration never clicks anything.
- `FLAG_KEEP_SCREEN_ON` is set on the overlay only while armed; otherwise the screen would time out while you hover.
- `START_NOT_STICKY`: the system never restarts the camera service in the background.

## UI

- No navigation library: a small enum-based screen switcher keeps dependencies minimal.
- Calibration (about 15 s): point with a relaxed thumb → pinch → trace the corners. Thresholds are set at 35% (close) and 55% (open) of the way from the pinched median to the relaxed median. The active area is the 5th to 95th percentile of the anchor positions, inset by 5% so edges are reachable.
