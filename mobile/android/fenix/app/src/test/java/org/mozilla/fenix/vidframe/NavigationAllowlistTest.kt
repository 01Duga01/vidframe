/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationAllowlistTest {

    @Test
    fun `video site, short links and CDNs are allowed`() {
        listOf(
                "https://m.youtube.com/watch?v=abc",
                "https://www.youtube.com/",
                "https://youtu.be/abc",
                "https://i.ytimg.com/vi/abc/hq.jpg",
                "https://rr1---sn-xyz.googlevideo.com/videoplayback",
                "https://accounts.google.com/ServiceLogin",
                "https://consent.youtube.com/m",
                "https://www.google.com/recaptcha/api2/anchor",
                "moz-extension://1234/dashboard.html",
                "about:blank",
            )
            .forEach { assertTrue(it, NavigationAllowlist.isAllowed(it)) }
    }

    @Test
    fun `other sites, google search and app schemes are blocked`() {
        listOf(
                "https://example.com/",
                "https://www.google.com/search?q=test",
                "https://maps.google.com/",
                "https://evilyoutube.com/",
                "https://youtube.com.evil.example/",
                "intent://watch?v=abc#Intent;scheme=vnd.youtube;end",
                "vnd.youtube:abc",
                "market://details?id=x",
                "file:///sdcard/a.html",
            )
            .forEach { assertFalse(it, NavigationAllowlist.isAllowed(it)) }
    }

    @Test
    fun `blocked subframes are denied silently and top-level loads get a block page`() {
        val interceptor = VidframeRequestInterceptor(testContext)

        assertNull(interceptor.onLoadRequest(VidframeConfig.START_URL, true, isSubframeRequest = false))
        assertEquals(
            InterceptionResponse.Deny,
            interceptor.onLoadRequest("https://ads.example/", hasUserGesture = false, isSubframeRequest = true),
        )
        val page = interceptor.onLoadRequest("https://example.com/<x>", true, isSubframeRequest = false)
        assertTrue(page is InterceptionResponse.Content)
        assertFalse((page as InterceptionResponse.Content).data.contains("<x>"))
    }

    @Test
    fun `external hand-off is refused without a user gesture`() {
        val interceptor = VidframeRequestInterceptor(testContext)
        val uri = "${VidframeRequestInterceptor.EXTERNAL_SCHEME}://open?u=https%3A%2F%2Fexample.com%2F"

        assertEquals(InterceptionResponse.Deny, interceptor.onLoadRequest(uri, false, false))
    }
}
