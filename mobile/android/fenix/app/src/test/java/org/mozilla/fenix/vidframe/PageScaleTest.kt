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
class PageScaleTest {

    @Test
    fun `the site is rendered at a fixed 80 percent`() {
        assertEquals(0.8f, PageScale.scale, 0.001f)
        assertEquals(80, PageScale.percent(PageScale.scale))
    }

    @Test
    @Config(qualifiers = "sw360dp-w360dp-h780dp-xhdpi")
    fun `the scale does not depend on the screen`() {
        val density = testContext.resources.displayMetrics.density

        assertEquals(density * 0.8f, PageScale.densityOverride(testContext)!!, 0.0001f)
    }

    @Test
    @Config(qualifiers = "sw600dp-w600dp-h960dp-xxhdpi")
    fun `the same scale applies on a large screen`() {
        val density = testContext.resources.displayMetrics.density

        assertEquals(density * 0.8f, PageScale.densityOverride(testContext)!!, 0.0001f)
    }

    @Test
    fun `build values are parsed and clamped`() {
        assertEquals(0.7f, PageScale.parse("0.7")!!, 0.001f)
        assertEquals(0.5f, PageScale.parse("0.2")!!, 0.001f)
        assertEquals(1f, PageScale.parse("1.5")!!, 0.001f)
        assertNull(PageScale.parse("auto"))
        assertNull(PageScale.densityOverride(testContext, 1f))
    }
}
