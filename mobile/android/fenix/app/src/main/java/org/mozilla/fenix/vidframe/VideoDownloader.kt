/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.net.Uri
import org.mozilla.fenix.BuildConfig

/**
 * Replaces the site's "Download" (a subscription feature on the mobile site) with a third-party download service,
 * savefrom.net by default. The page script (vidframe-ui/player.js) turns a tap on "Download" into a navigation to
 * `vidframe-download://open?u=<video url>`; [VidframeRequestInterceptor] then opens the service for that video in
 * another browser on the device. The service is never loaded inside this client, so the navigation allowlist stays
 * as it is and the service's own pages and ads stay out of it.
 *
 * Build with `-Pvidframe.downloader=` (empty) to keep the site's own button, or point it at another service that takes
 * the video URL appended to a prefix.
 */
object VideoDownloader {
    const val SCHEME = "vidframe-download"

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    val isEnabled: Boolean
        get() = BuildConfig.VIDFRAME_DOWNLOADER_URL.isNotEmpty()

    /** The 11-character video id of a watch, Shorts or youtu.be URL, or null for anything else. */
    fun videoId(url: String?): String? {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.pathSegments
        val id =
            when {
                host == "youtu.be" -> segments.firstOrNull()
                host !in YOUTUBE_HOSTS -> null
                segments.firstOrNull() == "watch" -> uri.getQueryParameter("v")
                segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed") -> segments[1]
                else -> null
            }
        return id?.takeIf { VIDEO_ID.matches(it) }
    }

    /** Download-service page for [videoId]; the video URL is rebuilt from the id, never passed through. */
    fun serviceUrl(videoId: String, prefix: String = BuildConfig.VIDFRAME_DOWNLOADER_URL): String =
        prefix + Uri.encode("https://www.youtube.com/watch?v=$videoId")
}
