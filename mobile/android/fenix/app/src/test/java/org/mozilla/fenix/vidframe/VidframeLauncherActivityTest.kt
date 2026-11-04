/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.pwa.ext.getUrlOverride
import mozilla.components.feature.pwa.intent.WebAppIntentProcessor.Companion.ACTION_VIEW_PWA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VidframeLauncherActivityTest {

    @Test
    fun `the start page is pinned to the mobile video site`() {
        assertEquals("https://m.youtube.com/", VidframeConfig.START_URL)
        assertEquals(VidframeConfig.START_URL, VidframeLauncherActivity.MANIFEST.startUrl)
        assertEquals(VidframeConfig.SCOPE, VidframeLauncherActivity.MANIFEST.scope)
        assertEquals(WebAppManifest.DisplayMode.STANDALONE, VidframeLauncherActivity.MANIFEST.display)
    }

    @Test
    fun `GIVEN a launcher start THEN the pinned web app is opened at the start page`() {
        val intent = VidframeLauncherActivity.buildWebAppIntent("io.github.vidframe.app", null)

        assertEquals(ACTION_VIEW_PWA, intent.action)
        assertEquals(VidframeConfig.START_URL, intent.dataString)
        assertEquals("io.github.vidframe.app", intent.`package`)
        assertNull(intent.getUrlOverride())
    }

    @Test
    fun `GIVEN an incoming video link THEN it opens inside the pinned web app`() {
        val intent = VidframeLauncherActivity.buildWebAppIntent("pkg", "https://youtu.be/abc")

        assertEquals(VidframeConfig.START_URL, intent.dataString)
        assertEquals("https://youtu.be/abc", intent.getUrlOverride())
    }

    @Test
    fun `GIVEN an incoming link outside the allowlist or without https THEN only the start page is opened`() {
        assertNull(VidframeLauncherActivity.buildWebAppIntent("pkg", "https://example.com/").getUrlOverride())
        assertNull(VidframeLauncherActivity.buildWebAppIntent("pkg", "http://m.youtube.com/").getUrlOverride())
        assertNull(VidframeLauncherActivity.buildWebAppIntent("pkg", "vnd.youtube:abc").getUrlOverride())
    }
}
