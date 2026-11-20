# Location Lab

A small Android mock-location utility for testing location-aware apps on a device you control. It uses Android's official mock-provider APIs and keeps an ongoing notification while active.

## Features

- Search for an address or place with Android's geocoder, or enter latitude and longitude directly.
- Save a **Home** coordinate and load it again with one tap.
- Save and reopen up to 20 named places.
- Preview a selected coordinate in an installed map app.
- Choose the reported accuracy (5 m, 15 m, or 50 m).
- Start a foreground session that refreshes GPS and network test providers every five seconds.
- Verify that Android returns the same coordinates to this app and identifies the fixes as mock locations.
- Stop from the app or from the persistent notification.
- Reject invalid coordinates and surface Android permission/provider errors in the status panel.

## Requirements and setup

- Android 8.0 (API 26) or newer.
- Enable Developer options, then select **Location Lab** under **Select mock location app**.
- Grant precise location when prompted. Keep Android Location services on.
- The address search uses Android's `Geocoder`; direct coordinate entry works without search.

Mock locations are marked as mock by Android. This app confirms Android's provider readback; it cannot make every other app use that fix. An app can detect mock coordinates or use network, Wi-Fi, cell, or other signals.

## Build and verify

Use JDK 17 or newer and Android SDK Platform 36. From this directory:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

On a connected phone, first install the debug APK and select Location Lab as the mock app. Then run:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

The device test injects a known coordinate through both Android providers, reads both fixes back, checks the mock flag and coordinates, and removes the providers in `finally`. It requires Location Lab to be selected as the device's mock app.

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.
