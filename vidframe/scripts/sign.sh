#!/usr/bin/env bash
# Aligns and signs an unsigned release APK with apksigner (v2+v3 schemes).
# Keystore: VIDFRAME_KEYSTORE (default vidframe/keystore/release.jks), alias VIDFRAME_KEY_ALIAS (default vidframe).
# Passwords are read from VIDFRAME_KS_PASS / VIDFRAME_KEY_PASS or prompted by apksigner.
set -euo pipefail

IN="${1:?usage: sign.sh <unsigned.apk>}"
TOP="$(cd "$(dirname "$0")/../.." && pwd)"
KEYSTORE="${VIDFRAME_KEYSTORE:-$TOP/vidframe/keystore/release.jks}"
ALIAS="${VIDFRAME_KEY_ALIAS:-vidframe}"

SDK="${ANDROID_HOME:-$HOME/.mozbuild/android-sdk-linux}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n1)"

OUT="${IN%-unsigned.apk}.apk"
ALIGNED="${IN%.apk}-aligned.apk"

"$BT/zipalign" -p -f 4 "$IN" "$ALIGNED"

PASS_ARGS=()
[ -n "${VIDFRAME_KS_PASS:-}" ] && PASS_ARGS+=(--ks-pass env:VIDFRAME_KS_PASS)
[ -n "${VIDFRAME_KEY_PASS:-}" ] && PASS_ARGS+=(--key-pass env:VIDFRAME_KEY_PASS)

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" "${PASS_ARGS[@]}" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED"
"$BT/apksigner" verify --print-certs "$OUT"
echo "$OUT"
