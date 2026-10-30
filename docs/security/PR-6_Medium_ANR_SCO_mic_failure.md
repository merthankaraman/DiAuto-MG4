# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-006 — ANR Risk and Guaranteed SCO Microphone Activation Failure

**Severity:** Medium  
**CVSS v3.1 Base Score:** 5.9 (Medium)  
**Component:** [`MicRecorder.kt` (lines 248–285)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/decoder/MicRecorder.kt#L248-L285)  
**Introduced in:** Commit `c28d6e8` (v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

The Bluetooth SCO (Synchronous Connection-Oriented) microphone activation in `MicRecorder.kt` contains a threading deadlock pattern that guarantees failure in certain call paths:

#### 1. Main Thread Blocking with Synchronous Latch

```kotlin
ContextCompat.registerReceiver(
    context, scoReceiver,
    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
    ContextCompat.RECEIVER_EXPORTED
)
audioManager.startBluetoothSco()
val scoOk = latch.await(SCO_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && connected.get()
```

The `registerReceiver()` call does **not specify a `Handler`**, so the broadcast is delivered on the **Main Thread (UI Looper)**. If `start()` is invoked from the Main Thread:

1. `latch.await(2000L)` blocks the Main Thread for up to 2 seconds
2. While the Main Thread is blocked, the Looper cannot process incoming broadcasts
3. The `SCO_AUDIO_STATE_CONNECTED` broadcast is queued but never delivered
4. `connected.get()` remains `false`
5. The method always times out after exactly 2 seconds

This creates a **guaranteed failure**: the SCO connection attempt always fails when called from the Main Thread, and the 2-second block risks an Application Not Responding (ANR) dialog.

#### 2. Premature Stop from Sticky Broadcast

The `SCO_AUDIO_STATE_DISCONNECTED` event may be delivered as a sticky broadcast immediately upon registration (reflecting the current state before `startBluetoothSco()` takes effect). If this happens, the receiver's `onReceive()` calls `stop()` before the `AudioRecord` has finished initialization, causing a race condition with `startRecording()`.

### Impact

| Impact Area | Description |
| :--- | :--- |
| **Voice calls** | Bluetooth microphone fails to activate during Android Auto voice calls, leaving the driver without hands-free audio input |
| **Voice assistant** | Google Assistant voice commands fail when routed through SCO |
| **UI freeze** | 2-second Main Thread block may trigger ANR if other UI operations are pending |
| **User confusion** | The failure is silent — no error is shown to the driver; the microphone simply doesn't work |

### Recommended Fix

1. **Specify a `Handler` for broadcast delivery**: Register the receiver with a background `Handler` so the broadcast is delivered regardless of the calling thread.
2. **Move `start()` off the Main Thread**: Ensure the SCO connection is always initiated from a background thread.
3. **Filter initial sticky broadcast**: Check that the SCO state transition is `CONNECTED`, not just any state change, and ignore the initial sticky broadcast.

```kotlin
// BEFORE (deadlock-prone)
ContextCompat.registerReceiver(
    context, scoReceiver,
    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
    ContextCompat.RECEIVER_EXPORTED
)

// AFTER (fixed)
val handlerThread = HandlerThread("ScoReceiverThread").apply { start() }
val handler = Handler(handlerThread.looper)
context.registerReceiver(
    scoReceiver,
    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
    null,
    handler  // ← delivers broadcast on background thread
)
```

### Disclosure Timeline

| Date | Event |
| :--- | :--- |
| 2026-10-15 | Vulnerability identified during code audit |
| 2026-10-15 | Report filed |
| TBD | Maintainer acknowledgment |
| TBD | Patch release |

### References

- Commit introducing the vulnerability: `c28d6e8`
- Android SCO audio documentation: [developer.android.com](https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco())
- Android ANR guidelines: [developer.android.com](https://developer.android.com/topic/performance/vitals/anr)
