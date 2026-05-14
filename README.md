# Axiom Web Radar

Android 6.0+ landscape app that displays the Martinique Meteo France radar in a fullscreen embedded viewer with playback controls similar to the site version.

## What It Does

- Uses the template Android project and keeps GitHub Actions as the intended build path.
- Opens a local `WebView` UI for the Antilles mosaic preset.
- Opens a local `WebView` UI for the Martinique `200 km` preset.
- Opens a local `WebView` UI for the Martinique `50 km` preset.
- Provides zoom, previous, play/pause, next, refresh, timeline, and speed controls.
- Overlays the device GPS position with a marker, accuracy circle, and follow toggle.
- Bundles Leaflet locally instead of loading it from a remote CDN at startup.
- Fetches a fresh Meteo France session token at runtime so the protected WMS radar layers can load.

## Target

- Android 6.0 compatible via `minSdk 23`
- `armeabi-v7a` only
- fullscreen landscape layout suitable for embedded displays

## Build Path

GitHub Actions workflow: [android-ci.yml](/C:/Gregor/Projects/Axiom%20Web%20Radar/.github/workflows/android-ci.yml)

The workflow installs Android platform `34` and build tools `34.0.0`, builds `:app:assembleDebug`, and uploads the debug APK as a workflow artifact.

## Notes

- This workspace was updated without running a local Android build.
- The radar viewer depends on live access to `https://meteofrance.mq` and `https://rwg.meteofrance.com`.
