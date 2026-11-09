# Decisions

Choices made while building, with reasons. Newest at the bottom of each section.

## Process and scope

- **Core first, then the rest.** The first build is a vertical slice (camera → engine → cursor → pinch-tap → calibration) so it can be tested on a real phone before the rest is built. Green CI only proves the code compiles; it can't tell whether a pinch registers as a tap.
- **v0.2 built all planned features in one go** at the user's request, before on-device results for v0.1 were in. Tuning will follow device testing.
- **Later:** two-hand zoom, an experimental 5-finger tap mode, an optional GPU delegate.

## Platform and build

- **Versions:** AGP 8.7.3, Kotlin 2.1.0 (Compose compiler plugin 2.1.0), Gradle 8.11.1, Compose BOM 2024.12.01, CameraX 1.4.1, DataStore 1.1.1. These are known to work together with compileSdk 35 and JDK 17.
- **MediaPipe tasks-vision 0.10.35**, the newest 0.10.x release. The 1.x line adds a dependency on `litertlm-android` (an LLM runtime), which this app doesn't need.
- **No Gradle wrapper jar.** CI uses `gradle/actions/setup-gradle` with a pinned `gradle-version: 8.11.1`, as the brief allows.
- **Model URL pinned to version `1`** (`…/float16/1/hand_landmarker.task`) rather than `latest`, with a SHA-256 in `model.sha256`. On 2026-11-09 both URLs served identical bytes. Pinning means an upstream model change can't silently change behavior.
- **ABI filter** arm64-v8a + armeabi-v7a only, to keep the APK smaller. Emulators (x86) aren't supported.
- **INTERNET removed** with `tools:node="remove"` (and `ACCESS_NETWORK_STATE` too), plus a CI check with `aapt2 dump permissions`.
- **Distribution like ZENITH:** every push to `main` publishes a signed GitHub Release with a fixed asset name (`airx1.apk`), so `releases/latest/download/airx1.apk` always installs the newest version. The release fails if signing secrets are missing; it never ships an unsigned or debug APK.
- **versionCode = release workflow run number**, versionName `0.1.<N>`.
- **Debug builds use `applicationIdSuffix .debug`** ("AirX1 Debug"), so they install next to the release and never hit signature conflicts.
- **No in-app update check** (ZENITH has one): it would need the INTERNET permission, which breaks the privacy promise. Users get updates by opening the release link again.

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

## v0.2 features

- **Smart Snap** reads clickable, long-clickable and checkable nodes from all interactive windows (plus leaf nodes with text in the keyboard window, for Gboard keys). It ignores containers covering more than 25% of the screen and only considers targets in the topmost window under the cursor. The radius is 28 dp. Taps hit the target's center; the cursor is drawn 30% of the way towards it. Scans run off the main thread, at most every 250 ms, and only after a screen change (or every 1.5 s) while armed.
- **Five fingertip dots, but only the index dot clicks.** A front camera can't reliably detect a finger "press" in the air, and five shaky dots on small keys would mistype.
- **Drag = chained continued strokes** (`StrokeDescription.continueStroke`). Each 24 ms segment is dispatched when the previous one completes, so one finger stays down. `DragEnd` is accepted even after disarming, so a finger is never left down.
- **Scroll semantics:** `Scroll(dir)` is the content direction (UP reveals content above, so the finger moves down). `Swipe(dir)` is the finger direction (a hand flick up means a finger swipe up, which is the next item in TikTok, Reels and Shorts). Swipes stay between 28% and 72% of the screen height to avoid edge gestures.
- **Recenter after a flick:** without it, a hand left high after a flick up would immediately scroll the opposite way.
- **Precision mode** (keyboard or AirKeys visible) uses relative motion at 0.45× gain. When it ends, the cursor returns to absolute mapping (a small jump).
- **AirKeys** types with ACTION_SET_TEXT instead of being an input method: no IME switching, and Gboard stays available. Some fields (many WebViews) reject SET_TEXT, and AirKeys then says to use Gboard. It opens automatically when a keyboard appears while armed in Cursor mode, unless you chose Gboard for that field.
- **Panels (Launcher, AirKeys) are drawn on a Canvas** in non-focusable overlay windows, so the app keeps text focus. Air taps call tiles directly; finger taps also work. The cursor overlay is re-added after a panel opens so it stays on top.
- **Quick Launcher defaults:** if no favorites are picked, it uses common apps that are installed (YouTube, WhatsApp, Chrome, TikTok, …), then alphabetical. Uses a `<queries>` launcher intent rather than QUERY_ALL_PACKAGES.
- **QS tile → invisible StartActivity → start service**, because the camera foreground service must start from the foreground.
- **Haptics:** a click for taps and actions, a heavy click for long press and disarm, a double click for arm.
- **Version scheme:** v0.1.0–v0.2.1 (codes 1–3) were published by hand; CI releases use run number + 3 and name `0.2.<N>`.
- **Always-visible status (v0.2.1):** a status pill at the top (show your hand / hold a fist to arm / armed), a small live hand outline in the corner (no camera image), and a clearly visible white disarmed cursor. Users could not tell whether AirX1 was working when the disarmed cursor was a faint grey dot.
