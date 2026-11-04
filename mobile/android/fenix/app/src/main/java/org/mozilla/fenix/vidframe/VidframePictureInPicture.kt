/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.annotation.VisibleForTesting
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.support.base.log.logger.Logger

/**
 * Like the native app, pressing Home while a video plays moves it into the system picture-in-picture window, also
 * when the player is not fullscreen. The page styles (m-youtube.css) then let the video fill that small window.
 * Fenix's own feature only handles fullscreen video and runs first.
 */
object VidframePictureInPicture {
    private val logger = Logger("VidframePip")

    @VisibleForTesting internal val ASPECT_RATIO = Rational(16, 9)

    @VisibleForTesting
    internal fun shouldEnter(tab: SessionState?): Boolean =
        tab?.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING

    fun onHomePressed(activity: Activity, tab: SessionState?): Boolean {
        if (!shouldEnter(tab) ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return false
        }
        return try {
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ASPECT_RATIO).build())
        } catch (e: IllegalStateException) {
            logger.warn("Entering picture-in-picture failed", e)
            false
        }
    }
}
