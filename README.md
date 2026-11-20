# GeoShift

A small Android mock-location utility for testing location-aware apps on a device you control. It uses Android's official mock-provider APIs and keeps an ongoing notification while active.

GitHub: [Bhaveek08/AndroidLocationSpoofer](https://github.com/Bhaveek08/AndroidLocationSpoofer).

## Features

- Search for an address or place with Android's geocoder, or enter latitude and longitude directly.
- Save a **Home** coordinate and load it again with one tap.
- Save and reopen up to 20 named places.
- Preview a selected coordinate in an installed map app.
- Open directly on the device's fresh real-location fix when permission is granted, with the last verified real fix as an offline fallback.
- Use the full-screen MapLibre map with a blue real-location dot, a coral target pin, quick-zoom gestures, large zoom buttons, and smooth recentering.
- Pick Home or any saved place from the coordinate-labeled card rail; long-press a card to rename or remove it, and swipe the sheet to expand or collapse controls.
- See which saved coordinate is selected, animate the target pin as it moves, and use one color-changing start/stop action with clear state feedback.
- Search result labels and map detail depend on OpenStreetMap coverage and Android's device geocoder; verify important coordinates before use.
- Choose the reported accuracy (5 m, 15 m, or 50 m).
- Start a foreground session that refreshes GPS and network test providers every five seconds.
- Verify that Android returns the same coordinates to this app and identifies the fixes as mock locations.
- Stop from the app or from the persistent notification.
- Reject invalid coordinates and surface Android permission/provider errors in the status panel.

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

The test APK install can reset the selected mock app on some devices. The commands above enable the mock-location app operation after both APKs are installed. The device tests load and tap the native map, inject a known coordinate through both Android providers, read both fixes back, check the mock flag and coordinates, verify the foreground service refreshes fixes in the background, and stop the service cleanly. The quick CI workflow runs build, lint, and unit checks. The Android 16 emulator integration suite can be selected in the workflow's manual run options; a connected physical device is also supported.

The `ACCESS_MOCK_LOCATION` permission is included only in the debug APK. The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

Pushing a version tag such as `v0.4.1` runs the release workflow and publishes the verified debug APK to GitHub Releases. This project currently distributes a debug build because no signing key is stored in the repository.
