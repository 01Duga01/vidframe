/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.feature.addons.AddonManager
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.log.logger.Logger
import java.io.File

/**
 * Installs the AMO-signed uBlock Origin XPI shipped in the APK assets on first run.
 *
 * It is installed as a regular (non built-in) add-on so it stays visible in the add-ons manager, keeps its AMO
 * update_url and is updated by the regular add-on updater independently of app releases. Install-time prompts for
 * [VidframeConfig.UBO_ID] are answered here, without any activity, so the launcher can wait for [ready] before the
 * first page load. If the user later removes the add-on it is not reinstalled.
 */
class UboProvisioner(
    private val context: Context,
    private val store: BrowserStore,
    private val addonManager: AddonManager,
    private val scope: CoroutineScope = MainScope(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = Logger("VidframeUbo")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _ready = MutableStateFlow(prefs.getBoolean(KEY_PROVISIONED, false))

    /** True once uBlock Origin is installed, or once installing it has failed and waiting would be pointless. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun start() {
        observePrompts()
        if (_ready.value) {
            return
        }
        scope.launch {
            val xpi = withContext(ioDispatcher) { copyAsset() }
            if (xpi == null) {
                _ready.value = true
                return@launch
            }
            addonManager.installAddon(
                url = Uri.fromFile(xpi).toString(),
                installationMethod = InstallationMethod.FROM_FILE,
                onSuccess = { addon ->
                    logger.info("Installed ${addon.id} ${addon.version}")
                    prefs.edit { putBoolean(KEY_PROVISIONED, true) }
                    xpi.delete()
                    _ready.value = true
                },
                onError = {
                    logger.error("uBO install failed", it)
                    _ready.value = true
                },
            )
        }
    }

    private fun observePrompts() {
        scope.launch {
            store.flow().map { it.webExtensionPromptRequest }.distinctUntilChanged().collect { request ->
                if (request is WebExtensionPromptRequest.AfterInstallation && autoAccept(request)) {
                    store.dispatch(WebExtensionAction.ConsumePromptRequestWebExtensionAction)
                }
            }
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
        @VisibleForTesting internal const val PREFS_NAME = "vidframe"

        @VisibleForTesting internal const val KEY_PROVISIONED = "ubo_provisioned"

        /**
         * True when [request] is answered by [autoAccept]; Fenix's own prompt UI must then stay out of the way and must
         * not consume it.
         */
        fun isHandledByProvisioner(request: WebExtensionPromptRequest.AfterInstallation): Boolean =
            request.extension.id == VidframeConfig.UBO_ID &&
                request !is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional

        /**
         * Answers install-time prompts for uBlock Origin without UI: required permissions and private browsing access
         * are granted and the post-install dialog is skipped. Returns false for any other add-on or prompt. Only
         * AMO-signed add-ons can carry this ID.
         */
        @VisibleForTesting
        internal fun autoAccept(request: WebExtensionPromptRequest.AfterInstallation): Boolean {
            if (!isHandledByProvisioner(request)) {
                return false
            }
            return when (request) {
                is WebExtensionPromptRequest.AfterInstallation.Permissions.Required -> {
                    request.onConfirm(
                        PermissionPromptResponse(isPermissionsGranted = true, isPrivateModeGranted = true),
                    )
                    true
                }
                is WebExtensionPromptRequest.AfterInstallation.PostInstallation -> true
                is WebExtensionPromptRequest.AfterInstallation.Permissions.Optional -> false
            }
        }
    }
}
