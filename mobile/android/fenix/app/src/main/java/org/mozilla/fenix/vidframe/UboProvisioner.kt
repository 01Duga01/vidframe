/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.feature.addons.AddonManager
import mozilla.components.support.base.log.logger.Logger
import java.io.File

/**
 * Installs the AMO-signed uBlock Origin XPI shipped in the APK assets on first run.
 *
 * It is installed as a regular (non built-in) add-on so it stays visible in the add-ons manager, keeps its AMO
 * update_url and is updated by the regular add-on updater independently of app releases. If the user later removes
 * the add-on it is not reinstalled.
 */
class UboProvisioner(
    private val context: Context,
    private val addonManager: AddonManager,
) {
    private val logger = Logger("VidframeUbo")
    private val prefs = context.getSharedPreferences("vidframe", Context.MODE_PRIVATE)
    private val scope = MainScope()

    fun start() {
        if (prefs.getBoolean(KEY_PROVISIONED, false)) {
            return
        }
        scope.launch {
            val xpi = withContext(Dispatchers.IO) { copyAsset() } ?: return@launch
            addonManager.installAddon(
                url = Uri.fromFile(xpi).toString(),
                installationMethod = InstallationMethod.FROM_FILE,
                onSuccess = { addon ->
                    logger.info("Installed ${addon.id} ${addon.version}")
                    prefs.edit { putBoolean(KEY_PROVISIONED, true) }
                    xpi.delete()
                },
                onError = { logger.error("uBO install failed", it) },
            )
        }
    }

    private fun copyAsset(): File? =
        runCatching {
                val target = File(context.cacheDir, "ublock_origin.xpi")
                context.assets.open(VidframeConfig.UBO_ASSET).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target
            }
            .onFailure { logger.error("Missing ${VidframeConfig.UBO_ASSET}; run vidframe/scripts/fetch-ubo.sh", it) }
            .getOrNull()

    companion object {
        private const val KEY_PROVISIONED = "ubo_provisioned"

        /**
         * Answers install-time prompts for uBlock Origin without UI: required permissions and private browsing access
         * are granted and the post-install dialog is skipped. Returns false for any other add-on or prompt, which then
         * goes through the regular Fenix dialogs. Only AMO-signed add-ons can carry this ID.
         */
        fun autoAccept(request: WebExtensionPromptRequest.AfterInstallation): Boolean {
            if (request.extension.id != VidframeConfig.UBO_ID) {
                return false
            }
            return when (request) {
                is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                    request.onConfirm(PermissionPromptResponse(isPermissionsGranted = true, isPrivateModeGranted = true))
                    true
                }
                is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> true
                is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> false
            }
        }
    }
}
