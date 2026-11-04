#!/usr/bin/env bash
# Builds GeckoView + Fenix (Vidframe) for the ABI selected by $MOZCONFIG and copies the APK to vidframe/out/.
set -euo pipefail

TOP="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$TOP"

: "${MOZCONFIG:?set MOZCONFIG=vidframe/mozconfigs/arm64 (or armv7)}"
export MOZCONFIG
NAV_MODE="${VIDFRAME_NAV_MODE:-strict}"

case "$MOZCONFIG" in
  *armv7*) ABI=armeabi-v7a ;;
  *) ABI=arm64-v8a ;;
esac

if [ ! -s mobile/android/fenix/app/src/main/assets/extensions/ublock_origin.xpi ]; then
  echo "uBlock Origin XPI missing; run vidframe/scripts/fetch-ubo.sh first" >&2
  exit 1
fi

STAMP="$(mktemp)"
trap 'rm -f "$STAMP"' EXIT

./mach build
./mach gradle -Pvidframe.navMode="$NAV_MODE" fenix:assembleRelease

mkdir -p vidframe/out
APK="$(find "$TOP" -path "*fenix*" -path "*outputs/apk/release/*" -name "*${ABI}*release-unsigned.apk" -newer "$STAMP" | head -n1)"
if [ -z "$APK" ]; then
  echo "Could not find the ${ABI} release APK" >&2
  exit 1
fi
cp "$APK" "vidframe/out/vidframe-${ABI}-release-unsigned.apk"
echo "vidframe/out/vidframe-${ABI}-release-unsigned.apk"
