/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import org.mozilla.fenix.BuildConfig

/** Build-time configuration of the Vidframe single-site client. */
object VidframeConfig {
    /** Fixed start page. Every launcher start and every attempt to show the browser home screen lands here. */
    const val START_URL = "https://m.youtube.com/"
    const val SCOPE = "https://m.youtube.com/"
    const val APP_TITLE = "Vidframe"

    const val UBO_ID = "uBlock0@raymondhill.net"
    const val UBO_ASSET = "extensions/ublock_origin.xpi"

    /** Upper bound for holding the first page load until uBlock Origin is installed. */
    const val UBO_WAIT_MS = 20_000L

    const val UI_EXTENSION_ID = "ui@vidframe"
    const val UI_EXTENSION_URL = "resource://android/assets/extensions/vidframe-ui/"

    /** Ordered list of DoH endpoints. The first healthy one wins. */
    val DOH_ENDPOINTS =
        listOf(
            "https://dns.adguard-dns.com/dns-query",
            "https://unfiltered.adguard-dns.com/dns-query",
            "https://dns.quad9.net/dns-query",
        )

    /** How navigations to hosts outside [NavigationAllowlist] are handled. */
    enum class NavMode {
        /** Off-list navigations are replaced by a block page with a single "back" action. */
        STRICT,

        /** Off-list navigations show a warning page that can hand the URL to another browser. */
        WARN,
    }

    val navMode: NavMode
        get() = if (BuildConfig.VIDFRAME_NAV_MODE == "warn") NavMode.WARN else NavMode.STRICT
}
