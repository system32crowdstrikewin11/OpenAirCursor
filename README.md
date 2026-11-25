# OpenAirCursor

Touchless, dual-hand mid-air cursor and gesture controller for Android. Uses the
front camera and on-device 21-point hand tracking (MediaPipe Hand Landmarker) to
drive one or two cursors and inject taps, swipes, long-presses, and system
actions through Android's Accessibility Service.

- **100% Offline & Tracker-Free:** No `INTERNET` permission, no GMS, and MediaPipe's DataTransport telemetry is stripped at build time.
- **Dual-Hand Support:** Dynamically spawns 1 cursor (Cyan) or 2 cursors (Cyan + Magenta) with mid-air pinch-to-zoom.
- **Precision Controls:** One-Euro filter + display-rate smoothing, pre-pinch damping, optional corner preview, mirror mode, and 1-tap `Reset Touch Input` for Android 15/16 `InputDispatcher` recovery.

## Gestures

| Gesture | Action |
| --- | --- |
| **1 or 2 hands** | Spawns 1 cursor (Cyan) or 2 cursors (Cyan + Magenta) |
| **Thumb + Index** | Quick pinch = Tap · Double pinch = 2x Click · Drag = Swipe · Hold = Long press |
| **Both hands pinch** | Pinch-to-Zoom |
| **Thumb + Middle** | Quick pinch = Back |
| **Thumb + Ring** | Quick pinch = Home |
| **Thumb + Pinky** | Quick pinch = Recents |

## Requirements

- Android 8.0+ (API 26), `arm64-v8a`
- Front camera
- Accessibility Service enabled for gesture injection

## Building from Source

Requires JDK 17 and Android SDK (platform 35, build-tools 35.0.0).

```sh
./scripts/fetch-model.sh
./gradlew :app:assembleRelease
```

### Model Provenance

`scripts/fetch-model.sh` downloads Google's Apache-2.0 licensed MediaPipe Hand Landmarker model (FP16) and verifies its SHA-256 before building:

- **URL:** `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
- **SHA-256:** `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`

### Release Signing

Release builds produce an unsigned APK by default (compatible with F-Droid's signing server). Provide either a git-ignored `keystore.properties` locally or `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets in GitHub Actions to sign release builds. For local testing without a keystore, pass `-PsignWithDebug=true`.

## License

Copyright (C) 2026 Dark

Licensed under the [GNU General Public License v3.0](LICENSE)
