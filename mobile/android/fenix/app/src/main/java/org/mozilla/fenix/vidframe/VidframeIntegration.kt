/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.components.Components

/** Single startup hook for all Vidframe features, called from FenixApplication after add-on support is ready. */
object VidframeIntegration {
    private val logger = Logger("Vidframe")

    fun start(context: Context, components: Components) {
        DohFailover(context, components.core.engine, components.settings).start()
        UboProvisioner(context, components.addonManager).start()
        components.core.engine.installBuiltInWebExtension(
            VidframeConfig.UI_EXTENSION_ID,
            VidframeConfig.UI_EXTENSION_URL,
            onSuccess = { logger.debug("Installed ${it.id}") },
            onError = { logger.error("Failed to install UI extension", it) },
        )
    }
}
