/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.feature.addons.update.AddonUpdater
import mozilla.components.support.base.log.logger.Logger

/**
 * Once per calendar day, on the first app start of that day, asks addons.mozilla.org for the current uBlock Origin
 * release and, if it is newer than the installed one, starts the regular add-on update right away (in addition to
 * Fenix's periodic background updates).
 *
 * The day comes from the device clock and time zone and is stored only locally (SharedPreferences "vidframe").
 * Runs in the background and never delays start-up. Does nothing before the first-run setup has installed uBlock
 * Origin, or after the user removed it.
 */
class UboDailyCheck(
    context: Context,
    private val runtime: WebExtensionRuntime,
    private val updater: AddonUpdater,
    private val scope: CoroutineScope = MainScope(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val latestRelease: () -> UboRelease? = { UboRelease.fetchLatest(CHECK_TIMEOUT_MS) },
) {
    private val logger = Logger("VidframeUbo")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when no check has run yet today (device date). */
    fun isDue(): Boolean = prefs.getString(KEY_LAST_CHECK_DAY, null) != today().toString()

    fun runIfDue() {
        if (!prefs.getBoolean(UboProvisioner.KEY_PROVISIONED, false) || !isDue()) {
            return
        }
        prefs.edit { putString(KEY_LAST_CHECK_DAY, today().toString()) }
        scope.launch {
            val installed = installedVersion() ?: return@launch logger.info("Daily check: uBlock Origin not installed")
            val latest =
                withContext(ioDispatcher) { runCatching { latestRelease() }.getOrNull() }
                    ?: return@launch logger.info("Daily check: AMO unreachable, will retry tomorrow")
            if (UboRelease.compareVersions(latest.version, installed) > 0) {
                logger.info("Daily check: updating uBlock Origin $installed -> ${latest.version}")
                updater.update(VidframeConfig.UBO_ID)
            } else {
                logger.info("Daily check: uBlock Origin $installed is current")
            }
        }
    }

    private suspend fun installedVersion(): String? =
        suspendCancellableCoroutine { continuation ->
            runtime.listInstalledWebExtensions(
                onSuccess = { extensions ->
                    val version = extensions.firstOrNull { it.id == VidframeConfig.UBO_ID }?.getMetadata()?.version
                    if (continuation.isActive) continuation.resume(version)
                },
                onError = { if (continuation.isActive) continuation.resume(null) },
            )
        }

    companion object {
        private const val PREFS_NAME = UboProvisioner.PREFS_NAME

        @VisibleForTesting internal const val KEY_LAST_CHECK_DAY = "ubo_last_check_day"
        private const val CHECK_TIMEOUT_MS = 6_000
    }
}
