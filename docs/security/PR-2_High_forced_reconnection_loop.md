# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-002 — Forced Reconnection Loop Prevents User from Exiting Android Auto (Denial of Exit)

**Severity:** High  
**CVSS v3.1 Base Score:** 7.1 (High)  
**Components:**
- [`AapService.kt` (lines 2226–2260, 3862–3897)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt#L2226)
- [`AutoStartReceiver.kt` (line 73)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/app/AutoStartReceiver.kt#L73)  
**Introduced in:** Commits `7cda6ef` + `c28d6e8` (v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

When the user presses "Exit" to disconnect from Android Auto, the following sequence creates an inescapable reconnection loop:

1. The user initiates exit → DiAuto sets a 15-second cooldown timer (`USER_EXIT_COOLDOWN_MS = 15_000L`) via `userExitCooldownUntil`.
2. Exiting Android Auto tears down the Wi-Fi/video session, causing the smartphone to re-establish standard Bluetooth profiles (HFP/A2DP).
3. The head unit's Bluetooth stack emits `BluetoothDevice.ACTION_ACL_CONNECTED`.
4. `AutoStartReceiver` intercepts this broadcast and launches `AapService` with action `ACTION_BT_AUTO_START`.
5. In `AapService`, the method `scheduleNativeAaPoke(mac)` is called.
6. Inside `scheduleNativeAaPoke()`, a coroutine calls `awaitUserExitCooldown()`, which **blocks until the 15-second cooldown expires** — and then immediately invokes `nativeAaHandshakeManager.manualPoke(mac)`, re-establishing the Android Auto session.

### Root Cause

The cooldown mechanism was designed as a debounce to prevent immediate reconnection. However, `awaitUserExitCooldown()` does not cancel the reconnection — it merely **delays** it. The function name is misleading: it waits *for* the cooldown to expire, then proceeds with the poke.

```
User Exits → 15s cooldown starts → BT ACL reconnects → AutoStartReceiver fires
→ scheduleNativeAaPoke() → awaits cooldown expiry → manualPoke() → AA session reopens
→ User Exits again → loop repeats
```

### Impact on Vehicle Operation

| Impact Area | Description |
| :--- | :--- |
| **User autonomy** | The driver cannot permanently exit Android Auto; the session is forcefully re-established every ~15 seconds |
| **Battery drain** | Continuous Wi-Fi/video session teardown and reconnection cycles drain the phone battery |
| **Driver distraction** | Unexpected screen takeover while driving poses a safety risk |
| **Phone usability** | The phone is locked into Android Auto projection mode and cannot be used normally |

### Recommended Fix

1. **Respect the cooldown as a cancellation**: If `awaitUserExitCooldown()` detects an active cooldown, it should **abort** the reconnection entirely rather than waiting for it to expire.
2. **Add an explicit `userExitedFlag`**: Set a persistent boolean that prevents auto-reconnection until the user manually re-initiates the connection.
3. **Filter ACL events during cooldown**: `AutoStartReceiver` should check for active cooldown state before launching the service.

```kotlin
// BEFORE (vulnerable)
private suspend fun awaitUserExitCooldown() {
    val remaining = userExitCooldownUntil - SystemClock.elapsedRealtime()
    if (remaining > 0) delay(remaining)
    // proceeds to reconnect after delay
}

// AFTER (fixed)
private suspend fun awaitUserExitCooldown(): Boolean {
    val remaining = userExitCooldownUntil - SystemClock.elapsedRealtime()
    if (remaining > 0) {
        Log.i(TAG, "User recently exited, cancelling auto-reconnect")
        return false  // abort reconnection
    }
    return true
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

- Commits introducing the vulnerability: `7cda6ef`, `c28d6e8`
- Android `BluetoothDevice.ACTION_ACL_CONNECTED` documentation: [developer.android.com](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#ACTION_ACL_CONNECTED)
