/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.vidframe.dpi.DpiBypass

/** Single startup hook for all Vidframe features, called from FenixApplication after add-on support is ready. */
object VidframeIntegration {
    private val logger = Logger("Vidframe")
    private var provisioner: UboProvisioner? = null

    /** Emits true when the first page may load, i.e. uBlock Origin is installed or cannot be installed. */
    val uboReady: StateFlow<Boolean>
        get() = provisioner?.ready ?: MutableStateFlow(true)

    fun start(context: Context, components: Components) {
        // Preconnect only once the DPI bypass (if any) is in place, so early connections take the same route.
        DpiBypass.start(context, components.core.engine) {
            VidframeConfig.PRECONNECT_URLS.forEach { components.core.engine.speculativeConnect(it) }
        }
        VideoCodecTuning.apply(context, components.core.engine)
        DohFailover(context, components.core.engine, components.settings).start()
        provisioner =
            UboProvisioner(context, components.core.store, components.addonManager).also { it.start() }
        components.core.engine.installBuiltInWebExtension(
            VidframeConfig.UI_EXTENSION_ID,
            VidframeConfig.UI_EXTENSION_URL,
            onSuccess = { logger.debug("Installed ${it.id}") },
            onError = { logger.error("Failed to install UI extension", it) },
        )
    }
}
