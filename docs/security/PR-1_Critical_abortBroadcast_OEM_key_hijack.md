# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-001 — Unconditional `abortBroadcast()` Hijacks All OEM Steering Wheel Hard Keys

**Severity:** Critical  
**CVSS v3.1 Base Score:** 8.6 (High)  
**Component:** [`CarKeyBroadcastReceiver.kt` (lines 95–97, 169–207)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/connection/carkey/CarKeyBroadcastReceiver.kt#L95-L97)  
**Introduced in:** Commit `d19de3c` (v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

In `CarKeyBroadcastReceiver.onReceive()`, the method calls `abortBroadcast()` **unconditionally** at the very top of the handler, before performing any validation of the action type or key code:

```kotlin
if (isOrderedBroadcast) {
    abortBroadcast()
}
```

This executes before:
- Checking whether the action matches `ACTION_SAIC_HARDKEY`
- Validating the key code against the recognized key map
- Determining whether DiAuto should handle or ignore the key event

As a result, **all ordered broadcasts matching the intent filter are consumed and suppressed**, regardless of whether DiAuto recognizes or handles the key. Downstream broadcast receivers (including the OEM radio app, phone call handler, and instrument cluster UI) never receive the event.

### Attack Vector — Intent Injection

The receiver is registered with `ContextCompat.RECEIVER_EXPORTED` and **no sender signature verification**. Any third-party application on the head unit can forge `com.saic.keyevent.hardkey.report` intents, injecting arbitrary key press events into DiAuto's handler. Combined with the unconditional `abortBroadcast()`, a malicious app could both inject phantom key events and prevent the legitimate OEM stack from receiving real ones.

### Impact on Vehicle Safety

| Impact Area | Description |
| :--- | :--- |
| **Volume control** | Physical volume buttons on the steering wheel become unresponsive to the OEM audio stack |
| **Phone call handling** | The handset/hangup button (`cornetta`) is intercepted and silenced, preventing the driver from answering or ending calls via steering wheel controls |
| **Instrument cluster navigation** | Menu/navigation keys for the dashboard display are blocked |
| **Driver distraction** | Non-functional steering wheel buttons force the driver to use touchscreen controls, increasing distraction |

### Recommended Fix

1. **Move `abortBroadcast()` after validation**: Only call `abortBroadcast()` when DiAuto positively identifies and handles the key event.
2. **Add sender verification**: Check the broadcast sender's package signature against the known SAIC system signature before processing.
3. **Whitelist handled keys**: Maintain an explicit set of handled key codes and only abort the broadcast for those specific keys.

```kotlin
// BEFORE (vulnerable)
override fun onReceive(context: Context, intent: Intent) {
    if (isOrderedBroadcast) {
        abortBroadcast()  // ← blocks ALL keys, even unrecognized ones
    }
    // ... key validation happens later
}

// AFTER (fixed)
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_SAIC_HARDKEY) return
    val keyCode = decodeKeyCode(intent)
    if (keyCode !in HANDLED_KEYS) return  // let OEM handle it
    if (isOrderedBroadcast) {
        abortBroadcast()  // ← only blocks keys DiAuto actually handles
    }
    // ... process the recognized key
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

- Commit introducing the vulnerability: `d19de3c`
- Android `BroadcastReceiver.abortBroadcast()` documentation: [developer.android.com](https://developer.android.com/reference/android/content/BroadcastReceiver#abortBroadcast())
