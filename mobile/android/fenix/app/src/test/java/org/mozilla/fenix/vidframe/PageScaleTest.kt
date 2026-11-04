/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PageScaleTest {

    @Before
    fun setup() {
        PreferenceManager.getDefaultSharedPreferences(testContext).edit { clear() }
    }

    @Test
    fun `auto scale follows the screen width`() {
        assertEquals(0.65f, PageScale.adaptive(smallestWidthDp = 360, fontScale = 1f), 0.001f)
        assertEquals(0.70f, PageScale.adaptive(smallestWidthDp = 393, fontScale = 1f), 0.001f)
        assertEquals(0.75f, PageScale.adaptive(smallestWidthDp = 411, fontScale = 1f), 0.001f)
        assertEquals(1.0f, PageScale.adaptive(smallestWidthDp = 600, fontScale = 1f), 0.001f)
    }

    @Test
    fun `a larger system font or display size lowers the auto scale, never below 50 percent`() {
        assertEquals(0.55f, PageScale.adaptive(smallestWidthDp = 393, fontScale = 1.3f), 0.001f)
        // A bigger "display size" setting shows up as fewer dp.
        assertEquals(0.60f, PageScale.adaptive(smallestWidthDp = 340, fontScale = 1f), 0.001f)
        assertEquals(0.5f, PageScale.adaptive(smallestWidthDp = 320, fontScale = 2f), 0.001f)
    }

    @Test
    fun `fixed choices are parsed and clamped, auto is not a number`() {
        assertEquals(0.8f, PageScale.parse("0.8")!!, 0.001f)
        assertEquals(0.5f, PageScale.parse("0.2")!!, 0.001f)
        assertEquals(1f, PageScale.parse("1.5")!!, 0.001f)
        assertNull(PageScale.parse(PageScale.AUTO))
        assertNull(PageScale.parse(null))
    }

    @Test
    @Config(qualifiers = "sw393dp-w393dp-h851dp-xxhdpi")
    fun `the user's choice wins over auto`() {
        assertEquals(0.70f, PageScale.current(testContext), 0.001f)

        PreferenceManager.getDefaultSharedPreferences(testContext).edit { putString(PageScale.PREF_KEY, "0.9") }

        assertEquals(0.9f, PageScale.current(testContext), 0.001f)
        assertEquals(90, PageScale.percent(PageScale.current(testContext)))
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `the density handed to Gecko is the system density times the scale`() {
        val density = testContext.resources.displayMetrics.density

        assertEquals(density * 0.7f, PageScale.densityOverride(testContext, 0.7f)!!, 0.0001f)
        assertNull(PageScale.densityOverride(testContext, 1f))
    }
}
