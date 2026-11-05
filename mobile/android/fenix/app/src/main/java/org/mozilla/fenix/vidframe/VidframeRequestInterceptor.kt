/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import mozilla.components.support.base.log.logger.Logger

/**
 * Restricts navigations to [NavigationAllowlist]. Returns `null` when the load may proceed untouched.
 *
 * App links are intentionally not consulted for allowed URLs so that YouTube links stay inside this client instead of
 * being handed to a native YouTube app.
 */
class VidframeRequestInterceptor(private val context: Context) {
    private val logger = Logger("VidframeNav")

    fun onLoadRequest(uri: String, hasUserGesture: Boolean, isSubframeRequest: Boolean): InterceptionResponse? {
        if (uri.startsWith("$EXTERNAL_SCHEME://")) {
            return handleExternalOpen(uri, hasUserGesture)
        }
        if (uri.startsWith("${VideoDownloader.SCHEME}://")) {
            return handleDownload(uri, hasUserGesture)
        }
        if (NavigationAllowlist.isAllowed(uri)) {
            return null
        }
        logger.info("Blocked navigation (subframe=$isSubframeRequest): $uri")
        if (isSubframeRequest) {
            return InterceptionResponse.Deny
        }
        return InterceptionResponse.Content(blockPage(uri, VidframeConfig.navMode))
    }

    private fun handleExternalOpen(uri: String, hasUserGesture: Boolean): InterceptionResponse {
        val target = Uri.parse(uri).getQueryParameter("u")
        val scheme = target?.let { Uri.parse(it).scheme }
        if (VidframeConfig.navMode != VidframeConfig.NavMode.WARN || !hasUserGesture ||
            (scheme != "https" && scheme != "http")
        ) {
            return InterceptionResponse.Deny
        }
        openInOtherBrowser(target)
        return InterceptionResponse.Deny
    }

    /** "Download" on the site: open the download service for the video in another browser (user gesture only). */
    private fun handleDownload(uri: String, hasUserGesture: Boolean): InterceptionResponse {
        val videoId = VideoDownloader.videoId(Uri.parse(uri).getQueryParameter("u"))
        if (!VideoDownloader.isEnabled || !hasUserGesture || videoId == null) {
            return InterceptionResponse.Deny
        }
        logger.info("Opening the download service for $videoId")
        openInOtherBrowser(VideoDownloader.serviceUrl(videoId))
        return InterceptionResponse.Deny
    }

    private fun openInOtherBrowser(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val chooser =
            Intent.createChooser(view, null).apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(
                        ComponentName(context, "org.mozilla.fenix.IntentReceiverActivity"),
                        ComponentName(context, VidframeLauncherActivity::class.java),
                    ),
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        runCatching { context.startActivity(chooser) }.onFailure { logger.error("No external browser", it) }
    }

    companion object {
        const val EXTERNAL_SCHEME = "vidframe-external"

        internal fun blockPage(uri: String, mode: VidframeConfig.NavMode): String {
            val shown = Html.escapeHtml(uri)
            val external =
                if (mode == VidframeConfig.NavMode.WARN) {
                    val encoded = Html.escapeHtml("$EXTERNAL_SCHEME://open?u=${Uri.encode(uri)}")
                    """<a class="b2" href="$encoded">Открыть во внешнем браузере</a>"""
                } else {
                    ""
                }
            val title = if (mode == VidframeConfig.NavMode.WARN) "Внешний сайт" else "Адрес недоступен"
            return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>
:root{color-scheme:light dark}body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:24px;max-width:560px}
code{word-break:break-all;opacity:.8}a{display:block;text-align:center;padding:12px;margin-top:12px;
border-radius:8px;text-decoration:none;font-weight:600}.b1{background:#c00;color:#fff}.b2{border:1px solid currentColor;color:inherit}
</style></head><body><h1>$title</h1>
<p>Этот клиент открывает только видеосервис и связанные с ним служебные адреса. Переход заблокирован:</p>
<p><code>$shown</code></p>
<a class="b1" href="${Html.escapeHtml(VidframeConfig.START_URL)}">Вернуться к видео</a>$external
</body></html>"""
        }
    }
}
