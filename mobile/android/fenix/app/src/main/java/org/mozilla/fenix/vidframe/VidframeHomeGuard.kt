/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity

/**
 * Keeps the browser UI of [HomeActivity] unreachable. HomeActivity still hosts settings, history and the add-on
 * manager (opened from launcher shortcuts), but its home screen, search bar, browser toolbar and tabs tray are never
 * shown: as soon as one of them would become visible the activity finishes and the pinned start page is opened.
 *
 * Destinations reached while the activity is not resumed (HomeActivity adds the home screen to the back stack in
 * onCreate without rendering it) are checked again in onResume.
 */
object VidframeHomeGuard {
    @VisibleForTesting
    internal val blockedDestinations = setOf(R.id.homeFragment, R.id.browserFragment, R.id.tabManagementFragment)

    fun install(activity: HomeActivity, navController: NavController) {
        if (activity is ExternalAppBrowserActivity) {
            return
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                check(activity, destination.id)
            }
        }
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    navController.currentDestination?.id?.let { check(activity, it) }
                }
            },
        )
    }

    @VisibleForTesting
    internal fun check(activity: HomeActivity, destinationId: Int) {
        if (destinationId in blockedDestinations && !activity.isFinishing) {
            VidframeLauncherActivity.relaunch(activity)
        }
    }
}
