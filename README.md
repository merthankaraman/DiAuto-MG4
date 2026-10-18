# DiAuto-MG4

Wireless / USB **Android Auto** for the MG4 Android head unit (SWI69), with live
battery SoC forwarded into Google Maps via Android Auto `VehicleEnergyModel`
(sensor types 23/25).

> Fork of [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0).
> MG4 telemetry: vendored [EVHardware](https://github.com/fatihdonmezdev/MG4ABRP) in `evhardware/`.
> For Apple CarPlay on the same car, use **DiPlay-MG4** separately — do not run both at once.

## Features (MG4)

| Area | What it does | Where to look |
|---|---|---|
| Wireless Android Auto | Over the car's built-in hotspot (Android 9; Wi‑Fi Direct unavailable). Hotspot recovery if it starts after the app. | [docs/HOTSPOT_RECOVERY.md](docs/HOTSPOT_RECOVERY.md) |
| Wired Android Auto | USB, with reconnect handling. | [docs/CONNECTION_SETUP.md](docs/CONNECTION_SETUP.md) |
| Auto-start | Starts wireless Android Auto when the phone connects over Bluetooth ("Auto-start on Bluetooth" setting). | Settings |
| Battery in Google Maps | SoC + range sent as Android Auto sensors 23/25 (default on). | [docs/MG4_TESTING.md](docs/MG4_TESTING.md) |
| Demo battery values | Manual SoC / range / capacity, skips EVHardware (desk testing). | Settings |
| Audio | Hybrid mode (music over the car's Bluetooth, navigation/assistant over Android Auto), AAC option, focus handling. | Settings |
| Microphone | Selectable source and processing (AEC / noise suppression). | Settings → Microphone |
| Steering-wheel / hard keys | Reads the SAIC hard-key broadcast (`com.saic.keyevent.hardkey.report`) plus other head-unit key actions. | `CarKeyBroadcastReceiver.kt` |
| Display | Screen mode, optionally hiding only the left bar. | Settings |
| Platform install | Platform-signed `android.uid.system` build for CarProperty / BMS access. | Build section |

Application id: `com.drivehub.diauto.mg4` (phone desk build: `.phone` suffix).

## Status

Early, owner-tested project. Target: MG4 Comfort, SWI69, Android 9. Other trims and
firmware (e.g. SWI68) are not covered. Release notes in [CHANGELOG.md](CHANGELOG.md) still
describe the upstream BYD builds; MG4 changes are in the git history. The vehicle
checklist in [docs/MG4_TESTING.md](docs/MG4_TESTING.md) is the reference for what has
to be verified on a real car. A short demo video would help readers judge the current state.

## Installing APKs on the MG4

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
adb install -r ..\DiAuto-MG4-v0.3.11-mg4.1.apk
```

**Normal phone / tablet** (debug keystore, Demo battery):

```powershell
powershell -File scripts\build_phone.ps1
adb install -r ..\DiAuto-MG4-phone-debug.apk
```

In Android Studio: open `DiAuto-MG4`, pick **phoneDebug** or **carDebug**.

See [docs/MG4_TESTING.md](docs/MG4_TESTING.md) for the vehicle checklist.

### Google Maps SoC note

Battery % / arrival charge is shown primarily on **Android Auto Maps** (the projected
head-unit UI) once a route is active. The phone’s own Maps screen often has no permanent
SoC badge; that is expected Google behavior when VEM is delivered over AA.

## Upstream

See upstream DiAuto README / docs for general AA host features. MG4-specific changes
live in this fork.
