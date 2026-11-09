# AirX1

> **Your Hands Are the Interface.**

AirX1 lets you control your real Android phone with air hand gestures seen by the front camera. It drives a cursor and performs real taps on whatever app is open, using Android's AccessibilityService. Everything runs on the phone: AirX1 has **no internet permission** and never saves or sends camera frames.

## Status

**Core build (v0.1).** It covers the camera pipeline, the cursor, arm/disarm, pinch-to-tap, calibration, a debug view, settings and the log. Navigation poses, long press, drag/scroll, Scroll mode, dwell click, the Quick Launcher, keyboard precision mode, the Quick Settings tile and haptics come next. The plan is in [docs/DECISIONS.md](docs/DECISIONS.md).

## Gestures (this build)

| Gesture | Action |
|---|---|
| ✊ Fist, hold 1 s | Arm / disarm (nothing is clicked while disarmed) |
| ☝️ Point and move | Move the cursor |
| 👌 Quick pinch (thumb + index) | Tap |

Safety: the notification **Stop** button turns the camera off. AirX1 also disarms automatically after 10 s with no hand, and when the screen turns off.

## Install

1. Open the latest green run in **Actions → Build APK** and download the **`airx1-apk`** artifact (a zip containing the APK).
2. Install it:
   - From Termux over wireless debugging: `adb install -r airx1-*.apk`
   - Or open the APK on the phone.
3. Open AirX1, then grant **Camera** and **Notifications**.
4. Tap **Open settings** next to *Accessibility service* and enable **AirX1**.
   - **Toggle greyed out?** Android 13+ blocks accessibility for sideloaded apps. Go to *App info → ⋮ → Allow restricted settings*, then try again. Installing with `adb install` usually avoids this.
5. Prop the phone up, run **Calibrate**, press **Start**, and hold a fist for 1 s to arm.

Debug log: `adb logcat -s AirX1`, or the in-app **Log** screen.

## Building

GitHub Actions is the build system (`.github/workflows/build.yml`). It runs on every push and on manual dispatch:

1. JDK 17 and Gradle 8.11.1 (via `gradle/actions/setup-gradle`, no wrapper jar committed).
2. Downloads `hand_landmarker.task` and checks its SHA-256 against `model.sha256`. The model is gitignored.
3. Runs the unit tests (`testDebugUnitTest`).
4. Builds a **release** APK if the signing secrets exist, otherwise a **debug** APK.
5. Fails if the APK requests `android.permission.INTERNET`.
6. Uploads the artifact **`airx1-apk`**.

Local builds (optional) need an Android SDK. `gradle assembleDebug` downloads the model automatically.

## Release signing (one-time, recommended)

Without these secrets, CI builds debug APKs. Each one may be signed with a different throwaway key, which forces you to uninstall before updating, and uninstalling turns the accessibility service off. Set the secrets once and every build updates in place.

```bash
# 1. Create a keystore (keep it safe and never commit it; *.jks is gitignored)
keytool -genkeypair -v -keystore airx1-release.jks -alias airx1 \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. Base64-encode it for the GitHub secret
base64 -w0 airx1-release.jks > airx1-release.jks.b64
```

Then add the four repository secrets (Settings → Secrets and variables → Actions), or use `gh`:

```bash
gh secret set KEYSTORE_BASE64 < airx1-release.jks.b64
gh secret set KEYSTORE_PASSWORD   # the store password you chose
gh secret set KEY_ALIAS --body airx1
gh secret set KEY_PASSWORD        # the key password (same as the store password if you pressed Enter)
```

Switching from debug to release builds changes the signature, so uninstall the debug build once before the first release install.

## Privacy

- No `INTERNET` permission. It is removed in the manifest with `tools:node="remove"` and CI checks the APK.
- Camera frames are processed in memory by MediaPipe and dropped immediately. The Debug view shows live frames only while it is open.
- No analytics, no accounts, no cloud.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, threads, data flow
- [docs/DECISIONS.md](docs/DECISIONS.md): choices, poses, roadmap

## Credits

The architecture follows the pattern of Google's open-source [Project Gameface](https://github.com/google/project-gameface) (Apache-2.0): MediaPipe drives an AccessibilityService cursor. No Gameface code is copied. Hand tracking uses [MediaPipe](https://developers.google.com/mediapipe) (Apache-2.0).
