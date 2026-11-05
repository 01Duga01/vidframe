/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import androidx.core.content.edit
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.engine.webextension.Metadata
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.feature.addons.update.AddonUpdater
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UboDailyCheckTest {
    private val testDispatcher = StandardTestDispatcher()
    private val updater: AddonUpdater = mockk(relaxed = true)
    private val runtime: WebExtensionRuntime = mockk()
    private val prefs = testContext.getSharedPreferences(UboProvisioner.PREFS_NAME, Context.MODE_PRIVATE)
    private var day = LocalDate.of(2026, 11, 5)
    private var latestCalls = 0

    @Before
    fun setup() {
        prefs.edit {
            clear()
            putBoolean(UboProvisioner.KEY_PROVISIONED, true)
        }
        installed("1.62.0")
    }

    private fun installed(version: String?) {
        val extensions =
            if (version == null) {
                emptyList()
            } else {
                val metadata = mockk<Metadata> { every { this@mockk.version } returns version }
                listOf(
                    mockk<WebExtension> {
                        every { id } returns VidframeConfig.UBO_ID
                        every { getMetadata() } returns metadata
                    },
                )
            }
        val onSuccess = slot<(List<WebExtension>) -> Unit>()
        every { runtime.listInstalledWebExtensions(capture(onSuccess), any()) } answers {
            onSuccess.captured(extensions)
        }
    }

    private fun TestScope.check(latest: String? = "1.63.2") =
        UboDailyCheck(
            testContext,
            runtime,
            updater,
            scope = backgroundScope,
            ioDispatcher = testDispatcher,
            today = { day },
            latestRelease = {
                latestCalls++
                latest?.let { UboRelease(it, "https://addons.mozilla.org/x.xpi", "00", 0) }
            },
        )

    @Test
    fun `GIVEN a newer release on AMO WHEN the app starts for the first time today THEN uBlock Origin is updated`() =
        runTest(testDispatcher) {
            check().runIfDue()
            testScheduler.runCurrent()

            verify(exactly = 1) { updater.update(VidframeConfig.UBO_ID) }
            assertEquals("2026-11-05", prefs.getString(UboDailyCheck.KEY_LAST_CHECK_DAY, null))
        }

    @Test
    fun `GIVEN the installed version is current THEN nothing is updated`() =
        runTest(testDispatcher) {
            installed("1.63.2")
            check().runIfDue()
            testScheduler.runCurrent()

            verify(exactly = 0) { updater.update(any()) }
        }

    @Test
    fun `later starts on the same day do not check again, the next day does`() =
        runTest(testDispatcher) {
            val daily = check()
            daily.runIfDue()
            testScheduler.runCurrent()
            assertFalse(daily.isDue())

            daily.runIfDue()
            testScheduler.runCurrent()
            assertEquals(1, latestCalls)

            day = day.plusDays(1)
            assertTrue(daily.isDue())
            daily.runIfDue()
            testScheduler.runCurrent()
            assertEquals(2, latestCalls)
        }

    @Test
    fun `before the first-run setup or after the user removed uBlock Origin nothing happens`() =
        runTest(testDispatcher) {
            prefs.edit { putBoolean(UboProvisioner.KEY_PROVISIONED, false) }
            check().runIfDue()
            testScheduler.runCurrent()
            assertEquals(0, latestCalls)

            prefs.edit { putBoolean(UboProvisioner.KEY_PROVISIONED, true) }
            installed(null)
            check().runIfDue()
            testScheduler.runCurrent()
            assertEquals(0, latestCalls)
            verify(exactly = 0) { updater.update(any()) }
        }

    @Test
    fun `AMO being unreachable is not an error and is retried the next day`() =
        runTest(testDispatcher) {
            val daily = check(latest = null)
            daily.runIfDue()
            testScheduler.runCurrent()

            verify(exactly = 0) { updater.update(any()) }
            assertFalse(daily.isDue())
            day = day.plusDays(1)
            assertTrue(daily.isDue())
        }
}
