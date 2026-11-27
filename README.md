# GeoShift

An Android mock-location utility for a device you control. It replaces the location Android reports
to other apps, and can walk that reported location along a planned route so an app sees a whole trip
while your real position is never sent anywhere.

It uses Android's official mock-provider API, keeps an ongoing notification while active, and reads
every injected fix back to confirm Android really delivered it.

GitHub: [Bhaveek08/AndroidLocationSpoofer](https://github.com/Bhaveek08/AndroidLocationSpoofer).

## What it does

**Choosing where to be**
- Tap the map, search an address with debounced suggestions from Android's geocoder, or type a
  latitude and longitude. A tap-mode control switches map taps between the spoof target (coral pin)
  and the route destination (gold pin).
- Save a **Home** coordinate and up to 20 named places. Long-press a card to rename or remove it.
- Export and restore Home and saved places as a JSON file you choose. The cached real fix is never
  included in a backup.
- Choose the reported accuracy: 5 m, 15 m, or 50 m.

**Travelling to a destination while spoofed**
- Plan a driving route in the app, then tap **Travel to destination** and pick a speed
  (5, 40, or 100 km/h). The reported location then moves along the route, one fix per second, with a
  real bearing and speed on every fix, and holds the destination on arrival.
- The route start defaults to the **spoof point**, not your real location, so planning and travelling
  a route sends nothing about where you actually are. `My location` is available as an explicit
  alternative, and the consent dialog says which of the two you picked.
- While travelling, the map shows a heading marker, dims the part of the route already covered, and
  optionally follows the trip; the sheet shows distance remaining, ETA, speed and heading.

**Your real location**
- The blue marker is the last real, non-mock fix GeoShift verified, with its age shown. An accuracy
  ring is drawn around it in real metres.
- Android stops reporting real fixes while mock providers are registered. To refresh the blue marker
  mid-session, GeoShift offers to remove the providers for a few seconds and then restore them. That
  window is visible to other apps, so it is always confirmed first, and it is restored automatically
  even if you leave the app mid-refresh.
- A missing real fix never blocks spoofing. GeoShift looks for one for about eight seconds and starts
  regardless.
- **Privacy checkup** reports permission, Android Location, mock-app selection, session and saved-fix
  status, with shortcuts to Android and Google Account location settings, and an action that deletes
  the cached real fix from the device.

**Session**
- A foreground service refreshes the mock fix every second and survives the app going to the
  background. Stop it from the app or from the notification, which shows the reported coordinate or
  the distance remaining on a trip.
- Invalid coordinates, missing permissions and provider errors are surfaced in the status panel.

## What it cannot do

These are platform limits, not missing features:

- **Mock fixes are flagged as mock by Android.** Any app can check `Location.isMock` and reject them.
- **GeoShift cannot make every app use the spoofed location.** Apps can place you from Wi-Fi, cell,
  IP address, cached fixes, or their own account history. If an app keeps showing your real area,
  turn off **Google Location Accuracy** and Wi-Fi/Bluetooth scanning in Android Location settings;
  GeoShift cannot change or inspect those sources.
- **GeoShift cannot read live real GPS while it is spoofing.** The blue marker is a saved fix, and
  refreshing it requires briefly removing the mock providers.
- **Route travel is a simulation, not navigation.** It interpolates the planned polyline at a constant
  speed. It does not follow actual roads beyond that polyline, re-route, or react to traffic.
- Map tiles come from OpenFreeMap, so panning the map reveals the displayed area to that service.
  Address lookups use Android's geocoder. Routing sends the two endpoints to the public OSRM demo
  service, which is best effort and rate limited.
- Home, saved places and the cached real fix live in app-private preferences. Android app backup is
  disabled and nothing is uploaded automatically; export is user-initiated.

## Which providers are mocked

`gps`, `network`, and — on Android 12 (API 31) and newer — the platform `fused` provider. Apps that
read location through Google Play Services see the fused provider, so mocking only `gps` and
`network` leaves them on their own estimate. Each injected fix also carries vertical, speed and
bearing accuracy plus `elapsedRealtimeNanos`, because consumers discard or down-weight fixes that
are missing them. A device that refuses one provider does not fail the session; the rest are used.

## Requirements and setup

- Android 8.0 (API 26) or newer.
- Enable Developer options, then select **GeoShift** under **Select mock location app**.
- Grant precise location when prompted. Keep Android Location services on.
- Direct coordinate entry works without the address search.

## Build and verify

JDK 17 or newer and Android SDK Platform 36.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The JVM unit suite covers coordinate parsing, the OSRM route parser, the saved-places backup codec,
and the route simulator (segment interpolation, bearing, arrival, antimeridian crossing, speed
clamping). It runs on every push in CI.

The device suite needs a real Android runtime. It loads and taps the native map, injects a coordinate
through every provider, reads the fixes back and checks the mock flag, asserts the `fused` provider is
registered where Android supports it, drives a simulated route and asserts the delivered fix actually
moves and carries a speed, stresses the map controls with 100 interactions, and checks the service
refreshes fixes in the background and stops cleanly. Run it on a connected device:

```bash
./gradlew installDebug installDebugAndroidTest
```

```bash
adb shell appops set dev.codex.locationlab android:mock_location allow
```

```bash
adb shell am instrument -w -r dev.codex.locationlab.test/androidx.test.runner.AndroidJUnitRunner
```

Installing the test APK can reset the selected mock app on some devices, which is why the `appops`
command above runs after both APKs are installed. Keep the phone awake and unlocked for the map
tests. In CI, the same suite runs on an Android 16 emulator from the workflow's manual run options.

## Releases

Pushing a tag such as `v1.0.0` builds, verifies and publishes an APK to GitHub Releases.

By default that APK is debug-signed, and the key is generated fresh by each CI run, so Android may
refuse to install it over an older build. To publish with a stable key — which makes updates work —
create one and add it as repository secrets:

```bash
keytool -genkeypair -v -keystore geoshift-release.jks -alias geoshift -keyalg RSA -keysize 4096 -validity 10000
```

```bash
base64 -w0 geoshift-release.jks
```

Add the output as the secret `GEOSHIFT_KEYSTORE_BASE64`, plus `GEOSHIFT_STORE_PASSWORD`,
`GEOSHIFT_KEY_ALIAS` (`geoshift`) and `GEOSHIFT_KEY_PASSWORD`. Keep `geoshift-release.jks` somewhere
safe and out of the repository: losing it means future builds can no longer update an installed app.
The same four values can be set locally as `geoshift.storeFile`, `geoshift.storePassword`,
`geoshift.keyAlias` and `geoshift.keyPassword` Gradle properties.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The `ACCESS_MOCK_LOCATION`
permission is declared only in the debug manifest; on modern Android the mock-app selection in
Developer options is what actually grants the capability.
