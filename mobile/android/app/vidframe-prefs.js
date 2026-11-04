/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Vidframe defaults, included from geckoview-prefs.js. This file is the Android
// equivalent of policies.json: Gecko's enterprise policy engine is not built on
// Android, so everything below is a compiled-in default pref. Fenix-level
// settings (DoH UI, telemetry toggles, Pocket) live in Settings.kt instead, and
// DoH values here are overwritten at runtime by DohFailover with the same values.

// DNS over HTTPS: TRR-only (mode 3) from the very first request, before Fenix
// pushes its own runtime settings.
pref("network.trr.mode", 3);
pref("network.trr.uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.default_provider_uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.custom_uri", "https://dns.adguard-dns.com/dns-query");
pref("network.trr.excluded-domains", "");
pref("network.trr.disable-ECS", true);

// These services perform lookups with TRR bypassed and would leak to the
// system resolver; captive portals must be handled in the system browser.
pref("network.captive-portal-service.enabled", false);
pref("network.connectivity-service.enabled", false);

// No speculative work towards hosts the user did not navigate to.
pref("network.dns.disablePrefetch", true);
pref("network.dns.disablePrefetchFromHTTPS", true);
pref("network.prefetch-next", false);

// HTTPS-only everywhere (Fenix setting is defaulted on as well).
pref("dom.security.https_only_mode", true);

// The video service does not need WebRTC; avoids local IP exposure.
pref("media.peerconnection.enabled", false);

// Gecko-side data reporting. Glean upload is controlled by Fenix settings.
pref("datareporting.healthreport.uploadEnabled", false);
pref("datareporting.policy.dataSubmissionEnabled", false);
pref("toolkit.telemetry.enabled", false);

// Keep add-on updates on so uBlock Origin updates from AMO independently of
// app releases.
pref("extensions.update.enabled", true);
pref("extensions.update.autoUpdateDefault", true);
