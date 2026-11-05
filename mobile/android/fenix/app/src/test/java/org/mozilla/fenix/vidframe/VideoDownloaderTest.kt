/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.app.Application
import android.content.Intent
import android.net.Uri
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VideoDownloaderTest {
    private fun downloadUri(videoUrl: String) = "${VideoDownloader.SCHEME}://open?u=" + Uri.encode(videoUrl)

    @Test
    fun `the video id is taken from watch, Shorts, live, embed and short links`() {
        assertEquals("dQw4w9WgXcQ", VideoDownloader.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals("dQw4w9WgXcQ", VideoDownloader.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoDownloader.videoId("https://youtube.com/live/dQw4w9WgXcQ?feature=share"))
        assertEquals("dQw4w9WgXcQ", VideoDownloader.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoDownloader.videoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `anything that is not a single video of the site yields no id`() {
        assertNull(VideoDownloader.videoId("https://m.youtube.com/"))
        assertNull(VideoDownloader.videoId("https://m.youtube.com/watch?v=short"))
        assertNull(VideoDownloader.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ%22%3E"))
        assertNull(VideoDownloader.videoId("https://evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(VideoDownloader.videoId("javascript:alert(1)//watch?v=dQw4w9WgXcQ"))
        assertNull(VideoDownloader.videoId(null))
    }

    @Test
    fun `the service URL carries a canonical video URL rebuilt from the id`() {
        assertEquals(
            "https://savefrom.net/?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ",
            VideoDownloader.serviceUrl("dQw4w9WgXcQ", prefix = "https://savefrom.net/?url="),
        )
    }

    @Test
    fun `a tap on Download opens the service in another browser`() {
        val interceptor = VidframeRequestInterceptor(testContext)
        val uri = downloadUri("https://m.youtube.com/watch?v=dQw4w9WgXcQ")

        assertEquals(InterceptionResponse.Deny, interceptor.onLoadRequest(uri, true, false))

        val chooser = shadowOf(testContext as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(target.dataString!!.startsWith("https://savefrom.net/?url="))
        assertTrue(target.dataString!!.endsWith("dQw4w9WgXcQ"))
    }

    @Test
    fun `without a user gesture or with a bad link nothing is opened`() {
        val interceptor = VidframeRequestInterceptor(testContext)
        val good = downloadUri("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        val bad = downloadUri("https://evil.example/")

        assertEquals(InterceptionResponse.Deny, interceptor.onLoadRequest(good, false, false))
        assertEquals(InterceptionResponse.Deny, interceptor.onLoadRequest(bad, true, false))
        assertNull(shadowOf(testContext as Application).nextStartedActivity)
    }
}
