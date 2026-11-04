#!/usr/bin/env bash
# Watches plain DNS (UDP/TCP 53) and DoT (853) traffic of a test device while you use Vidframe.
# Run on a Linux host that routes the device's traffic (hotspot, bridge or router with tcpdump).
#   sudo verify-doh.sh <interface> <device-ip> [seconds]
# Expected with network.trr.mode=3: no queries for youtube/googlevideo/ytimg hosts. Lookups of the resolver
# hostnames themselves (dns.adguard-dns.com, ...) are the Gecko bootstrap and the DohFailover probe and are expected,
# unless network.trr.bootstrapAddr is set. Turn Android "Private DNS" off for this test, otherwise the OS hides
# everything behind DoT and the check proves nothing about Gecko.
set -euo pipefail

IFACE="${1:?interface}"
DEVICE="${2:?device ip}"
SECS="${3:-120}"
LOG="$(mktemp)"

echo "Capturing for ${SECS}s on $IFACE from $DEVICE. Play videos, search, sign in now."
timeout "$SECS" tcpdump -l -n -i "$IFACE" "host $DEVICE and (port 53 or port 853)" 2>/dev/null | tee "$LOG" || true

leaks="$(grep -Eio '(youtube|youtu\.be|googlevideo|ytimg|ggpht|gstatic|googleapis|google)\.[a-z.]*' "$LOG" | sort -u || true)"
if [ -n "$leaks" ]; then
  echo "LEAK: plain DNS queries for content hosts:"
  echo "$leaks"
  echo "(Make sure no other app on the device produced them: use a clean test profile / work profile.)"
  exit 1
fi
echo "No content-host lookups over plain DNS. Resolver bootstrap lookups seen:"
grep -Eio '(adguard-dns\.com|quad9\.net)' "$LOG" | sort | uniq -c || echo "  none"
