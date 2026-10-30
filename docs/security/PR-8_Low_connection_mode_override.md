# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.9.x   | :x:                |
| 1.8.x   | :white_check_mark: |
| < 1.8   | :white_check_mark: |

## Reporting a Vulnerability

### CVE-DIAUTO-2026-008 — Forced Overwrite of User Connection Preferences During Settings Migration

**Severity:** Low  
**CVSS v3.1 Base Score:** 3.3 (Low)  
**Components:**
- [`Settings.kt` (lines 634–649)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/utils/Settings.kt#L634-L649)
- [`OnboardingActivity.kt` (line 112)](file:///c:/Users/ioxdu/workspace/DiAuto-MG4/app/src/main/java/com/andrerinas/openheadunit/main/OnboardingActivity.kt#L112)  
**Introduced in:** Commit `ba70b5b` (v1.9.6)  
**Affects:** All users upgrading from prior versions to DiAuto-MG4 ≥ v1.9.6

---

### Description

In commit `ba70b5b`, the migration logic for the `connection-modes-v2` preference was modified to automatically force single-mode selections (USB-only or Wi-Fi-only) to dual-mode (`USB + Wi-Fi`):

```kotlin
val result = when {
    legacy == null -> setOf(ConnectionMode.USB, ConnectionMode.WIFI)
    legacy == setOf(ConnectionMode.WIFI) || legacy == setOf(ConnectionMode.USB) ->
        setOf(ConnectionMode.USB, ConnectionMode.WIFI)
    else -> legacy
}
```

### Impact

If an end-user intentionally configured DiAuto to operate **strictly over USB** (e.g. for privacy, stability, or to avoid wireless interference and hotspot battery drain) or **strictly over Wi-Fi**, this migration:

1. Overwrites the user's explicit preference without consent or notification.
2. Silently enables background TCP servers, Wi-Fi polling, and ArpNudger routines on launch.
3. Violates user intent and security isolation principles for users restricting network surface area.

### Recommended Fix

Preserve explicit user preferences during migration. Only provide the `USB + Wi-Fi` default if no prior preference was set (`legacy == null`).

```kotlin
// AFTER (fixed)
val result = when {
    legacy == null -> setOf(ConnectionMode.USB, ConnectionMode.WIFI) // default for new installs only
    else -> legacy // preserve explicit user configuration
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

- Commit introducing the change: `ba70b5b`
