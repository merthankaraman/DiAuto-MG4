# Install DiAuto-MG4

DiAuto-MG4 is an Android Auto host for the MG4 Android head unit (operator-reported
test on MG4 Luxury 64 kWh, firmware SWI68-29958-1300R63, Android 9; not verifiable
from source, and other revisions, including SWI69, are untested). The `car` build is intended for the head unit and requires
the matching platform signature for its system UID features. The `phone` build is a
separate, ordinary Android app for desk testing; it is not verified as a vehicle
installation.

## Choose a build

| Build | Package ID | Intended use |
| --- | --- | --- |
| `car` | `com.drivehub.diauto.mg4` | MG4 head unit; platform-signed |
| `phone` | `com.drivehub.diauto.mg4.phone` | Phone/tablet desk testing; no system UID |

The phone build is not expected to read MG4 SoC/BMS through CarProperty or force-stop
OEM Android Auto apps. These phone-flavor behaviors have not been verified on a
vehicle; use the platform-signed `car` build for MG4 testing.

## Install on the MG4

1. Build or obtain a platform-signed `car` APK: the `DiAuto-MG4-vX.Y.Z-car.apk`
   release asset or the output of `scripts\build_mg4.ps1` (`DiAuto-MG4-vX.Y.Z.apk`). Do not install an
   ordinary `phone` APK when testing the MG4 system-UID features, and do not use
   `app\build\outputs\apk\car\release\*.apk` unless its certificate has been verified:
   Gradle does not platform-sign it (it is release-signed or unsigned depending on the
   local keystore), and the head unit rejects it with "App not installed".
2. Transfer the APK to a USB drive. On the head unit, open Bluetooth Settings and
   select the car name.
3. Hold the comma key to open the settings, then choose **Android Keyboard Settings**
   → **Languages**. Search for **file** to open the Files app.
4. Select the USB drive and install the APK using the head unit's permitted method.
   If installation is unavailable, use ADB only if enabled and permitted by the
   vehicle firmware.
5. Open DiAuto-MG4, grant the requested permissions, pair the phone in the car's
   Bluetooth settings, and choose **Connect phone**. Accept Android Auto's prompts
   on the phone.

For USB Android Auto, use a USB data cable and **Connect with USB**.

## Install a phone build

Install the `phone` APK on a phone or tablet using Android's normal APK installation
flow. It is intended for UI and desk testing; use **Demo battery values** to supply
test SoC/range values without MG4 telemetry.

## Updates and signing

Android permits in-place updates only when the package ID and signing certificate
match. The `car` and `phone` variants have different package IDs and cannot update
one another. Preserve the existing variant's signing key when updating it. Export
settings before uninstalling; uninstalling removes local app data.

See [README.md](../README.md) for build commands and the Release/MVP signing setup.
See [MG4 testing](MG4_TESTING.md) for the vehicle checklist.
Before installing the `car` build, follow the [MG4 rollback and recovery
runbook](MG4_ROLLBACK.md). Do not proceed without a tested recovery path.

## Troubleshooting

- If installation is blocked, check the head unit's firmware policy; ADB is optional
  and may not be enabled.
- "App not installed" for the `car` APK means the signing certificate is not the MG4
  platform certificate (`android:sharedUserId="android.uid.system"`). Install the
  `-car.apk` release asset instead.
- If the phone logs `WifiConnectStatus status=-3` and `pskLen=0`, no hotspot password
  was available: with no saved password and no readable live configuration an empty
  passphrase can be advertised, and Android Auto usually refuses to join. Re-enter
  SSID and password in **Settings → Connection setup** (uninstalling removes local app data).
- If the phone does not connect, confirm Bluetooth pairing, Wi-Fi is enabled, and the
  MG4 built-in hotspot details are correct in DiAuto-MG4's connection settings.
- For reports, include MG4 model/firmware, phone model/Android version, connection
  type, app version, and reproducible steps. Review diagnostic logs for identifiers
  or network details before sharing.
