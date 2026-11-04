/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import io.mockk.mockk
import io.mockk.verify
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.preferences.Branch
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalAndroidComponentsApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoCodecTuningTest {

    @Before
    fun setup() {
        testContext.getSharedPreferences("vidframe", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `the platform answer wins when it is available`() {
        assertTrue(VideoCodecTuning.isHardwareCodec("c2.android.av1.decoder", reportedHardware = true))
        assertFalse(VideoCodecTuning.isHardwareCodec("c2.qti.av1.decoder", reportedHardware = false))
    }

    @Test
    fun `without a platform answer software codecs are recognised by name`() {
        assertFalse(VideoCodecTuning.isHardwareCodec("c2.android.av1.decoder", reportedHardware = null))
        assertFalse(VideoCodecTuning.isHardwareCodec("OMX.google.av1.decoder", reportedHardware = null))
        assertFalse(VideoCodecTuning.isHardwareCodec("c2.google.av1.decoder", reportedHardware = null))
        assertTrue(VideoCodecTuning.isHardwareCodec("c2.qti.av1.decoder", reportedHardware = null))
        assertTrue(VideoCodecTuning.isHardwareCodec("c2.mtk.av1.decoder", reportedHardware = null))
    }

    @Test
    fun `GIVEN no hardware AV1 decoder WHEN applied THEN AV1 is disabled on the default branch`() {
        val engine = mockk<Engine>(relaxed = true)

        VideoCodecTuning.apply(testContext, engine)

        verify { engine.setBrowserPref(VideoCodecTuning.AV1_PREF, false, Branch.DEFAULT, any(), any()) }
    }

    @Test
    fun `GIVEN a cached scan for this OS build WHEN applied again THEN the cached answer is used`() {
        val engine = mockk<Engine>(relaxed = true)
        VideoCodecTuning.apply(testContext, engine)
        testContext
            .getSharedPreferences("vidframe", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("codec_hw_av1", true)
            .commit()
        val second = mockk<Engine>(relaxed = true)

        VideoCodecTuning.apply(testContext, second)

        verify(exactly = 0) { second.setBrowserPref(any(), any<Boolean>(), any(), any(), any()) }
    }
}
