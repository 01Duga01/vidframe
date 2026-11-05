/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import java.io.File
import kotlin.coroutines.resume
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.feature.addons.AddonManager
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.BuildConfig

/**
 * Progress of the first-run uBlock Origin setup, shown by the launcher.
 *
 * @property version the uBlock Origin version being downloaded or installed, if known.
 * @property progress download progress in 0..1, or null when indeterminate.
 */
data class UboSetupState(val phase: Phase, val version: String? = null, val progress: Float? = null) {
    enum class Phase {
        /** Asking addons.mozilla.org whether the bundled copy is still the newest release. */
        CHECKING,

        /** A newer release than the bundled one is being downloaded. */
        DOWNLOADING,

        /** Gecko is installing (verifying and starting) the add-on. */
        INSTALLING,

        /** Done: installed, or failed and waiting is pointless. */
        READY,
    }
}

/**
 * Installs uBlock Origin on first run, always the newest release: the AMO-signed XPI bundled in the APK is used when it
 * is still the current version on addons.mozilla.org, otherwise the current release is downloaded from AMO (SHA-256
 * checked; Gecko also verifies the AMO signature). Offline or on any AMO error the bundled copy is installed.
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
    private val onInstalled: () -> Unit = {},
    private val bundledVersion: String = BuildConfig.VIDFRAME_UBO_VERSION,
    private val latestRelease: () -> UboRelease? = { UboRelease.fetchLatest(CHECK_TIMEOUT_MS) },
    private val download: (UboRelease, File, (Float?) -> Unit) -> Boolean = { release, target, onProgress ->
        release.download(target, DOWNLOAD_TIMEOUT_MS, onProgress)
    },
) {
    private val logger = Logger("VidframeUbo")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alreadyProvisioned = prefs.getBoolean(KEY_PROVISIONED, false)
    private val _ready = MutableStateFlow(alreadyProvisioned)
    private val _state =
        MutableStateFlow(
            UboSetupState(if (alreadyProvisioned) UboSetupState.Phase.READY else UboSetupState.Phase.CHECKING),
        )

    /** True once uBlock Origin is installed, or once installing it has failed and waiting would be pointless. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Detailed progress for the first-run setup screen. */
    val state: StateFlow<UboSetupState> = _state.asStateFlow()

    fun start() {
        observePrompts()
        if (_ready.value) {
            return
        }
        scope.launch { provision() }
    }

    private suspend fun provision() {
        val bundled = withContext(ioDispatcher) { copyAsset() }
        val latest = withContext(ioDispatcher) { runCatching { latestRelease() }.getOrNull() }
        var candidate = bundled?.let { it to bundledVersion }

        if (latest != null && (bundled == null || UboRelease.compareVersions(latest.version, bundledVersion) > 0)) {
            logger.info("Bundled uBlock Origin '$bundledVersion' is outdated; downloading ${latest.version}")
            _state.value = UboSetupState(UboSetupState.Phase.DOWNLOADING, latest.version, 0f)
            val target = File(context.cacheDir, LATEST_FILE)
            val downloaded =
                withContext(ioDispatcher) {
                    runCatching {
                            download(latest, target) { progress ->
                                _state.value = UboSetupState(UboSetupState.Phase.DOWNLOADING, latest.version, progress)
                            }
                        }
                        .getOrDefault(false)
                }
            if (downloaded) {
                candidate = target to latest.version
            } else {
                logger.warn("Download of ${latest.version} failed; using the bundled copy")
            }
        } else {
            logger.info("Bundled uBlock Origin $bundledVersion is current (AMO: ${latest?.version ?: "unreachable"})")
        }

        val (file, version) = candidate ?: return finish(success = false)
        _state.value = UboSetupState(UboSetupState.Phase.INSTALLING, version)
        var installed = install(file)
        if (!installed && bundled != null && file != bundled) {
            _state.value = UboSetupState(UboSetupState.Phase.INSTALLING, bundledVersion)
            installed = install(bundled)
        }
        withContext(ioDispatcher) {
            bundled?.delete()
            File(context.cacheDir, LATEST_FILE).delete()
        }
        finish(installed)
    }

    private suspend fun install(xpi: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            addonManager.installAddon(
                url = Uri.fromFile(xpi).toString(),
                installationMethod = InstallationMethod.FROM_FILE,
                onSuccess = { addon ->
                    if (addon.id == VidframeConfig.UBO_ID) {
                        logger.info("Installed ${addon.id} ${addon.version}")
                        continuation.resume(true)
                    } else {
                        logger.error("Unexpected add-on ${addon.id}; removing it")
                        addonManager.uninstallAddon(addon)
                        continuation.resume(false)
                    }
                },
                onError = {
                    logger.error("uBO install failed", it)
                    continuation.resume(false)
                },
            )
        }

    private fun finish(success: Boolean) {
        if (success) {
            prefs.edit { putBoolean(KEY_PROVISIONED, true) }
        }
        _state.value = UboSetupState(UboSetupState.Phase.READY)
        _ready.value = true
        if (success) {
            onInstalled()
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

        private const val LATEST_FILE = "ublock_origin_latest.xpi"
        private const val CHECK_TIMEOUT_MS = 6_000
        private const val DOWNLOAD_TIMEOUT_MS = 20_000

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
