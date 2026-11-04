/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.core.net.toUri
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.ext.putUrlOverride
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor.Companion.ACTION_VIEW_PWA

/**
 * Launcher entry point. Opens [VidframeConfig.START_URL] as a standalone web app, which in Fenix means a single
 * external-app session without address bar, tabs tray or home screen. Re-launching reuses the existing session.
 */
class VidframeLauncherActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            val storage = ManifestStorage(applicationContext)
            if (storage.loadManifest(VidframeConfig.START_URL) == null) {
                storage.saveManifest(MANIFEST)
            }
            val target = intent?.data?.takeIf { NavigationAllowlist.isAllowed(it.toString()) }
            startActivity(
                Intent(ACTION_VIEW_PWA, VidframeConfig.START_URL.toUri()).apply {
                    `package` = packageName
                    putUrlOverride(target?.toString())
                },
            )
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        val MANIFEST =
            WebAppManifest(
                name = VidframeConfig.APP_TITLE,
                shortName = VidframeConfig.APP_TITLE,
                startUrl = VidframeConfig.START_URL,
                scope = VidframeConfig.SCOPE,
                display = WebAppManifest.DisplayMode.STANDALONE,
                backgroundColor = Color.BLACK,
                themeColor = Color.BLACK,
            )
    }
}
