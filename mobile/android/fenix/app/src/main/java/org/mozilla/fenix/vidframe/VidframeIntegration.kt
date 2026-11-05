/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.vidframe.dpi.DpiBypass

/** Single startup hook for all Vidframe features, called from FenixApplication after add-on support is ready. */
object VidframeIntegration {
    private val logger = Logger("Vidframe")
    private var provisioner: UboProvisioner? = null

    /** Set by the launcher when it opened the site before uBlock Origin finished installing. */
    @Volatile var openedBeforeUbo = false

    /** Emits true when the first page may load, i.e. uBlock Origin is installed or cannot be installed. */
    val uboReady: StateFlow<Boolean>
        get() = provisioner?.ready ?: MutableStateFlow(true)

    /** Detailed first-run progress for the setup screen. */
    val uboState: StateFlow<UboSetupState>
        get() = provisioner?.state ?: MutableStateFlow(UboSetupState(UboSetupState.Phase.READY))

    fun start(context: Context, components: Components) {
        val engine = components.core.engine

        // First: uBlock Origin. On the very first run its install is the longest step before the first page.
        provisioner =
            UboProvisioner(
                    context = context,
                    store = components.core.store,
                    addonManager = components.addonManager,
                    onInstalled = { reloadIfOpenedEarly(components) },
                )
                .also { it.start() }

        // Preconnect only once the DPI bypass (if any) is in place, so early connections take the same route.
        DpiBypass.start(context, engine) {
            VidframeConfig.PRECONNECT_URLS.forEach { engine.speculativeConnect(it) }
        }
        DohFailover(context, engine, components.settings).start()
        engine.installBuiltInWebExtension(
            VidframeConfig.UI_EXTENSION_ID,
            VidframeConfig.UI_EXTENSION_URL,
            onSuccess = { logger.debug("Installed ${it.id}") },
            onError = { logger.error("Failed to install UI extension", it) },
        )

        // The codec scan can take tens of milliseconds on the first run; keep it off the main thread. The site picks
        // its codec only once its player has loaded, well after this finishes.
        val main = Handler(Looper.getMainLooper())
        thread(name = "vidframe-codecs", isDaemon = true) {
            val hardwareAv1 = VideoCodecTuning.hasHardwareAv1Decoder(context)
            main.post { VideoCodecTuning.apply(engine, hardwareAv1) }
        }
    }

    /** The first page was loaded unfiltered because uBlock Origin was still installing: reload it once. */
    private fun reloadIfOpenedEarly(components: Components) {
        if (!openedBeforeUbo) {
            return
        }
        openedBeforeUbo = false
        val tabId = findWebAppTabId(components.core.store.state) ?: return
        logger.info("uBlock Origin is ready; reloading the first page")
        components.useCases.sessionUseCases.reload(tabId)
    }

    @VisibleForTesting
    internal fun findWebAppTabId(state: BrowserState): String? =
        state.customTabs.firstOrNull { it.content.webAppManifest?.startUrl == VidframeConfig.START_URL }?.id
}
