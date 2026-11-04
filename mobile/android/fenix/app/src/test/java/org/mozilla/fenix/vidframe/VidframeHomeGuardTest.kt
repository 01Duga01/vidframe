/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Intent
import androidx.navigation.NavController
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VidframeHomeGuardTest {

    @Test
    fun `home screen, browser toolbar and tabs tray are blocked`() {
        assertEquals(
            setOf(R.id.homeFragment, R.id.browserFragment, R.id.tabManagementFragment),
            VidframeHomeGuard.blockedDestinations,
        )
    }

    @Test
    fun `GIVEN the home screen with its search bar would be shown THEN the pinned start page is opened instead`() {
        val activity = mockk<HomeActivity>(relaxed = true)
        every { activity.packageName } returns "io.github.vidframe.app"
        val started = slot<Intent>()
        every { activity.startActivity(capture(started)) } returns Unit

        VidframeHomeGuard.check(activity, R.id.homeFragment)

        assertEquals(VidframeLauncherActivity::class.java.name, started.captured.component?.className)
        assertTrue(started.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        verify { activity.finish() }
    }

    @Test
    fun `GIVEN the browser toolbar would be shown THEN the pinned start page is opened instead`() {
        val activity = mockk<HomeActivity>(relaxed = true)
        every { activity.packageName } returns "io.github.vidframe.app"

        VidframeHomeGuard.check(activity, R.id.browserFragment)

        verify { activity.startActivity(any()) }
        verify { activity.finish() }
    }

    @Test
    fun `GIVEN a settings screen THEN it stays open`() {
        val activity = mockk<HomeActivity>(relaxed = true)

        VidframeHomeGuard.check(activity, R.id.settingsFragment)

        verify(exactly = 0) { activity.startActivity(any()) }
        verify(exactly = 0) { activity.finish() }
    }

    @Test
    fun `GIVEN the activity is already finishing THEN it is not relaunched twice`() {
        val activity = mockk<HomeActivity>(relaxed = true)
        every { activity.isFinishing } returns true

        VidframeHomeGuard.check(activity, R.id.homeFragment)

        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `GIVEN the web-app activity THEN the guard is not installed`() {
        val activity = mockk<ExternalAppBrowserActivity>(relaxed = true)
        val navController = mockk<NavController>(relaxed = true)

        VidframeHomeGuard.install(activity, navController)

        verify(exactly = 0) { navController.addOnDestinationChangedListener(any()) }
    }
}
