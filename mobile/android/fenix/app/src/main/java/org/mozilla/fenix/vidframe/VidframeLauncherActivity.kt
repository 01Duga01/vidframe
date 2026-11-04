/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.ext.putUrlOverride
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor.Companion.ACTION_VIEW_PWA
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.mozilla.fenix.ext.components

/**
 * Launcher entry point. Opens [VidframeConfig.START_URL] as a standalone web app, which in Fenix means a single
 * external-app session without address bar, search, tabs tray or home screen. Re-launching reuses the existing session.
 *
 * Start-up path, kept as short as possible:
 * - the system splash screen (Theme.Vidframe.Launcher) is shown from the first frame and kept until the web app is
 *   started, so there is no blank window;
 * - the web-app intent is processed here and [ExternalAppBrowserActivity] is started directly, skipping the
 *   IntentReceiverActivity trampoline;
 * - the pinned manifest is verified once per process, and the first page load waits for uBlock Origin only on the
 *   very first run, and only briefly ([VidframeConfig.UBO_WAIT_MS]); a slower install reloads the page once instead.
 */
class VidframeLauncherActivity : Activity() {
    private val scope = MainScope()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !started }
        super.onCreate(savedInstanceState)

        if (manifestPinned && VidframeIntegration.uboReady.value) {
            openWebApp()
            return
        }
        scope.launch {
            if (!VidframeIntegration.uboReady.value) {
                withTimeoutOrNull(VidframeConfig.UBO_WAIT_MS) { VidframeIntegration.uboReady.first { it } }
                VidframeIntegration.openedBeforeUbo = !VidframeIntegration.uboReady.value
            }
            pinManifest()
            openWebApp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun openWebApp() {
        val webApp = buildWebAppIntent(packageName, intent?.data?.toString())
        val processed = components.intentProcessors.externalAppIntentProcessors.any { it.process(webApp) }
        if (processed) {
            components.core.engine.speculativeCreateSession(private = false)
            webApp.setClassName(this, ExternalAppBrowserActivity::class.java.name)
            webApp.putExtra(HomeActivity.OPEN_TO_BROWSER, true)
        }
        started = true
        startActivity(webApp, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle())
        finish()
    }

    /** Restores the pinned web-app manifest when it is missing or was changed, for example after clearing data. */
    private suspend fun pinManifest() {
        val storage = ManifestStorage(applicationContext)
        if (storage.loadManifest(VidframeConfig.START_URL) != MANIFEST) {
            storage.saveManifest(MANIFEST)
        }
        manifestPinned = true
    }

    companion object {
        /** Set once the manifest has been verified in this process; later launches skip the database. */
        @Volatile private var manifestPinned = false

        /** Colours come from the app theme (light or dark), so the manifest pins none. */
        @VisibleForTesting
        internal val MANIFEST =
            WebAppManifest(
                name = VidframeConfig.APP_TITLE,
                shortName = VidframeConfig.APP_TITLE,
                startUrl = VidframeConfig.START_URL,
                scope = VidframeConfig.SCOPE,
                display = WebAppManifest.DisplayMode.STANDALONE,
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
