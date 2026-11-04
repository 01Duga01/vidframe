#!/usr/bin/env bash
# Downloads the AMO-signed uBlock Origin XPI pinned in vidframe/config/ubo.lock into the Fenix assets.
#   fetch-ubo.sh           download the pinned version and verify its sha256
#   fetch-ubo.sh --update  resolve the current AMO version, rewrite the lock file, then download
set -euo pipefail

TOP="$(cd "$(dirname "$0")/../.." && pwd)"
LOCK="$TOP/vidframe/config/ubo.lock"
DEST="$TOP/mobile/android/fenix/app/src/main/assets/extensions/ublock_origin.xpi"
API="https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/"

if [ "${1:-}" = "--update" ]; then
  META="$(curl -fsSL "$API")"
  VERSION="$(printf '%s' "$META" | python3 -c 'import json,sys; print(json.load(sys.stdin)["current_version"]["version"])')"
  URL="$(printf '%s' "$META" | python3 -c 'import json,sys; print(json.load(sys.stdin)["current_version"]["file"]["url"])')"
  HASH="$(printf '%s' "$META" | python3 -c 'import json,sys; print(json.load(sys.stdin)["current_version"]["file"]["hash"].split(":",1)[1])')"
  cat > "$LOCK" <<LOCKEOF
# Pinned uBlock Origin release from addons.mozilla.org (AMO-signed XPI).
# Regenerate with: vidframe/scripts/fetch-ubo.sh --update
UBO_VERSION=$VERSION
UBO_URL=$URL
UBO_SHA256=$HASH
LOCKEOF
  echo "Pinned uBlock Origin $VERSION"
fi

# shellcheck disable=SC1090
. "$LOCK"
if [ -z "${UBO_URL:-}" ] || [ -z "${UBO_SHA256:-}" ]; then
  echo "ubo.lock is empty; run: vidframe/scripts/fetch-ubo.sh --update" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl -fsSL -o "$TMP" "$UBO_URL"
echo "$UBO_SHA256  $TMP" | sha256sum -c - >/dev/null
unzip -p "$TMP" manifest.json | python3 -c '
import json, sys
m = json.load(sys.stdin)
gecko = m.get("browser_specific_settings", m.get("applications", {})).get("gecko", {})
assert gecko.get("id") == "uBlock0@raymondhill.net", gecko
assert m["manifest_version"] == 2, "expected the MV2 build of uBlock Origin"
'
unzip -l "$TMP" | grep -Eq "META-INF/(mozilla\.rsa|cose\.sig)" || { echo "XPI is not AMO-signed" >&2; exit 1; }
mv "$TMP" "$DEST"
trap - EXIT
echo "uBlock Origin $UBO_VERSION -> $DEST"
