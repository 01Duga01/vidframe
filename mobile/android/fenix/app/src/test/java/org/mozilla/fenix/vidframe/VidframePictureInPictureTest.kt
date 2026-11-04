/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.mediasession.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VidframePictureInPictureTest {

    private fun tab(state: MediaSession.PlaybackState) =
        createTab(
            url = "https://m.youtube.com/watch?v=abc",
            mediaSessionState = MediaSessionState(controller = mockk(), playbackState = state),
        )

    private fun activity(hasPip: Boolean): Activity {
        val packageManager = mockk<PackageManager> {
            every { hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) } returns hasPip
        }
        return mockk(relaxed = true) {
            every { this@mockk.packageManager } returns packageManager
            every { enterPictureInPictureMode(any<PictureInPictureParams>()) } returns true
        }
    }

    @Test
    fun `GIVEN a playing video that is not fullscreen WHEN Home is pressed THEN it moves into a 16 by 9 PiP window`() {
        val activity = activity(hasPip = true)
        val params = slot<PictureInPictureParams>()
        every { activity.enterPictureInPictureMode(capture(params)) } returns true

        assertTrue(VidframePictureInPicture.onHomePressed(activity, tab(MediaSession.PlaybackState.PLAYING)))
        assertEquals(VidframePictureInPicture.ASPECT_RATIO, params.captured.aspectRatio)
    }

    @Test
    fun `GIVEN a paused video WHEN Home is pressed THEN the app is simply left`() {
        val activity = activity(hasPip = true)

        assertFalse(VidframePictureInPicture.onHomePressed(activity, tab(MediaSession.PlaybackState.PAUSED)))
        assertFalse(VidframePictureInPicture.onHomePressed(activity, null))
        verify(exactly = 0) { activity.enterPictureInPictureMode(any<PictureInPictureParams>()) }
    }

    @Test
    fun `GIVEN a device without PiP WHEN Home is pressed THEN nothing happens`() {
        val activity = activity(hasPip = false)

        assertFalse(VidframePictureInPicture.onHomePressed(activity, tab(MediaSession.PlaybackState.PLAYING)))
    }

    @Test
    fun `GIVEN the system refuses PiP WHEN Home is pressed THEN the failure is swallowed`() {
        val activity = activity(hasPip = true)
        every { activity.enterPictureInPictureMode(any<PictureInPictureParams>()) } throws IllegalStateException()

        assertFalse(VidframePictureInPicture.onHomePressed(activity, tab(MediaSession.PlaybackState.PLAYING)))
    }
}
