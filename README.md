# AirX1

> **Your Hands Are the Interface.**

AirX1 lets you control your real Android phone with air hand gestures seen by the front camera. It drives a cursor and performs real taps on whatever app is open, using Android's AccessibilityService. Everything runs on the phone: AirX1 has **no internet permission** and never saves or sends camera frames.

## What it does (v0.2)

**Cursor mode**: aim and pinch, like a mouse in the air

| Gesture | Action |
|---|---|
| ✊ Fist, hold 1 s | Arm / disarm (nothing is clicked while disarmed) |
| ☝️ Point and move | Move the cursor. It locks onto buttons and icons (**Smart Snap**) |
| 👌 Quick pinch | Tap |
| 👌 Pinch and hold | Long press |
| 👌 Pinch and move | Drag, scroll, swipe, glide typing |
| ✌️ Two fingers, hold | Back |
| 🤟 Three fingers, hold | Home |
| 🖖 Four fingers (thumb folded), hold | Recents |
| 🖐️ Open palm, hold 1 s | **Quick Launcher**: your 8 favorite apps, Alerts, Quick settings, Scroll mode, Keys |
| ⏱️ Hold still (optional) | Dwell click |

**Scroll mode**, for feeds and reading: hand above the middle scrolls up, below scrolls down (further means faster). A quick flick goes to the next or previous item.

**Typing**: **AirKeys**, a big-key keyboard that opens automatically when you tap a text field while armed. Or switch to Gboard and glide-type with pinch-and-move, or use the mic.

**Feedback**: your five fingertips shown as dots, rings that fill before a click or pose fires, vibration, and a Quick Settings tile to turn AirX1 on and off.

**Safety**: the notification **Stop** button turns the camera off. AirX1 disarms after 10 s with no hand and when the screen turns off. A drag in progress is always released.

## Download

**On your Android phone, open:**

### 👉 https://github.com/melhive/airx1/releases/latest/download/airx1.apk

Tap the downloaded file and choose **Install**. The first time, Android asks you to allow "Install unknown apps" for your browser.

- This link always gives the **newest version**, so you can share it with anyone.
- Updates install **over** the old version and keep your settings.
- All versions: https://github.com/melhive/airx1/releases

## Set up (2 minutes)

1. Open **AirX1** and tap **Grant** for Camera and Notifications.
2. Tap **Open settings** next to *Accessibility service* and turn on **AirX1** (under *Installed apps* or *Downloaded apps*).
   - **Toggle greyed out?** Android 13+ blocks accessibility for apps installed from a browser. Go back to AirX1 and tap **Open App info**, then tap **⋮** (top right) → **Allow restricted settings**, and turn AirX1 on again.
3. Prop the phone up so the front camera can see your hand, 40–70 cm away.
4. Tap **Calibrate** and follow the three steps (about 15 s).
5. Tap **Start AirX1**, then hold a ✊ fist for 1 s to arm. The cursor turns into a ring.

Debug log: the in-app **Log** screen, or `adb logcat -s AirX1` over USB or wireless debugging.

## Building

GitHub Actions is the build system. Nothing needs to be installed locally.

| Workflow | When | Output |
|---|---|---|
| `release.yml` | every push to `main`, or manual | Signed **GitHub Release** `v0.1.<N>` with `airx1.apk`, marked Latest |
| `build.yml` | pushes to other branches, pull requests, or manual | Artifact `airx1-apk`: a **debug** APK ("AirX1 Debug", `com.melhive.airx1.debug`) that installs next to the real app |

Both workflows download `hand_landmarker.task`, check its SHA-256 against `model.sha256`, run the unit tests, and fail if the APK requests `android.permission.INTERNET`. Releases also verify the APK signature. `N` is the release run number and becomes the `versionCode`, so every release is newer than the last.

Local builds (optional) need an Android SDK. `gradle assembleDebug` downloads the model automatically.

## Release signing

Releases are signed with one key stored as repository secrets. This is what lets Android install each release as an update. **Keep a private backup of the keystore and its password** (for example a USB stick and a password manager). If they are lost, installed copies can't be updated: users would have to uninstall and reinstall.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 airx1-release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_PASSWORD` | key password (same as the keystore password for PKCS12) |

The key alias is `airx1`. To create a key (only if starting over):

```bash
keytool -genkeypair -v -keystore airx1-release.jks -alias airx1 \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 airx1-release.jks > airx1-release.jks.b64
```

## Privacy

- No `INTERNET` permission. It is removed in the manifest with `tools:node="remove"` and CI checks the APK.
- Camera frames are processed in memory by MediaPipe and dropped immediately. The Debug view shows live frames only while it is open.
- No analytics, no accounts, no cloud.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, threads, data flow
- [docs/DECISIONS.md](docs/DECISIONS.md): choices, poses, roadmap

## Credits

The architecture follows the pattern of Google's open-source [Project Gameface](https://github.com/google/project-gameface) (Apache-2.0): MediaPipe drives an AccessibilityService cursor. No Gameface code is copied. Hand tracking uses [MediaPipe](https://developers.google.com/mediapipe) (Apache-2.0).
