/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.ext.putUrlOverride
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor.Companion.ACTION_VIEW_PWA

/**
 * Launcher entry point. Opens [VidframeConfig.START_URL] as a standalone web app, which in Fenix means a single
 * external-app session without address bar, search, tabs tray or home screen. Re-launching reuses the existing session.
 *
 * On first run the page load is held back until uBlock Origin is installed (at most [VidframeConfig.UBO_WAIT_MS]), so
 * the very first page is already filtered. The stored web-app manifest is rewritten on every start, which keeps the
 * start page fixed even if the site publishes a manifest of its own.
 */
class VidframeLauncherActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            if (!VidframeIntegration.uboReady.value) {
                showProgress()
                withTimeoutOrNull(VidframeConfig.UBO_WAIT_MS) { VidframeIntegration.uboReady.first { it } }
            }
            pinManifestIfNeeded()
            startActivity(buildWebAppIntent(packageName, intent?.data?.toString()))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Restores the pinned web-app manifest when it is missing or was changed (for example after clearing site data).
     * The usual start only reads it, which keeps the launcher hop short.
     */
    private suspend fun pinManifestIfNeeded() {
        val storage = ManifestStorage(applicationContext)
        if (storage.loadManifest(VidframeConfig.START_URL) != MANIFEST) {
            storage.saveManifest(MANIFEST)
        }
    }

    private fun showProgress() {
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    ProgressBar(this@VidframeLauncherActivity).apply { isIndeterminate = true },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            },
        )
    }

    companion object {
        @VisibleForTesting
        internal val MANIFEST =
            WebAppManifest(
                name = VidframeConfig.APP_TITLE,
                shortName = VidframeConfig.APP_TITLE,
                startUrl = VidframeConfig.START_URL,
                scope = VidframeConfig.SCOPE,
                display = WebAppManifest.DisplayMode.STANDALONE,
                backgroundColor = Color.BLACK,
                themeColor = Color.BLACK,
            )

        /**
         * Intent that opens the pinned web app. [link] (an incoming VIEW url) is only honoured when it is on the
         * allowlist; the session itself always starts from [VidframeConfig.START_URL].
         */
        @VisibleForTesting
        internal fun buildWebAppIntent(packageName: String, link: String?): Intent =
            Intent(ACTION_VIEW_PWA, VidframeConfig.START_URL.toUri()).apply {
                `package` = packageName
                putUrlOverride(link?.takeIf { NavigationAllowlist.isAllowed(it) && it.startsWith("https://") })
            }

        /** Leaves any screen that is not part of the client and goes back to the pinned start page. */
        fun relaunch(activity: Activity) {
            activity.startActivity(
                Intent(activity, VidframeLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity.finish()
        }
    }
}
