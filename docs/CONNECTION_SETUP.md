# MG4 built-in hotspot connection setup

This guide covers wireless Android Auto between an MG4 head unit and an Android
phone. It is written for the MG4 running Android 9 (operator-reported working on
Luxury 64 kWh, SWI68-29958-1300R63; not verifiable from source). The test suite has
firewall-script fixtures labeled SWI68 R71 and SWI69 R42, which do not establish
behavior on other revisions. Wi-Fi Direct is
unavailable on this unit; use the car's built-in Wi-Fi hotspot.

## Set up the car hotspot

1. In the MG4 settings, enable the built-in Wi-Fi hotspot. Select 5 GHz if available,
   then note its network name and password.
2. Open DiAuto-MG4 on the head unit and go to **Settings → Connection setup**.
3. Select **Built-in car hotspot**, enter the car's hotspot name and password, and
   save the settings. Keep the car hotspot enabled.
   For this transport, DiAuto-MG4 prefers the BSSID read from the active hotspot
   interface; an Advanced **Static BSSID** is only a fallback if the live address
   cannot be read.
4. Enable Bluetooth and Wi-Fi on the phone, then pair it with the MG4 using the car's
   Bluetooth settings.
5. In DiAuto-MG4, tap **Connect phone** and select the paired phone if prompted.
   Accept Android Auto's first-connection prompts on the phone.

The phone does not need to join the hotspot manually first; DiAuto-MG4 provides the
saved connection details during pairing. A car internet plan is not required for the
local connection, though internet access depends on the phone and vehicle network
configuration. If the hotspot name or password changes, update the saved details. Save the password:
if no password is saved and the live hotspot configuration cannot be read, an empty
passphrase may be advertised and the phone can refuse to join (observed
`status=-3`). Uninstalling removes local app data, so re-enter them after a reinstall.

On a restrictive default-reject firewall the app picks a free allowed port other than
8080/6010; if those are the only allowed ports, it declines to start the wireless
listener (`CarNetworkQuirks`).

USB Android Auto is also available with a USB data cable and **Connect with USB**.
Test one Android Auto host at a time.

## What to test and report

Check first connection, reconnect after restarting the app or car, maps, music/audio,
and USB if applicable. If a feature fails, record the steps, MG4 model/firmware,
phone model/OS, connection type, and app version. Export a report from **Settings →
Diagnostics → Save diagnostic report** and review it before sharing; do not include
the hotspot password.

The `phone` flavor is a regular phone/tablet build without the system UID. Its
CarProperty battery access and ability to force-stop OEM Android Auto apps on an MG4
have not been verified on a vehicle. Use the platform-signed `car` flavor for MG4
vehicle testing.
