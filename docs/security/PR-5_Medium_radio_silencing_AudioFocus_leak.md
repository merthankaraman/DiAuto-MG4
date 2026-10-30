# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-005 — Coercive OEM Radio Silencing and AudioFocus Token Leak

**Severity:** Medium  
**CVSS v3.1 Base Score:** 5.3 (Medium)  
**Components:**
- [`AapService.kt` (lines 1192–1212, 1218–1239)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt#L1192-L1212)
- [`SaicCarAudioSource.kt` (lines 101–130)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/evhardware/src/main/java/com/evsuite/hardware/saic/SaicCarAudioSource.kt#L101-L130)  
**Introduced in:** Commits `9d17bd4`, `d19de3c` (v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

#### 1. Forced Radio Muting Every 8 Seconds

In `startSaicAndroidAutoMediaPublication()`, a coroutine loop runs every 8 seconds:

```kotlin
while (isActive) {
    delay(8_000L)
    if (SaicMediaPlayer.isAndroidAutoSource()) continue
    silenceSaicRadioForWifiAudio()
    claimSaicAndroidAutoMediaSource()
}
```

If the driver deliberately switches to FM/DAB radio while Android Auto is active on the screen (e.g., using AA only for navigation while listening to broadcast radio), DiAuto will:
1. Invoke `SaicRadio.pause()` to silence the radio
2. Reassign the audio amplifier output to the Android Auto source
3. This happens **every 8 seconds**, making it impossible for the driver to listen to OEM radio while Android Auto is displayed

#### 2. AudioFocus Token Accumulation (Memory Leak)

Inside `claimAndroidAuto()`, each invocation of the 8-second loop creates a **new** `AudioFocusRequest.Builder` and calls `audioManager.requestAudioFocus(request)` without releasing the previous `AudioFocusRequest`:

```kotlin
// Each loop iteration creates a new request
val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(audioAttributes)
    .build()
audioManager.requestAudioFocus(request)
// ← previous request is never abandoned
```

Over the course of a long drive, this accumulates unreleased AudioFocus tokens in Android's `AudioService`, eventually exhausting the system's focus stack.

### Impact

| Impact Area | Description |
| :--- | :--- |
| **Driver experience** | FM/DAB radio is forcefully muted every 8 seconds; the driver cannot listen to broadcast radio while Android Auto navigation is displayed |
| **Audio system stability** | Accumulated `AudioFocusRequest` tokens leak memory in the Android `AudioService` system process |
| **OEM app interference** | Continuous audio focus claims may prevent other OEM audio apps (phone call handler, parking sensors) from acquiring focus |
| **Long-drive degradation** | On drives exceeding several hours, the token accumulation can cause the audio subsystem to become unresponsive |

### Recommended Fix

1. **Store and reuse a single `AudioFocusRequest`**: Create the request once and reuse it across loop iterations.
2. **Call `abandonAudioFocusRequest()`** before requesting a new one, or when Android Auto exits.
3. **Respect user audio source choice**: If the driver manually switched to FM/DAB, do not override the selection. Only claim audio focus when Android Auto media playback is active.

```kotlin
// BEFORE (leaks tokens)
while (isActive) {
    delay(8_000L)
    val request = AudioFocusRequest.Builder(AUDIOFOCUS_GAIN).build()
    audioManager.requestAudioFocus(request)
}

// AFTER (fixed)
private val audioFocusRequest = AudioFocusRequest.Builder(AUDIOFOCUS_GAIN)
    .setAudioAttributes(audioAttributes)
    .build()

while (isActive) {
    delay(8_000L)
    if (userManuallySelectedRadio) continue  // respect user choice
    audioManager.requestAudioFocus(audioFocusRequest)  // reuse same request
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

- Commits introducing the vulnerability: `9d17bd4`, `d19de3c`
- Android AudioFocus documentation: [developer.android.com](https://developer.android.com/reference/android/media/AudioFocusRequest)
