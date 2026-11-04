/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import org.mozilla.fenix.BuildConfig

/**
 * Page scale of the site, 50-100 %: the fraction of the system density handed to Gecko (see [densityOverride]).
 *
 * "auto" adapts to the device: the mobile site lays out comfortably at about [REFERENCE_WIDTH_DP] CSS pixels, so the
 * scale is the screen's smallest width in dp divided by that, and divided again by the system font scale because
 * Gecko follows the system font size. Changing Android's display size or font size, or using a tablet, therefore
 * changes the result. The user can also pick a fixed value in Settings (stored under [PREF_KEY]).
 */
object PageScale {
    const val PREF_KEY = "pref_key_vidframe_content_scale"
    const val AUTO = "auto"

    @VisibleForTesting internal const val REFERENCE_WIDTH_DP = 560f
    private const val MIN = 0.5f
    private const val MAX = 1f
    private const val STEP = 0.05f
    private const val PERCENT = 100

    /**
     * Automatic scale for a screen of [smallestWidthDp] with the system [fontScale], rounded to 5 %. Examples at font
     * scale 1.0: 360 dp -> 0.65, 393 dp -> 0.70, 411 dp -> 0.75, 600 dp and wider -> 1.0.
     */
    fun adaptive(smallestWidthDp: Int, fontScale: Float): Float {
        val raw = smallestWidthDp / (REFERENCE_WIDTH_DP * fontScale.coerceAtLeast(MIN))
        return ((raw / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)
    }

    /** A fixed scale from a stored value such as "0.7", or null for [AUTO] and anything unparsable. */
    fun parse(value: String?): Float? = value?.toFloatOrNull()?.coerceIn(MIN, MAX)

    /** The stored choice, falling back to the build default (`-Pvidframe.contentScale`, "auto" unless set). */
    fun storedValue(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null)
            ?: BuildConfig.VIDFRAME_CONTENT_SCALE

    fun current(context: Context, value: String = storedValue(context)): Float {
        val configuration = context.resources.configuration
        return parse(value) ?: adaptive(configuration.smallestScreenWidthDp, configuration.fontScale)
    }

    fun percent(scale: Float): Int = (scale * PERCENT).roundToInt()

    /**
     * Density handed to Gecko instead of the system one, or null to keep the system density. A smaller density means a
     * smaller CSS pixel, so the whole page (menus, player controls, fullscreen) is laid out as on a larger screen.
     */
    fun densityOverride(context: Context, scale: Float = current(context)): Float? =
        if (scale < MAX) context.resources.displayMetrics.density * scale else null

    /**
     * Gecko reads the density once, when the runtime starts, so a new scale needs a fresh process. Restarts the app
     * into the launcher (and with it the pinned start page).
     */
    fun restartApp(context: Context) {
        val component = ComponentName(context, VidframeLauncherActivity::class.java)
        context.startActivity(Intent.makeRestartActivityTask(component))
        exitProcess(0)
    }
}
