# Axiom Web Radar

Android 6.0+ landscape app that displays the Martinique Meteo France radar in a fullscreen embedded viewer with playback controls similar to the site version.

## What It Does

- Uses the template Android project and keeps GitHub Actions as the intended build path.
- Uses a native Android `View` to render the Antilles, Martinique `200 km`, and Martinique `50 km` presets.
- Draws basemap, contours, radar frame, and GPS marker directly on a `Canvas`.
- Provides native zoom, pan, previous, play/pause, next, refresh, timeline, and speed controls.
- Overlays the device GPS position with a passive marker and accuracy circle.
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
