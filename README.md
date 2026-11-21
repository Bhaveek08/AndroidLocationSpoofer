# GeoShift

A small Android mock-location utility for testing location-aware apps on a device you control. It uses Android's official mock-provider APIs and keeps an ongoing notification while active.

GitHub: [Bhaveek08/AndroidLocationSpoofer](https://github.com/Bhaveek08/AndroidLocationSpoofer).

## Features

- Search for a place with debounced suggestions from Android's geocoder, or enter latitude and longitude directly. Search text is sent to the device geocoding provider as you type.
- Save a **Home** coordinate and load it again with one tap.
- Save and reopen up to 20 named places.
- Export or restore Home and saved places as a user-chosen JSON file. Real-location history is excluded from backups.
- Preview a selected coordinate in an installed map app.
- Open directly on the device's fresh real-location fix when permission is granted, with the last verified real fix as an offline fallback.
- Use a full-screen dark vector map with readable roads and labels, a blue last-real-fix dot, a coral spoof target, a separate gold route destination, quick-zoom gestures, large zoom buttons, and smooth recentering.
- Switch map-tap mode between choosing the spoof point and choosing a directions destination. Search suggestions place the selected result on the map using the active mode.
- Keep the blue marker pinned to the last verified real fix during mock mode, with its age displayed; starting a session waits for a fresh non-mock fix first.
- Plan a driving route in the app from the last saved real fix or a typed address/coordinate, view the highlighted route, and scroll through turn instructions. This is route planning, not live turn-by-turn navigation: Android mock providers prevent this app from independently reading an unmocked live GPS feed while spoofing is active.
- Route lookups send the selected start and destination to the public OSRM routing service; the app displays OpenStreetMap/OSRM credit and labels public routing as best effort.
- Home, saved places, and the cached real fix are stored in app-private on-device preferences; Android app backup is disabled. GeoShift does not automatically upload those saved coordinates. Online map tiles reveal the displayed map area to OpenFreeMap, address lookups use Android's geocoder, and directions ask before sending route locations to external services. Export is user-initiated.
- Pick Home or any saved place from the coordinate-labeled card rail; long-press a card to rename or remove it, and swipe the sheet to expand or collapse controls.
- See which saved coordinate is selected, animate the target pin as it moves, and use one color-changing start/stop action with clear state feedback.
- Map coverage is based on OpenStreetMap data served by OpenFreeMap; search result labels depend on Android's device geocoder. Verify important coordinates before use.
- Choose the reported accuracy (5 m, 15 m, or 50 m).
- Start a foreground session that refreshes GPS and network test providers every second.
- Verify that Android returns the same coordinates to this app and identifies the fixes as mock locations.
- Stop from the app or from the persistent notification.
- Reject invalid coordinates and surface Android permission/provider errors in the status panel.
- Open the read-only Privacy checkup for GeoShift permission, Android Location, mock-app selection, mock-session, and saved real-fix status, with shortcuts to Android app/location settings and Google Account location controls. It cannot inspect or override other apps' independent Wi-Fi, cell, cached, account, or network signals.

## Requirements and setup

- Android 8.0 (API 26) or newer.
- Enable Developer options, then select **GeoShift** under **Select mock location app**.
- Grant precise location when prompted. Keep Android Location services on.
- The address search uses Android's `Geocoder`; direct coordinate entry works without search.

Mock locations are marked as mock by Android. This app confirms Android's provider readback; it cannot make every other app use that fix. An app can detect mock coordinates or use network, Wi-Fi, cell, or other signals.

## Build and verify

Use JDK 17 or newer and Android SDK Platform 36. From this directory:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

On a connected phone, first install the debug APK and select GeoShift as the mock app. Then run:

```powershell
./gradlew.bat installDebug installDebugAndroidTest
adb shell appops set dev.codex.locationlab android:mock_location allow
adb shell am instrument -w -r dev.codex.locationlab.test/androidx.test.runner.AndroidJUnitRunner
```

The test APK install can reset the selected mock app on some devices. The commands above enable the mock-location app operation after both APKs are installed. The device tests load and tap the native map, inject a known coordinate through both Android providers, read both fixes back, check the mock flag and coordinates, stress map controls with 100 interactions, verify saved-place backup round trips, check the foreground service refreshes fixes in the background, and stop the service cleanly. Keep the phone awake and unlocked while map tests run. The quick CI workflow runs build, lint, and unit checks. The Android 16 emulator integration suite can be selected in the workflow's manual run options; a connected physical device is also supported.

The `ACCESS_MOCK_LOCATION` permission is included only in the debug APK. The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

Pushing a version tag such as `v0.9.0` runs the release workflow and publishes the verified debug APK to GitHub Releases. The APK is debug-signed on GitHub Actions; signatures can change between runners, so Android may reject an install-over update. Export saved places before uninstalling an older build if Android reports a signature conflict.
