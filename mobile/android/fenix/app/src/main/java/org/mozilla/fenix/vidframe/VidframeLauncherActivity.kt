/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.ext.putUrlOverride
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor.Companion.ACTION_VIEW_PWA
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.mozilla.fenix.ext.components

/**
 * Launcher entry point. Opens [VidframeConfig.START_URL] as a standalone web app, which in Fenix means a single
 * external-app session without address bar, search, tabs tray or home screen. Re-launching reuses the existing session.
 *
 * Start-up path:
 * - the system splash screen (Theme.Vidframe.Launcher) is shown from the first frame;
 * - on later launches the web-app intent is processed here and [ExternalAppBrowserActivity] is started directly,
 *   skipping the IntentReceiverActivity trampoline; the pinned manifest is verified once per process;
 * - on the very first run the splash hands over to [VidframeSetupScreen] until uBlock Origin is installed (the newest
 *   release, downloaded if the bundled copy is outdated). After [SKIP_AFTER_MS] the user may continue without it; the
 *   page is then reloaded once the blocker is ready.
 */
class VidframeLauncherActivity : ComponentActivity() {
    private val scope = MainScope()
    private var keepSplash = true
    private val skipped = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplash }
        super.onCreate(savedInstanceState)

        if (manifestPinned && VidframeIntegration.uboReady.value) {
            openWebApp()
            return
        }
        if (!VidframeIntegration.uboReady.value) {
            splash.setOnExitAnimationListener { provider -> animateSplashExit(provider.view, provider::remove) }
            showSetup()
            keepSplash = false
        }
        scope.launch {
            pinManifest()
            if (!VidframeIntegration.uboReady.value) {
                VidframeIntegration.uboState
                    .combine(skipped) { state, skip -> state.phase == UboSetupState.Phase.READY || skip }
                    .first { it }
                if (!VidframeIntegration.uboReady.value) {
                    VidframeIntegration.openedBeforeUbo = true
                } else {
                    delay(READY_HOLD_MS) // let "Ready" and the full progress bar register for a moment
                }
            }
            openWebApp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun showSetup() {
        enableEdgeToEdge()
        val showSkip = MutableStateFlow(false)
        scope.launch {
            delay(SKIP_AFTER_MS)
            showSkip.value = true
        }
        setContent {
            val state by VidframeIntegration.uboState.collectAsState()
            val skip by showSkip.collectAsState()
            VidframeSetupScreen(state = state, showSkip = skip, onSkip = { skipped.value = true })
        }
    }

    /** The splash icon grows slightly and fades while the setup screen rises underneath. */
    private fun animateSplashExit(view: View, remove: () -> Unit) {
        AnimatorSet()
            .apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, SPLASH_EXIT_SCALE),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, SPLASH_EXIT_SCALE),
                )
                duration = SPLASH_EXIT_MS
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                doOnEnd { remove() }
            }
            .start()
    }

    private fun openWebApp() {
        val webApp = buildWebAppIntent(packageName, intent?.data?.toString())
        val processed = components.intentProcessors.externalAppIntentProcessors.any { it.process(webApp) }
        if (processed) {
            components.core.engine.speculativeCreateSession(private = false)
            webApp.setClassName(this, ExternalAppBrowserActivity::class.java.name)
            webApp.putExtra(HomeActivity.OPEN_TO_BROWSER, true)
        }
        keepSplash = false
        val transition =
            ActivityOptions.makeCustomAnimation(this, R.anim.vidframe_open_enter, R.anim.vidframe_open_exit)
        startActivity(webApp, transition.toBundle())
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

        @VisibleForTesting internal const val SKIP_AFTER_MS = 20_000L
        private const val READY_HOLD_MS = 350L
        private const val SPLASH_EXIT_MS = 320L
        private const val SPLASH_EXIT_SCALE = 1.12f

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
