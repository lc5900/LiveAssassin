# LiveAssassin

[中文说明 (Simplified Chinese)](./README.zh-CN.md)

LiveAssassin is an Android app for USB capture-card preview and audio playback.

## Features
- USB capture-card video preview (UVC)
- USB capture-card audio loopback playback
- Fullscreen immersive mode
- Front-camera picture-in-picture (PIP) overlay with adjustable position and size
- Capture resolution selector (defaults to highest supported mode)
- Per-ABI CI builds (`arm64-v8a`, `armeabi-v7a`)

## Requirements
- Android Studio (Hedgehog or newer recommended)
- JDK 11
- Android phone with OTG / USB Host support
- UVC-compatible USB capture card

## Quick Start
1. Open this project in Android Studio.
2. Run once to sync dependencies (internet required).
3. Connect your phone and USB capture card via OTG.
4. Launch the app and grant permissions:
- Camera
- Microphone
- USB device access (system prompt)

## Local Build
Single ABI debug APK:

```bash
./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a
./gradlew :app:assembleDebug -PtargetAbi=armeabi-v7a
```

Single ABI release APK:

```bash
./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a
./gradlew :app:assembleRelease -PtargetAbi=armeabi-v7a
```

Release bundle (AAB):

```bash
./gradlew :app:bundleRelease
```

## Usage
1. Connect the capture card to the phone via OTG.
2. Tap `Start Capture`.
3. Tap `Rotate Fullscreen` for immersive view; tap preview area to hide/show floating controls.
4. Enable `Front PIP` for front-camera overlay and adjust size/position with sliders.

## Notes
- `targetSdk` is currently set to `33` for compatibility with USB broadcast registration behavior in upstream dependencies.
- Audio output routing priority: Bluetooth output device, then wired headset, then phone speaker.

## ABIs & Artifacts
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`
- Debug workflow output:
- `app-debug-arm64-v8a.apk`
- `app-debug-armeabi-v7a.apk`
- Release workflow output:
- `app-release-arm64-v8a.apk`
- `app-release-armeabi-v7a.apk`
- `app-release.aab`

## Release
Create and push a version tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

Tag push triggers release workflow and uploads APK/AAB artifacts.

## License
Licensed under [Apache License 2.0](./LICENSE).
