/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import org.mozilla.fenix.BuildConfig
import kotlin.math.roundToInt

/**
 * Fixed page scale of the site: the fraction of the system density handed to Gecko (see [densityOverride]). It is the
 * same on every device, [DEFAULT] (80 %) unless the build sets `-Pvidframe.contentScale=0.5..1.0`.
 */
object PageScale {
    const val DEFAULT = 0.8f
    private const val MIN = 0.5f
    private const val MAX = 1f
    private const val PERCENT = 100

    /** A scale from a build value such as "0.8", clamped to 50-100 %, or null for anything unparsable. */
    fun parse(value: String?): Float? = value?.toFloatOrNull()?.coerceIn(MIN, MAX)

    val scale: Float
        get() = parse(BuildConfig.VIDFRAME_CONTENT_SCALE) ?: DEFAULT

    fun percent(scale: Float): Int = (scale * PERCENT).roundToInt()

    /**
     * Density handed to Gecko instead of the system one, or null to keep the system density. A smaller density means a
     * smaller CSS pixel, so the whole page (menus, player controls, fullscreen) is laid out as on a larger screen.
     */
    fun densityOverride(context: Context, scale: Float = this.scale): Float? =
        if (scale < MAX) context.resources.displayMetrics.density * scale else null
}
