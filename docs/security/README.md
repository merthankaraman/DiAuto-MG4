# DiAuto-MG4 Security Advisories & Vulnerability Reports

This directory contains the security advisories and pull request issue templates generated from the audit of update commits (v1.8.2 `d31639c` through v1.9.6 `ba70b5b`).

## Summary of Vulnerabilities

| Advisory | Severity | Component | Issue Summary |
| :--- | :--- | :--- | :--- |
| [PR-1](PR-1_Critical_abortBroadcast_OEM_key_hijack.md) | **Critical** (8.6) | `CarKeyBroadcastReceiver.kt` | Unconditional `abortBroadcast()` hijacks all OEM steering wheel hard keys (volume, phone, cluster). |
| [PR-2](PR-2_High_forced_reconnection_loop.md) | **High** (7.1) | `AapService.kt` / `AutoStartReceiver.kt` | Forced reconnection loop (Denial of Exit) triggered by Bluetooth ACL reconnection. |
| [PR-3](PR-3_High_UDP_flood_ArpNudger.md) | **High** (7.5) | `ArpNudger.kt` | Uncontrolled UDP flooding (~20k packets) & kernel ARP neighbor table saturation on failed connection. |
| [PR-4](PR-4_MediumHigh_port_conflict_CarNetworkQuirks.md) | **Medium-High** (6.5) | `CarNetworkQuirks.kt` | Deceptive port availability check via `SO_REUSEADDR` & conflict with common ports (8080, 6010). |
| [PR-5](PR-5_Medium_radio_silencing_AudioFocus_leak.md) | **Medium** (5.3) | `AapService.kt` / `SaicCarAudioSource.kt` | Periodic 8s coercive muting of OEM FM/DAB radio & `AudioFocusRequest` token leak in system audio service. |
| [PR-6](PR-6_Medium_ANR_SCO_mic_failure.md) | **Medium** (5.9) | `MicRecorder.kt` | Main Thread blocking via `CountDownLatch.await()` causing 2s UI freeze & guaranteed SCO mic failure. |
| [PR-7](PR-7_LowMedium_BT_proxy_race_condition.md) | **Low-Medium** (4.3) | `BluetoothHelper.kt` | Premature `closeProfileProxy()` in `finally` block before asynchronous IPC teardown completes. |
| [PR-8](PR-8_Low_connection_mode_override.md) | **Low** (3.3) | `Settings.kt` / `OnboardingActivity.kt` | Migration silently overrides explicit single-mode (USB-only or Wi-Fi-only) preferences to USB+Wi-Fi. |
