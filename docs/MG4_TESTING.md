# DiAuto-MG4 — vehicle test checklist

Platform: MG4 / Android 9 (AUTUS SAIC MT2712). Operator-reported working with v1.9.8
on MG4 Luxury 64 kWh, firmware SWI68-29958-1300R63 (not verifiable from source;
other revisions untested).  
Car APK: platform-signed `com.drivehub.diauto.mg4` (see `scripts/build_mg4.ps1`).
Phone APK: `com.drivehub.diauto.mg4.phone`, without the system UID.

Do **not** run DiPlay-MG4 CarPlay and DiAuto-MG4 Android Auto at the same time.

## Safety gate

Before any in-vehicle test, complete [the MG4 rollback and recovery
checklist](MG4_ROLLBACK.md). Test while parked, keep the OEM Android Auto blocker
off for the initial run, and stop immediately on vehicle-function anomalies or
unexpected package/signature errors. Do not treat the phone flavor as a substitute
for validating the platform-signed car flavor.

## Build & install

1. Host smoke (JDK 17+, `local.properties` → `sdk.dir`):
   ```
   .\gradlew.bat :app:compileCarDebugKotlin :app:testCarDebugUnitTest --tests com.andrerinas.openheadunit.vehicle.VehicleEnergyModelEncoderTest
   ```
2. `$env:MG4_PLATFORM_KEYS_DIR = "<dir with platform.pk8 and platform.x509.pem>"`
3. `powershell -File scripts\build_mg4.ps1`
4. `adb install -r ..\DiAuto-MG4-v1.9.8.apk` (the `build_mg4` scripts build `carDebug` and sign it with the external platform key. Do not use the Gradle `carRelease` output unless its certificate is verified: it is not platform-signed.)

Phone desk APK (no platform key): `powershell -File scripts\build_phone.ps1` then
`adb install -r ..\DiAuto-MG4-phone-debug.apk`. Use Demo battery values for desk tests.
The phone flavor is not expected to read MG4 SoC/BMS through CarProperty or force-stop
OEM Android Auto apps; these behaviors have not been verified on a vehicle.

EVHardware is vendored at `evhardware/` (no git submodule).
See [README.md](../README.md) for `phoneRelease` and `carRelease` builds.

## Connection (Android 9 car hotspot)

1. Open DiAuto-MG4 on the head unit.
2. Settings → Connection setup → **Built-in car hotspot** (nativeApTransport = 1; default).
3. Save the car hotspot SSID/password exactly.
4. On the phone: Wi‑Fi + Bluetooth on, pair, connect Android Auto wirelessly.
5. Confirm projection video, touch, and audio.

## Battery → Google Maps (VehicleEnergyModel)

1. Settings → **Şarj bilgileri** (above Diagnostics) → **Battery for Google Maps** ON (default).
2. Optional desk test: enable **Demo battery values**, set SoC / range / capacity, Save.
   Demo skips EVHardware (works off the car). Still needs a real AA session for Maps.
3. Logcat filter: `DiAuto-MG4`
4. Expect before/during connect:
   - `DiAuto-MG4 battery XX% range YYkm (demo)` or `(car)`
5. After an Android Auto session starts and the phone requests sensor 23:
   - `DiAuto-MG4 phone requested VEM sensor type=23`
   - `DiAuto-MG4 vem tx capacity=...Wh current=...Wh range=...km battery=...%`
6. On **Android Auto Maps on the head unit**: pick a destination and start — charging
   stops + arrival SoC should appear. That projected AA Maps surface is the path Google
   wires to VehicleEnergyModel.
7. **Phone Google Maps app** (unlock phone → open Maps → destination → Start) is a
   different UI. Even with a live AA session and working VEM (`vem tx` in logs), that
   phone chrome often shows a normal route **without** charging stops / arrival SoC.
   That is Google Maps behaviour, not a missing DiAuto sensor: if the head-unit AA Maps
   already shows the EV plan, the energy model is reaching the phone.
8. If VEM is not requested: confirm Service Discovery logged
   `Announcing EV energy sensors (23/25/26) + ELECTRIC fuel type`  
   then reconnect after battery settings / first SoC log.

## Regression

- [ ] Music playback
- [ ] Touch / map pan
- [ ] Navigation voice prompts
- [ ] Disconnect / reconnect wireless AA
- [ ] USB AA (optional)

## Known implementation limits

- Restrictive firewall: 8080/6010 are excluded as common system ports; if no other
  whitelisted free port exists, wireless AA does not start (port 0). `CarNetworkQuirks.kt`.
- ARP wake-up is a bounded sweep of at most 50 addresses just below the subnet
  broadcast, with 150 ms–1 s backoff, not a repeated scan of the subnet. `ArpNudger.kt`.
- SAIC `SRC_AA` is claimed when playback enters PLAYING and is no longer reclaimed
  periodically if the car switches source during playback. `AapService.kt`.
- OTA prefers the matching flavor asset but is not a hard filter; publish and verify
  both APK assets. `OtaReleaseAssets.java`.
- Debug variants (including `carDebug`) embed `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`
  if configured; do not distribute such APKs. `app/build.gradle.kts`.
- Gradle `carRelease` is not platform-signed by the car build scripts; only the
  `build_mg4` `carDebug` output is externally signed with the platform key.

## Failure hints

| Symptom | Likely cause |
|---|---|
| No `battery XX%` log | Not platform-signed / CPM denied / unsupported or unidentified firmware / CarProperty unavailable |
| Phone log `status=-3`, `pskLen=0` | No hotspot password saved or readable; set it in Connection setup |
| "App not installed" | APK not signed with the platform key; use the `-car.apk` asset |
| EV sensors not announced | No snapshot yet — wait for poll, reconnect |
| `vem tx dropped` | Phone never started sensor 23 |
| HU AA Maps has EV plan, phone Maps does not | Expected Google split: use head-unit AA Maps |
| Neither surface shows SoC / charge stops | Confirm `vem tx` + EV announce; Maps/AA versions |
