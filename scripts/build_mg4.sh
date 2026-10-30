#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    SDK="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then
    SDK="$HOME/Android/Sdk"
  fi
fi

if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
  BT="$ANDROID_BUILD_TOOLS"
elif [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | tail -n1)
else
  echo "Error: Android SDK or build-tools not found. Set ANDROID_HOME or ANDROID_BUILD_TOOLS." >&2
  exit 1
fi

KEYS="${MG4_PLATFORM_KEYS_DIR:-$ROOT/tools}"
if [ ! -f "$KEYS/platform.pk8" ]; then
  echo "Error: platform.pk8 not found in $KEYS. Set MG4_PLATFORM_KEYS_DIR to folder containing platform.pk8 & platform.x509.pem" >&2
  exit 1
fi
VERSION="1.9.8"
APK_DIR="$ROOT/app/build/outputs/apk/car/debug"
ALIGNED="$APK_DIR/DiAuto-MG4-v$VERSION-aligned.apk"
SIGNED="$APK_DIR/DiAuto-MG4-v$VERSION.apk"
DELIVERY_DIR=$(CDPATH= cd -- "$ROOT/.." && pwd)
DELIVERY_APK="$DELIVERY_DIR/DiAuto-MG4-v$VERSION.apk"

is_gradle_apk_candidate() {
  case "${1##*/}" in
    DiAuto-MG4-v*.apk|*aligned*|*unsigned*) return 1 ;;
    *.apk) return 0 ;;
    *) return 1 ;;
  esac
}

if [ -d "$APK_DIR" ]; then
  for existing in "$APK_DIR"/*.apk; do
    [ -f "$existing" ] || continue
    if is_gradle_apk_candidate "$existing"; then
      rm -f "$existing"
    fi
  done
fi

cd "$ROOT"
./gradlew :app:assembleCarDebug

APK=
for candidate in "$APK_DIR"/*.apk; do
  [ -f "$candidate" ] || continue
  if is_gradle_apk_candidate "$candidate"; then
    if [ -n "$APK" ]; then
      echo "Ambiguous car debug APK outputs in $APK_DIR" >&2
      exit 1
    fi
    APK=$candidate
  fi
done
[ -n "$APK" ] || { echo "No fresh car debug APK found in $APK_DIR" >&2; exit 1; }

"$BT/zipalign" -f 4 "$APK" "$ALIGNED"
"$BT/apksigner" sign \
  --key "$KEYS/platform.pk8" \
  --cert "$KEYS/platform.x509.pem" \
  --out "$SIGNED" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$SIGNED"
cp "$SIGNED" "$DELIVERY_APK"
echo "$DELIVERY_APK"
