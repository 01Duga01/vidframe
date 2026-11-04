/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.net.Uri

/**
 * Allowlist for top-level and subframe navigations.
 *
 * Only documents are checked here: GeckoView does not route subresource loads (video segments, images, XHR) through
 * the request interceptor, so CDN hosts such as googlevideo.com never reach this class. They are still listed so the
 * allowlist stays the single source of truth for documentation and for the optional subresource guard.
 */
object NavigationAllowlist {
    /**
     * @property host registrable domain or exact host; subdomains match unless [exact] is set.
     * @property pathPrefixes when non-empty, only these path prefixes are allowed on [host].
     */
    data class Rule(val host: String, val exact: Boolean = false, val pathPrefixes: List<String> = emptyList())

    val rules =
        listOf(
            Rule("youtube.com"),
            Rule("youtu.be"),
            Rule("youtube-nocookie.com"),
            Rule("ytimg.com"),
            Rule("ggpht.com"),
            Rule("googlevideo.com"),
            Rule("gstatic.com"),
            Rule("googleapis.com"),
            Rule("googleusercontent.com"),
            Rule("accounts.google.com", exact = true),
            Rule("consent.google.com", exact = true),
            Rule("myaccount.google.com", exact = true),
            Rule("accounts.youtube.com", exact = true),
            Rule("consent.youtube.com", exact = true),
            Rule(
                "www.google.com",
                exact = true,
                pathPrefixes = listOf("/recaptcha/", "/sorry/", "/accounts/", "/signin", "/intl/", "/policies/"),
            ),
            Rule("policies.google.com", exact = true),
            Rule("support.google.com", exact = true, pathPrefixes = listOf("/youtube", "/accounts")),
            Rule("dns.adguard-dns.com", exact = true),
            Rule("unfiltered.adguard-dns.com", exact = true),
            Rule("dns.quad9.net", exact = true),
        )

    private val passthroughSchemes = setOf("about", "data", "blob", "moz-extension", "resource", "javascript")

    fun isAllowed(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme in passthroughSchemes) {
            return true
        }
        if (scheme != "https" && scheme != "http") {
            return false
        }
        val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
        val path = parsed.path.orEmpty()
        return rules.any { it.matches(host, path) }
    }

    private fun Rule.matches(candidate: String, path: String): Boolean {
        val hostMatches = candidate == host || (!exact && candidate.endsWith(".$host"))
        return hostMatches && (pathPrefixes.isEmpty() || pathPrefixes.any { path.startsWith(it) })
    }
}
