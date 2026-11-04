/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VidframeConfigTest {

    @Test
    fun `web content is rendered at 70 percent by default`() {
        assertEquals(0.7f, VidframeConfig.contentScale, 0.0001f)
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `the density handed to Gecko is the system density times the content scale`() {
        val density = testContext.resources.displayMetrics.density

        assertEquals(density * 0.7f, VidframeConfig.contentDensityOverride(testContext, 0.7f)!!, 0.0001f)
    }

    @Test
    fun `a scale of 1 keeps the system density`() {
        assertNull(VidframeConfig.contentDensityOverride(testContext, 1f))
    }
}
