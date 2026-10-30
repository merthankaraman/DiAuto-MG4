#!/usr/bin/env bash
# Install a review or signed release APK without changing global head-unit settings.
set -euo pipefail
CAR="${1:?Usage: install.sh CAR_IP:5555 PATH_TO_APK}"
APK="${2:?Provide an APK path}"
ADB="${ADB:-adb}"
PKG=com.drivehub.diauto.mg4
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
"$ADB" connect "$CAR"
adb_car() { "$ADB" -s "$CAR" "$@"; }
STATE="$(adb_car get-state)"
[[ "$STATE" == "device" ]] || {
    echo "ADB target is not authorized and online (state: $STATE)." >&2
    exit 1
}

if [[ ! -t 0 ]]; then
    echo "Refusing to install non-interactively; confirm the target on a parked vehicle." >&2
    exit 1
fi
printf 'Target: %s\nAPK: %s\n' "$CAR" "$APK"
read -r -p "Type INSTALL CAR to continue on this parked head unit: " answer
[[ "$answer" == "INSTALL CAR" ]] || {
    echo "Installation cancelled."
    exit 0
}

adb_car install -r "$APK"
adb_car shell am force-stop "$PKG"
adb_car shell am start -n "$PKG/com.andrerinas.openheadunit.main.MainActivity"
echo 'Finish setup on the display, then choose Connect phone.'
echo 'No runtime permissions, app-ops, location mode, or device-idle whitelist were changed.'
