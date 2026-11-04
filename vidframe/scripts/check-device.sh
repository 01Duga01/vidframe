#!/usr/bin/env bash
# Checks that the connected device (adb) meets Vidframe's minimum requirements.
set -euo pipefail

MIN_SDK=26
MIN_RAM_MB=2048

sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
release="$(adb shell getprop ro.build.version.release | tr -d '\r')"
abis="$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
ram_kb="$(adb shell cat /proc/meminfo | awk '/MemTotal/ {print $2}')"
ram_mb=$((ram_kb / 1024))
webview="$(adb shell dumpsys webviewupdate 2>/dev/null | awk -F': ' '/Current WebView package/ {print $2}' | tr -d '\r')"

echo "Android $release (API $sdk), ABIs: $abis, RAM: ${ram_mb} MB"
echo "System WebView (not used by Vidframe): ${webview:-unknown}"

ok=1
[ "$sdk" -ge "$MIN_SDK" ] || { echo "FAIL: API $sdk < $MIN_SDK (Android 8.0)"; ok=0; }
case ",$abis," in
  *,arm64-v8a,*) echo "Install: vidframe-arm64-v8a-*.apk" ;;
  *,armeabi-v7a,*) echo "Install: vidframe-armeabi-v7a-*.apk" ;;
  *) echo "FAIL: no supported ABI (arm64-v8a / armeabi-v7a)"; ok=0 ;;
esac
[ "$ram_mb" -ge "$MIN_RAM_MB" ] || echo "WARN: ${ram_mb} MB RAM; 1080p playback and uBO lists may cause tab kills"
[ "$ok" = 1 ] && echo "OK"
