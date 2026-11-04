/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.utils.Settings

/**
 * Keeps Gecko in TRR-only mode and switches the resolver to the next healthy endpoint of
 * [VidframeConfig.DOH_ENDPOINTS] when the current one stops answering.
 *
 * Probes use the platform HTTP stack on purpose: Gecko's own fetch would resolve the backup host through the (dead)
 * active resolver while in TRR-only mode. Only resolver hostnames are ever looked up via system DNS.
 */
class DohFailover(
    private val context: Context,
    private val engine: Engine,
    private val settings: Settings,
) {
    private val logger = Logger("VidframeDoH")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        applyEndpoint(settings.dohProviderUrl.ifEmpty { VidframeConfig.DOH_ENDPOINTS.first() })
        scope.launch { selectHealthyEndpoint() }
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { selectHealthyEndpoint() }
                }
            },
        )
    }

    private suspend fun selectHealthyEndpoint() {
        val healthy = VidframeConfig.DOH_ENDPOINTS.firstOrNull { probe(it) } ?: return
        withContext(Dispatchers.Main) {
            if (healthy != engine.settings.dohProviderUrl) {
                logger.info("Switching DoH resolver to $healthy")
                applyEndpoint(healthy)
            }
        }
    }

    private fun applyEndpoint(url: String) {
        settings.setDohSettingsMode(Engine.DohSettingsMode.MAX)
        settings.dohProviderUrl = url
        engine.settings.dohSettingsMode = Engine.DohSettingsMode.MAX
        engine.settings.dohProviderUrl = url
    }

    private fun probe(endpoint: String): Boolean =
        runCatching {
                val connection = URL("$endpoint?dns=$PROBE_QUERY").openConnection() as HttpURLConnection
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/dns-message")
                try {
                    connection.responseCode == HttpURLConnection.HTTP_OK &&
                        connection.contentType?.startsWith("application/dns-message") == true
                } finally {
                    connection.disconnect()
                }
            }
            .getOrDefault(false)

    private companion object {
        /** RFC 8484 GET body: A query for youtube.com, id 0, RD set. */
        const val PROBE_QUERY = "AAABAAABAAAAAAAAB3lvdXR1YmUDY29tAAABAAE"
        const val PROBE_TIMEOUT_MS = 4000
    }
}
