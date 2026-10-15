# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-007 — Race Condition and Premature Proxy Closure in Bluetooth Profile Disconnect

**Severity:** Low-Medium  
**CVSS v3.1 Base Score:** 4.3 (Medium)  
**Component:** [`BluetoothHelper.kt` (lines 349–399)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/utils/BluetoothHelper.kt#L349-L399)  
**Introduced in:** Commit `7cda6ef` (v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

To disconnect A2DP and AVRCP Bluetooth profiles before initiating Android Auto, `releaseCarBluetoothMediaLink()` uses `adapter.getProfileProxy()` with an anonymous `ServiceListener`. Inside `onServiceConnected()`:

```kotlin
try {
    disconnect?.invoke(proxy, device)
} finally {
    adapter.closeProfileProxy(profileId, proxy)
}
```

#### The Race Condition

The `disconnect` method is a reflected call to `BluetoothProfile.disconnect()`, which is an **asynchronous IPC transaction** to the Bluetooth daemon (`com.android.bluetooth`). The `closeProfileProxy()` call in the `finally` block executes **immediately and synchronously** after `disconnect` returns.

Since `disconnect` only queues the IPC message, `closeProfileProxy()` invalidates the Binder proxy **before the Bluetooth daemon has completed the profile teardown**. This can result in:

1. The disconnect operation being silently dropped by the daemon
2. The profile remaining connected despite DiAuto believing it was disconnected
3. Inconsistent state between DiAuto's connection tracking and the actual Bluetooth stack

#### AVRCP Controller Disruption

The code also disconnects `PROFILE_AVRCP_CONTROLLER` (profile ID 12). Closing this profile proxy disables media control commands (play, pause, skip) from the head unit to the phone over Bluetooth. If the disconnect fails due to the race condition, the AVRCP state becomes indeterminate.

### Impact

| Impact Area | Description |
| :--- | :--- |
| **Incomplete profile teardown** | A2DP audio streaming may persist after DiAuto believes it has been disconnected |
| **Audio routing conflicts** | Both A2DP and Android Auto Wi-Fi audio may be active simultaneously, causing audio glitches or echo |
| **Media control loss** | AVRCP disconnect may leave steering wheel media controls in a broken state |
| **Bluetooth stack instability** | Repeated premature proxy closures can accumulate stale state in the Bluetooth service |

### Recommended Fix

1. **Delay `closeProfileProxy()`**: Wait for the profile state change callback before closing the proxy.
2. **Use `BluetoothProfile.ServiceListener.onServiceDisconnected()`** to detect when the profile is truly torn down.
3. **Add a timeout-based fallback**: If the state change callback doesn't arrive within a reasonable time (e.g., 3 seconds), then close the proxy.

```kotlin
// BEFORE (race condition)
try {
    disconnect?.invoke(proxy, device)
} finally {
    adapter.closeProfileProxy(profileId, proxy)  // ← too early
}

// AFTER (fixed)
try {
    disconnect?.invoke(proxy, device)
    // Wait for the profile state change or a timeout
    withTimeoutOrNull(3000L) {
        awaitProfileDisconnected(profileId, device)
    }
} finally {
    adapter.closeProfileProxy(profileId, proxy)
}
```

### Disclosure Timeline

| Date | Event |
| :--- | :--- |
| 2026-10-15 | Vulnerability identified during code audit |
| 2026-10-15 | Report filed |
| TBD | Maintainer acknowledgment |
| TBD | Patch release |

### References

- Commit introducing the vulnerability: `7cda6ef`
- Android `BluetoothAdapter.closeProfileProxy()` documentation: [developer.android.com](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#closeProfileProxy(int,%20android.bluetooth.BluetoothProfile))
