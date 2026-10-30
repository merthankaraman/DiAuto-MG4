# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-004 — Port Conflict and Deceptive Bind with `SO_REUSEADDR` in CarNetworkQuirks

**Severity:** Medium-High  
**CVSS v3.1 Base Score:** 6.5 (Medium)  
**Component:** [`CarNetworkQuirks.kt` (lines 32–63, 88–96, 100–109)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/connection/CarNetworkQuirks.kt#L32-L63)  
**Introduced in:** Commits `19caaec` / `42849a3` (PR #2, v1.9.x series)  
**Affects:** All MG4 / SAIC-based vehicles running DiAuto-MG4 ≥ v1.9.0

---

### Description

`CarNetworkQuirks` manages TCP port selection for the Android Auto wireless server. Three design flaws create port conflict and potential interference with other vehicle system services:

#### 1. False-Positive Port Availability Check with `SO_REUSEADDR`

The port availability check uses `reuseAddress = true`:

```kotlin
fun isFree(port: Int): Boolean = runCatching {
    ServerSocket().use { s ->
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(port))
    }
}.isSuccess
```

On Linux/Android, `SO_REUSEADDR` allows a socket to bind to a port even when another socket is already bound to it (with compatible options or on a different interface). This produces a **false positive**: the port appears free when it is actually in use by another service.

#### 2. Candidate List Contains Common System Ports

The preferred port list is:

```kotlin
val PREFERRED = listOf(30518, 30517, 8080, 6010)
```

Ports `8080` and `6010` are commonly used by:
- Internal web proxies and diagnostic interfaces (`8080`)
- X11 forwarding and development tools (`6010`)
- OEM diagnostic services that may run on the head unit

#### 3. Fallback to Known-Occupied Port

If all candidate ports fail the (already flawed) availability check, the code falls back unconditionally:

```kotlin
candidates.firstOrNull { isFree(it) } ?: candidates.first()
```

This selects port `30518` even when the check has determined it is occupied, guaranteeing a bind failure in `AapService.kt`.

#### 4. No Cache Invalidation

The selected port is cached in `cachedPort` and **never recalculated** for the lifetime of the process. If a port conflict develops after initial selection (e.g., a diagnostic service starts later), the cached port remains stale.

### Impact

| Impact Area | Description |
| :--- | :--- |
| **Service interference** | DiAuto may bind to a port already in use by an OEM diagnostic or telematics service, disrupting vehicle diagnostics |
| **Connection failure** | If the port is truly occupied, the TCP server in `AapService` fails to start, preventing Android Auto wireless connectivity |
| **Port hijacking** | A malicious app could pre-bind to a candidate port, preventing DiAuto from operating (denial of service) |
| **Silent degradation** | The false-positive check means the conflict goes undetected until runtime failure |

### Recommended Fix

1. **Remove `SO_REUSEADDR`** from the availability check — a proper availability test should fail if the port is in use.
2. **Use ephemeral ports**: Let the OS assign a free port via `ServerSocket(0)` and communicate the chosen port to the phone.
3. **Remove system-common ports** (`8080`, `6010`) from the candidate list.
4. **Invalidate cache** on bind failure and retry with an alternative port.

```kotlin
// BEFORE (vulnerable)
fun isFree(port: Int): Boolean = runCatching {
    ServerSocket().use { s ->
        s.reuseAddress = true   // ← masks conflicts
        s.bind(InetSocketAddress(port))
    }
}.isSuccess

// AFTER (fixed)
fun isFree(port: Int): Boolean = runCatching {
    ServerSocket().use { s ->
        s.reuseAddress = false  // ← strict check
        s.bind(InetSocketAddress(port))
    }
}.isSuccess
```

### Disclosure Timeline

| Date | Event |
| :--- | :--- |
| 2026-10-15 | Vulnerability identified during code audit |
| 2026-10-15 | Report filed |
| TBD | Maintainer acknowledgment |
| TBD | Patch release |

### References

- PR introducing the vulnerability: PR #2 (commits `19caaec`, `42849a3`)
- Linux `SO_REUSEADDR` semantics: `socket(7)` man page
