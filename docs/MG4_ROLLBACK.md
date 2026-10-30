# MG4 rollback and recovery — DiAuto car 1.9.8

This is a conservative operator runbook, not an SAIC service procedure. The rollback
procedure itself has not been validated on a vehicle. The operator reports running
v1.9.8 on SWI68-29958-1300R63; this cannot be verified from source. Use only on the
documented MG4 / Android 9 target, while parked, and stop rather than improvising if
the head unit behaves differently.

## Go/no-go before installation

- [ ] Confirm and record the exact MG4 model, SWI firmware, Android version, DiAuto
      version, and baseline behavior.
- [ ] Verify stock Android Auto, Bluetooth calls/audio, touchscreen, navigation,
      climate, ADAS, camera, and ignition behavior before changing anything.
- [ ] Export DiAuto settings and verify the export is readable. Uninstall removes
      DiAuto's local data.
- [ ] Keep the known-good APK and its SHA-256 outside the head unit, plus a separate
      USB copy. Do not rely on the app's OTA cache as the rollback copy.
- [ ] Record which OEM packages exist and their enabled state:
      `com.allgo.app.androidauto`, `com.saicmotor.caradapter`,
      `com.allgo.carplay.service`, and `com.allgo.rui`. Do not modify these packages.
- [ ] If ADB is permitted, authorize and test the USB data connection before the
      install. `adb devices -l` must show the intended serial as `device`. Do not use
      ADB over Wi-Fi as the only recovery channel.
- [ ] Have a second person, the stock UI path, and the dealer/support contact
      available. If no supported recovery path is available, **NO-GO**.
- [ ] Keep **Block OEM Android Auto while DiAuto runs** disabled for initial testing.
      It is off by default; enabling it force-stops OEM Android Auto and caradapter.
- [ ] Do not run `scripts/install.sh` from older project revisions. The current
      script installs only after interactive confirmation and does not change global
      settings; it does not restore changes made by older revisions.

Example read-only ADB checks (replace `SERIAL` with the authorized device serial):

```powershell
adb devices -l
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell pm path com.drivehub.diauto.mg4
adb -s SERIAL shell pm path com.allgo.app.androidauto
adb -s SERIAL shell pm path com.saicmotor.caradapter
adb -s SERIAL shell pm path com.allgo.carplay.service
adb -s SERIAL shell pm path com.allgo.rui
```

ADB availability and the OEM package set vary by head unit; an empty package
lookup is not permission to guess a package name or install/remove OEM components.

## Stop conditions

Stop testing immediately for a persistent crash/reboot loop, black screen, unexpected
ADB or Package Manager refusal, signature/shared-UID conflict, missing/unlaunchable
OEM packages, loss of stock Android Auto/Bluetooth, or any anomaly in ADAS, climate,
camera, instruments, ignition, or other vehicle functions. Do not try alternate
privileged flags, firmware/recovery images, factory reset, 12 V disconnection, or
commands against OEM packages. Contact MG service if the stock UI or vehicle
functions do not return after the normal user-level recovery below.

## Recovery procedures

### DiAuto is crashing, but Settings are reachable

1. Park safely and stop the test.
2. In DiAuto, turn **Block OEM Android Auto while DiAuto runs** off and save.
3. If DiAuto cannot open but Android Settings/ADB work, stop only DiAuto:

   ```powershell
   adb -s SERIAL shell am force-stop com.drivehub.diauto.mg4
   ```

4. Export logs/settings if possible, then uninstall with the controlled procedure
   below. Do not clear OEM app data.

### Stock Android Auto no longer starts

The blocker uses runtime `force-stop` for `com.allgo.app.androidauto` and
`com.saicmotor.caradapter`; it does not use `pm disable`. Stop/remove DiAuto, then
launch stock Android Auto from the normal vehicle UI and test USB, Bluetooth calls,
and audio while parked. If the stock apps do not start after one normal user-level
restart, stop and contact service. Do not run `pm enable`, `pm disable`, `pm clear`,
or uninstall/install commands on OEM packages.

