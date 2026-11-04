/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import mozilla.components.ExperimentalAndroidComponentsApi
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.preferences.Branch
import mozilla.components.support.base.log.logger.Logger

/**
 * Keeps video playback on hardware decoders.
 *
 * On Android Gecko exposes AV1 and H.264 to Media Source Extensions (VP9 MSE is off on mobile). The video site prefers
 * AV1 whenever it is advertised, and without an AV1 hardware decoder Gecko falls back to software decoding (dav1d),
 * which drops frames and drains the battery on most phones. When the device has no hardware AV1 decoder, AV1 is
 * turned off on the default pref branch, so the site serves H.264, which every Android device decodes in hardware.
 *
 * The decoder scan result is cached per OS build, because enumerating codecs costs tens of milliseconds.
 */
object VideoCodecTuning {
    private val logger = Logger("VidframeCodec")

    @VisibleForTesting internal const val AV1_PREF = "media.av1.enabled"

    @VisibleForTesting internal const val AV1_MIME = "video/av01"

    private const val PREFS_NAME = "vidframe"
    private const val KEY_FINGERPRINT = "codec_scan_fingerprint"
    private const val KEY_HW_AV1 = "codec_hw_av1"

    private val softwareCodecPrefixes = listOf("OMX.google.", "c2.android.", "c2.google.", "OMX.ffmpeg.", "c2.ffmpeg.")

    @OptIn(ExperimentalAndroidComponentsApi::class)
    fun apply(context: Context, engine: Engine) {
        val hardwareAv1 = hasHardwareAv1Decoder(context)
        logger.info("Hardware AV1 decoder: $hardwareAv1")
        if (hardwareAv1) {
            return
        }
        engine.setBrowserPref(
            pref = AV1_PREF,
            value = false,
            branch = Branch.DEFAULT,
            onSuccess = { logger.info("AV1 disabled; the site will use hardware H.264") },
            onError = { logger.error("Could not disable AV1", it) },
        )
    }

    private fun hasHardwareAv1Decoder(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FINGERPRINT, null) == Build.FINGERPRINT) {
            return prefs.getBoolean(KEY_HW_AV1, false)
        }
        val result =
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { isHardwareAv1Decoder(it) } }
                .getOrDefault(false)
        prefs.edit {
            putString(KEY_FINGERPRINT, Build.FINGERPRINT)
            putBoolean(KEY_HW_AV1, result)
        }
        return result
    }

    @VisibleForTesting
    internal fun isHardwareAv1Decoder(info: MediaCodecInfo): Boolean {
        if (info.isEncoder || info.supportedTypes.none { it.equals(AV1_MIME, ignoreCase = true) }) {
            return false
        }
        return isHardwareCodec(
            name = info.name,
            reportedHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null,
        )
    }

    /** [reportedHardware] is the platform's own answer (API 29+), or null on older releases. */
    @VisibleForTesting
    internal fun isHardwareCodec(name: String, reportedHardware: Boolean?): Boolean =
        reportedHardware ?: softwareCodecPrefixes.none { name.startsWith(it, ignoreCase = true) }
}
