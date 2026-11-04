/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import java.util.Locale
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.preferences.Branch
import mozilla.components.concept.engine.preferences.SetBrowserPreference
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.BuildConfig

/**
 * In-app DPI bypass: starts [DpiBypassProxy] and routes Gecko through it. Works without root and without a VPN
 * because only this app's own connections are affected.
 *
 * Mode comes from `-Pvidframe.dpiBypass=auto|on|off` (BuildConfig.VIDFRAME_DPI_BYPASS). `auto` enables it when the
 * mobile network, the SIM or the system region is in [AUTO_COUNTRIES].
 *
 * HTTP/3 is turned off while the bypass is active: QUIC runs over UDP, which a SOCKS proxy does not carry, and the
 * desync only applies to TCP.
 */
object DpiBypass {
    private val logger = Logger("VidframeDpi")

    @VisibleForTesting internal val AUTO_COUNTRIES = setOf("ru")

    @Volatile private var proxy: DpiBypassProxy? = null

    val isRunning: Boolean
        get() = proxy != null

    @VisibleForTesting
    internal fun isEnabled(mode: String, countries: List<String?>): Boolean =
        when (mode) {
            "on" -> true
            "off" -> false
            else -> countries.any { it?.lowercase(Locale.ROOT) in AUTO_COUNTRIES }
        }

    private fun countries(context: Context): List<String?> {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOf(
            runCatching { telephony?.networkCountryIso }.getOrNull(),
            runCatching { telephony?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        )
    }

    @VisibleForTesting
    internal fun geckoPrefs(port: Int): List<SetBrowserPreference<*>> =
        listOf(
            SetBrowserPreference.setStringPref("network.proxy.socks", DpiBypassProxy.LOOPBACK, Branch.DEFAULT),
            SetBrowserPreference.setIntPref("network.proxy.socks_port", port, Branch.DEFAULT),
            SetBrowserPreference.setIntPref("network.proxy.socks_version", SOCKS_VERSION, Branch.DEFAULT),
            // Gecko resolves names itself over DoH and hands the proxy IP addresses; the system resolver, which may
            // return spoofed answers, is never asked for content hosts.
            SetBrowserPreference.setBoolPref("network.proxy.socks5_remote_dns", false, Branch.DEFAULT),
            SetBrowserPreference.setBoolPref("network.proxy.socks_remote_dns", false, Branch.DEFAULT),
            SetBrowserPreference.setBoolPref("network.http.http3.enable", false, Branch.DEFAULT),
            // Manual proxy configuration; set last so the address is complete when it takes effect.
            SetBrowserPreference.setIntPref("network.proxy.type", PROXY_TYPE_MANUAL, Branch.DEFAULT),
        )

    /**
     * Starts the bypass if it is enabled and calls [onReady] once Gecko uses it (or right away when disabled or on
     * failure, so start-up is never blocked).
     */
    @OptIn(ExperimentalAndroidComponentsApi::class)
    fun start(context: Context, engine: Engine, onReady: () -> Unit) {
        if (proxy != null || !isEnabled(BuildConfig.VIDFRAME_DPI_BYPASS, countries(context))) {
            onReady()
            return
        }
        val started =
            runCatching { DpiBypassProxy(log = { message, error -> logger.warn(message, error) }).start() }
                .onFailure { logger.error("Could not start the DPI bypass proxy", it) }
                .getOrNull()
        if (started == null) {
            onReady()
            return
        }
        proxy = started
        engine.setBrowserPrefs(
            prefs = geckoPrefs(started.port),
            onSuccess = {
                logger.info("DPI bypass active on ${DpiBypassProxy.LOOPBACK}:${started.port}")
                onReady()
            },
            onError = {
                logger.error("Could not route Gecko through the DPI bypass", it)
                onReady()
            },
        )
    }

    private const val SOCKS_VERSION = 5
    private const val PROXY_TYPE_MANUAL = 1
}
