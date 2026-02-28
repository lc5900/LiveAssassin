# LiveAssassin

[中文说明 (Simplified Chinese)](./README.zh-CN.md)

Android USB capture-card video/audio preview app with:
- USB capture-card video preview (UVC)
- USB capture-card audio loopback playback
- Fullscreen immersive mode
- Front-camera picture-in-picture (PIP) overlay with adjustable position and size

## Requirements
- Android Studio (Hedgehog or newer recommended)
- JDK 11
- Android phone with OTG / USB Host support
- UVC-compatible USB capture card

## Build & Run
1. Open the project in Android Studio.
2. Build once to download dependencies (internet required).
3. Connect your phone and run the `app` module.
4. Grant app permissions:
   - Camera
   - Microphone
   - USB device access (system prompt)

## Usage
1. Connect the capture card to the phone via OTG.
2. Tap `Start Capture`.
3. Tap `Rotate Fullscreen` for immersive view; tap preview area to hide/show floating controls.
4. Enable `Front PIP` for front-camera overlay and adjust size/position with sliders.

## Notes
- `targetSdk` is currently set to `33` for compatibility with USB broadcast registration behavior in upstream dependencies.
- Audio output routing priority: Bluetooth output device, then wired headset, then phone speaker.

## ABIs & Artifacts
- Supported native ABIs by default: `arm64-v8a`, `armeabi-v7a`.
- Build a single ABI locally:
  - `./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a`
  - `./gradlew :app:assembleRelease -PtargetAbi=armeabi-v7a`
- CI generates per-ABI APKs:
  - `app-debug-arm64-v8a.apk`
  - `app-debug-armeabi-v7a.apk`
  - `app-release-arm64-v8a.apk`
  - `app-release-armeabi-v7a.apk`
- Release workflow also generates a universal `AAB` (for store distribution).

## License
Licensed under [Apache License 2.0](./LICENSE).
