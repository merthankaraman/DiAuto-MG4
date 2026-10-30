# DiAuto-MG4

Wireless / USB **Android Auto** for the MG4 Android head unit (tested on SWI68), with live
battery SoC forwarded into Google Maps via Android Auto `VehicleEnergyModel`
(sensor types 23/25).

> Fork of [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0).
> MG4 telemetry: vendored [EVHardware](https://github.com/fatihdonmezdev/MG4ABRP) in `evhardware/`.
> For Apple CarPlay on the same car, use **DiPlay-MG4** separately — do not run both at once.

## Features (MG4)

- Native wireless Android Auto over the car’s built-in hotspot (Android 9; Wi‑Fi Direct unavailable).
- `car` flavor: platform-signed `android.uid.system` build for MG4 CarProperty / BMS access.
- `phone` flavor: ordinary phone/tablet build for desk testing; it has no system UID.
- Optional **Battery for Google Maps** (default on): SoC + range → AA sensors 23/25.
- **Demo battery values**: manual SoC / range / capacity (skips EVHardware; desk testing).
- Application id: `com.drivehub.diauto.mg4` (`phone` flavor: `com.drivehub.diauto.mg4.phone`).

The phone flavor is not a substitute for the platform-signed car build: it is not
expected to read MG4 SoC/BMS data through CarProperty or force-stop OEM Android Auto
apps. Phone-flavor behavior on an MG4 has not been verified on a vehicle.

## Tested configuration

- Operator-reported vehicle test: MG4 Luxury 64 kWh, infotainment firmware **SWI68-29958-1300R63**, `car` flavor v1.9.8 (platform-signed), working. This is not verifiable from the source code.
- Other firmware/trim combinations are untested.
- `CarNetworkQuirksTest` contains firewall-script fixtures labeled SWI68 R71 and SWI69 R42 (DROP rules only, no whitelist, so the listener falls back to port 30518). They do not establish that every SWI68/SWI69 revision, including R63, uses the same rules.
- The code catalogue records battery power and battery temperature as never published on SWI68, based on captures on SWI68-29958-1300R67 (`CarPropertyEvidence.kt`); this is not an R63-specific measurement.

## Installing APKs on the MG4

Install only the platform-signed `-car.apk` (release asset). The `car` flavor uses
`android.uid.system`; an APK signed with any other key fails with "App not installed".
In the app, save and verify the car hotspot SSID and password. If live hotspot
configuration cannot be read and no password is saved, an empty passphrase may be
advertised and the phone can refuse to join (observed: `status=-3`, `pskLen=0`).

If you haven't sideloaded apps before:

1. Open **Bluetooth Settings** and select the car name.
2. Hold down on the **comma** until the settings appear.
3. Select **Android Keyboard Settings**.
4. Select **Languages**.
5. Use the search box at the top to search for **file**.
6. That should give you access to the **Files** app.
7. From there, select a connected USB device with APKs on it and install as usual.

## Build

**MG4 car** (platform-signed, `android.uid.system`):

```powershell
$env:MG4_PLATFORM_KEYS_DIR = "C:\path\to\platform-keys"  # platform.pk8 + platform.x509.pem
powershell -File scripts\build_mg4.ps1
adb install -r ..\DiAuto-MG4-v1.9.8.apk
```

The script writes `..\DiAuto-MG4-vX.Y.Z.apk`; when publishing a release, upload it as
`DiAuto-MG4-vX.Y.Z-car.apk` (the OTA picker matches on the `-car` / `-phone` suffix).

> Gradle `carRelease` (`app\build\outputs\apk\car\release\*.apk`) is not platform-signed:
> depending on local keystore configuration it is release-signed or unsigned. Do not
> install it on the car unless its certificate has been verified; the head unit rejects
> it ("App not installed"). The `build_mg4` scripts build `carDebug` and sign that APK
> with the external platform key.

**Normal phone / tablet** (debug keystore, Demo battery):

```powershell
powershell -File scripts\build_phone.ps1
adb install -r ..\DiAuto-MG4-phone-debug.apk
```

In Android Studio: open `DiAuto-MG4`, pick **phoneDebug** or **carDebug**.

See [docs/MG4_TESTING.md](docs/MG4_TESTING.md) for the vehicle checklist.

## Release / MVP

With JDK 17+, Android SDK configured, and the release keystore available as
`headunit-release-key.jks` in the repository root (or its parent), configure signing
and build the requested variant. Gradle reads `key.properties` first, then
`secrets.properties`; otherwise it uses these PowerShell environment variables:

```powershell
$env:HEADUNIT_KEYSTORE_PASSWORD = "your-keystore-password"
$env:HEADUNIT_KEY_PASSWORD = "your-key-password"
.\gradlew.bat :app:assemblePhoneRelease
.\gradlew.bat :app:assembleCarRelease
```

The release signing config uses the `headunit-revived` alias unless overridden by
`key.properties`. `phoneRelease` is the normal, no-system-UID MVP build. `carRelease`
is for local use and must be signed with the MG4 platform certificate to retain the
system UID; a regular app-signing key does not grant that privilege.

Telegram crash-report upload is optional. Debug variants (including `carDebug`, the
variant the `build_mg4` scripts sign) read `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`;
release variants read `TELEGRAM_BOT_TOKEN_RELEASE` and `TELEGRAM_CHAT_ID_RELEASE`.
Credentials embedded in any APK are extractable: leave the applicable pair unset for
every artifact you distribute.

### Releases and OTA updates

Releases are published at <https://github.com/mastroiannim/DiAuto-MG4/releases>. For
each release, publish both flavor assets: `DiAuto-MG4-vX.Y.Z-car.apk` (platform-signed,
for the head unit) and `DiAuto-MG4-vX.Y.Z-phone.apk`. The app checks this repository's GitHub releases for
OTA updates and prefers the asset matching the installed flavor by its `-car` /
`-phone` suffix. This is a preference, not a hard filter, so publish both assets.

### Google Maps SoC note

Battery % / arrival charge is shown primarily on **Android Auto Maps** (the projected
head-unit UI) once a route is active. The phone’s own Maps screen often has no permanent
SoC badge; that is expected Google behavior when VEM is delivered over AA.

## Upstream

See upstream DiAuto README / docs for general AA host features. MG4-specific changes
live in this fork.
