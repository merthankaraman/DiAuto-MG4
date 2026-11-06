# Automatic Wi-Fi Direct BSSID recovery

Included in v0.3.2. Initially tested on 22 September 2026; the release also keeps
credential refresh from replacing the group while Bluetooth waits for its address.

## Problem

On the tested BYD DiLink 5.1 / Android 13, clearing Static BSSID reproduces a
wireless startup failure. Bluetooth reaches the phone and the 5 GHz group is
created, but ordinary app MAC lookups return null or masked addresses. Location
and nearby-device permissions are already enabled. The native handshake aborts
before sending Wi-Fi credentials.

Previously this unit required manually provisioning its group address using ADB.
That cannot serve users whose cars do not expose ADB.

## Change

The active group interface on this firmware has a modified EUI-64 IPv6 link-local
address derived from its current MAC. `P2pInterfaceBssid` reverses that encoding,
using `NetworkInterface.inetAddresses` within DiAuto's own process. The encoding
is described in [RFC 4291, Appendix A](https://www.rfc-editor.org/rfc/rfc4291#appendix-A).

- Runs when automatic MAC lookup is masked and the head unit owns the group.
- Reads only the named group interface; it does not select a station interface,
  nearby phone, router address, or a hard-coded car address.
- Requires `fe80::/64` and the modified EUI-64 `ff:fe` marker; rejects opaque
  identifiers, multicast/zero/masked results, and conflicting candidates.
- Retains explicit Static BSSID overrides and the existing MAC lookup paths.
- Allows IPv6 to arrive within the existing 15-second address-wait budget, with
  the existing generation check preventing delivery after group replacement.
- Does not save the recovered address in settings. Subsequent groups are read
  again, so the mechanism does not depend on a MAC remaining stable.
- Adds no permissions, shell commands, root dependency, phone app or ADB grants.

## Verification

- 581 JVM unit tests pass, including five new tests covering local/universal MAC
  decoding, malformed/opaque/non-link-local input, placeholders, ambiguous
  candidates, and absent interfaces.
- Debug and optimized release builds pass, including release vital lint.
- Live car: Static BSSID cleared to Auto, process restarted, automatic resolution
  logged from `p2p0`, Wi-Fi session established, SSL completed and video rendered.
- Optimized non-debuggable build installed with the existing private-preview
  certificate to preserve the owner's settings. Projection started with the ADB
  client disconnected. `run-as` was denied, confirming this was not a debug build.
- After the head-unit restart command, the Android framework/processes restarted
  and a newly named P2P group was created. Opening DiAuto again recovered its
  address, completed the handshake and rendered video. Kernel uptime did not
  reset, so this is a framework/head-unit restart test, not a cold power-cycle test.

The original on-car test used a non-debuggable development artifact with the
private-preview certificate. The public v0.3.2 release uses the production
certificate and versionCode 102. The existing v0.3.1 release does not contain this fix.

The isolated v0.3.2 release was retested on 23 September: automatic interface
recovery, a 5200 MHz group, successful TLS and first rendered frame at 01:01:41
with the saved 1080p preference. This on-car release test again used the existing
private-preview signing key; the public artifact certificate was separately verified.

## Limits

This is a targeted recovery method for interfaces using MAC-derived IPv6
identifiers. Firmware using opaque IPv6 identifiers or no IPv6 may still need
another solution. Tested with the owner's OnePlus 15, not the issue reporter's
Samsung S21 Ultra. Do not claim universal wireless compatibility from this test.
ADB was used to install and inspect the test build; the resolver itself uses only
ordinary app APIs. Disconnecting the ADB client is not the same test as disabling
the ADB service in system settings.

## Follow-up: interface name race and a second device (25 September)

- **Race fixed.** `group.interface` is null on Android 11+, and when group info
  arrived before the kernel assigned `192.168.49.1` the interface lookup failed
  once and was never repeated, so all 15 waits read IPv6 from no interface and
  the handshake aborted with a masked BSSID. The wait loop now looks the interface
  up by the group-owner address on every pass and always reads it at least once.
- **Current group wins.** When the address came from a fallback that may describe
  another group or interface (`lastKnownBssid`, device address, sysfs scan), the
  IPv6 address of the group's own interface replaces it once readable. A Static
  BSSID is still never overridden.
- **Readable failure.** If nothing is recovered, the log names the cause: no
  interface name, no IPv6 link-local, or only opaque identifiers.
- **Second device.** HiBy R4 (Qualcomm, Android 12, SELinux enforcing, P2P MAC
  randomization supported) hosting the group with a debug build: `p2p0` link-local
  `fe80::48ab:cdff:feef:0123` decoded to `4A:AB:CD:EF:01:23`, equal to the kernel's
  `link/ether`, and a Samsung S25 Ultra (Android 16) scan listed `DIRECT-GH-HeadUnit`
  at that BSSID. The address stayed the same across group recreation and a Wi-Fi
  off/on. A full Android Auto session was not run on this device.
- **How common opaque identifiers are.** Both devices report `addr_gen_mode` 0
  (EUI-64) for `default`, `p2p0` and `wlan0`, so a newly created P2P interface gets
  a MAC-derived link-local address. Stable-privacy link-local addresses were not
  observed; the Limits above still apply to firmware that changes this.
- **Ruled out.** Reading the MAC from the kernel over nl80211 (generic netlink) is
  denied by SELinux for both `shell` and the app's domain on a user build, so it
  cannot serve as a fallback.
