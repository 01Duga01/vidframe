#!/usr/bin/env bash
# One-shot: bootstrap (artifact mode), build, run Fenix unit tests, then build APKs if the tests pass.
# Logs go to vidframe/out/logs/. Usage: vidframe/scripts/test-and-build.sh
set -euo pipefail

TOP="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$TOP"

export MOZCONFIG="$TOP/vidframe/mozconfigs/arm64-artifact"
LOGS="$TOP/vidframe/out/logs"
mkdir -p "$LOGS"

step() { echo; echo "=== $1 ==="; }

step "1/5 bootstrap (Android SDK/NDK/JDK, artifact mode)"
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android Artifact Mode" \
  > "$LOGS/bootstrap.log" 2>&1 || { tail -40 "$LOGS/bootstrap.log"; echo "bootstrap failed: $LOGS/bootstrap.log"; exit 1; }

step "2/5 build (prebuilt Gecko + local Java/Kotlin/JS/prefs)"
./mach build > "$LOGS/build.log" 2>&1 || { tail -60 "$LOGS/build.log"; echo "build failed: $LOGS/build.log"; exit 1; }

step "3/5 Fenix unit tests"
if ! ./mach test fenix > "$LOGS/fenix-tests.log" 2>&1; then
  grep -E "FAILED|tests completed|Execution failed|error:" "$LOGS/fenix-tests.log" | tail -80 || true
  echo "tests failed: $LOGS/fenix-tests.log"
  exit 1
fi
grep -E "tests completed|BUILD SUCCESSFUL" "$LOGS/fenix-tests.log" | tail -5 || true

step "4/5 debug APK"
./mach gradle fenix:assembleDebug > "$LOGS/assemble-debug.log" 2>&1 || {
  tail -60 "$LOGS/assemble-debug.log"; echo "assembleDebug failed: $LOGS/assemble-debug.log"; exit 1; }
find "$TOP/obj-vidframe-arm64-artifact" -path "*outputs/apk/debug/*arm64-v8a*.apk" -exec cp {} "$TOP/vidframe/out/" \;

step "5/5 release APK (needs uBlock Origin from addons.mozilla.org)"
if vidframe/scripts/fetch-ubo.sh --update > "$LOGS/fetch-ubo.log" 2>&1 \
  && ./mach gradle fenix:assembleRelease > "$LOGS/assemble-release.log" 2>&1; then
  find "$TOP/obj-vidframe-arm64-artifact" -path "*outputs/apk/release/*arm64-v8a*.apk" -exec cp {} "$TOP/vidframe/out/" \;
else
  echo "release APK skipped or failed, see $LOGS/fetch-ubo.log and $LOGS/assemble-release.log"
fi

echo
echo "APKs in vidframe/out/:"
ls -lh "$TOP"/vidframe/out/*.apk
