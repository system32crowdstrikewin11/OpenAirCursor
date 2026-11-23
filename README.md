# OpenAirCursor

Hands-free, camera-driven cursor for Android. A front-camera hand tracker
(MediaPipe Hand Landmarker) drives one or two on-screen cursors and injects
taps, swipes, long-presses and system gestures through an accessibility service.

## Features

- 1 or 2 hands → 1 cursor (cyan) or 2 cursors (cyan + magenta)
- Thumb + index pinch: tap, swipe, long-press
- Both hands pinch: pinch-to-zoom
- Thumb + middle: back / notifications
- Thumb + ring / pinky: home / recents
- Adjustable sensitivity and smoothing, optional corner preview, mirror mode

## Requirements

- Android 8.0+ (API 26), arm64-v8a
- Front camera
- Accessibility service enabled for gesture injection

## Build

```sh
./gradlew :app:assembleDebug
```

Requires an Android SDK with platform 35, build-tools 35.0.0, NDK 29.0.14206865,
and CMake on `PATH` (SDK CMake packages are x86_64-only and do not run on
aarch64 devices such as Termux).

## CI and releases

- **CI** (`.github/workflows/ci.yml`): builds debug and release APKs on every
  push to `main`, every pull request, and on manual dispatch. The debug APK is
  uploaded as a workflow artifact.
- **Release** (`.github/workflows/release.yml`): runs on `v*` tags or manual
  dispatch, builds the minified release APK, and publishes a GitHub Release
  containing:
  - `OpenAirCursor-<version>.apk`
  - `OpenAirCursor-<version>-mapping.txt` (R8 mapping, for deobfuscating crashes)
  - `SHA256SUMS.txt`

Cut a release by pushing a tag:

```sh
git tag v1.0.1
git push origin v1.0.1
```

The version name is taken from the tag (leading `v` stripped) and the version
code from the workflow run number.

### Release signing

Release builds are signed with the debug key unless you add these repository
secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

A local `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) is also honoured for local release builds and is git-ignored.

## License

Copyright (C) 2026 Dark

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see <https://www.gnu.org/licenses/>.

The full license text is in [LICENSE](LICENSE) (GPL-3.0).