### Installation or update reports a signature/shared UID conflict

Stop at the first error. Capture the message and leave the installed package intact
if it still works. Android requires compatible package identity/signing for an
in-place update and shared UID; do not bypass the check, uninstall the existing app
to experiment, or add `-d`, `-f`, `-t`, `install-existing`, or other flags. Request
an approved build/install path from the project maintainer or SAIC service.

### Black screen or repeated head-unit reboot

Do not force repeated power cycles, disconnect 12 V/fuses, enter undocumented
recovery/bootloader modes, or flash an APK/firmware image. If an already-authorized
ADB connection is stable, the single-package stop/uninstall procedure may be used
once. If it is not stable or the UI remains black, stop and contact MG service.

### App update / firmware OTA

Keep the known-good APK outside the head unit before using DiAuto's updater. Do not
start an app update during vehicle validation; the car updater may install after
the download/hash check, and a downgrade may be rejected. Do not interrupt a
manufacturer firmware OTA or disable OEM OTA services. Distinguish an app update
from a vehicle firmware update; neither has a verified on-vehicle rollback path in
this project.

## Controlled DiAuto uninstall

Uninstalling deletes DiAuto's local data. Export settings first if possible. The
PowerShell helper requires the exact ADB serial and a typed confirmation; it only
targets `com.drivehub.diauto.mg4`, verifies the device state, and verifies removal:

```powershell
.\scripts\rollback_mg4.ps1 -Serial SERIAL
```

If ADB is unavailable and Android Settings are accessible, use the normal
Android **App info → DiAuto-MG4 → Uninstall** flow. If either route is refused,
stop; do not try per-user removal or package-manager workarounds.

After removal:

- Confirm `adb -s SERIAL shell pm path com.drivehub.diauto.mg4` returns no package
  path (or confirm it is absent from App info).
- Launch stock Android Auto from the vehicle UI; check USB, pairing, calls, and
  audio.
- Compare climate, ADAS, camera, instruments, ignition, hotspot, and location
  behavior with the recorded baseline. Complete two normal cold starts and one
  stock USB session before declaring recovery complete.
- If anything differs, stop testing and contact MG service; do not reinstall to
  troubleshoot.

## Older install-script side effects

Older `scripts/install.sh` revisions changed `location_mode`, app-ops, runtime
permissions, and the device-idle whitelist without saving their previous values.
Uninstalling DiAuto does not necessarily restore those settings. If that older
script was used, restore only values recorded before installation, or use the
normal user-facing Android settings. Never guess a previous global value.

## Checklist

### Before

- [ ] Exact firmware/version and normal vehicle baseline recorded.
- [ ] DiAuto settings export verified; known-good APK and hash stored off-device.
- [ ] OEM package presence recorded without modifying packages.
- [ ] ADB USB authorized and tested, or an alternate supported recovery path known.
- [ ] Vehicle parked; observer and service contact available.
- [ ] OEM blocker off; no concurrent Android Auto/CarPlay host app.
- [ ] No firmware update in progress.

### After a test or rollback

- [ ] DiAuto stopped/uninstalled only through a supported path.
- [ ] Stock Android Auto, Bluetooth calls/audio, and USB verified while parked.
- [ ] Vehicle functions match baseline; two normal starts completed.
- [ ] No unknown global settings were changed or guessed.

**No-go:** if any precondition is missing, do not install the `car` build. **Stop
immediately:** on any unexpected package/signature error, persistent reboot/black
screen, loss of stock functionality, or vehicle-function warning.

## Limits

This plan does not establish that ADB is enabled, that uninstall is permitted, that
the listed OEM packages exist on every SWI68/SWI69 build (test fixtures cover only
SWI68 R71 and SWI69 R42), or that a prior APK can be
installed as a downgrade. It does not validate firmware OTA behavior or recovery
from a vehicle-specific head-unit failure. Those require the exact vehicle and,
where system-level recovery is needed, MG service.
