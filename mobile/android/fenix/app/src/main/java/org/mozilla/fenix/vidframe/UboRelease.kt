/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * The current uBlock Origin release on addons.mozilla.org, used on first run to decide whether the XPI bundled in the
 * APK is still the newest one.
 *
 * @property version e.g. "1.62.0".
 * @property url AMO download URL of the signed XPI.
 * @property sha256 lowercase hex digest published by AMO.
 * @property size file size in bytes, or 0 if unknown.
 */
data class UboRelease(val version: String, val url: String, val sha256: String, val size: Long) {
    companion object {
        const val AMO_API = "https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/"
        private const val MAX_XPI_BYTES = 32L * 1024 * 1024
        private const val BUFFER = 16 * 1024

        /** Parses the AMO add-on detail response; null if it is not the expected add-on or lacks a SHA-256. */
        fun parse(json: String): UboRelease? =
            runCatching {
                    val addon = JSONObject(json)
                    if (addon.optString("guid") != VidframeConfig.UBO_ID) return null
                    val current = addon.getJSONObject("current_version")
                    val file = current.getJSONObject("file")
                    val hash = file.getString("hash")
                    if (!hash.startsWith("sha256:")) return null
                    UboRelease(
                        version = current.getString("version"),
                        url = file.getString("url"),
                        sha256 = hash.removePrefix("sha256:").lowercase(),
                        size = file.optLong("size", 0),
                    )
                }
                .getOrNull()

        /**
         * Compares dotted version strings numerically ("1.10.0" > "1.9.3"); non-numeric suffixes are ignored and
         * missing components count as 0. An empty or unknown version is lower than any real one.
         */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val left = parts(a)
            val right = parts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val cmp = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** Asks AMO for the current release; null when offline, slow or on any error. Blocking. */
        fun fetchLatest(timeoutMs: Int): UboRelease? =
            runCatching {
                    val connection = URL(AMO_API).openConnection() as HttpURLConnection
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.setRequestProperty("Accept", "application/json")
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                        parse(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        connection.disconnect()
                    }
                }
                .getOrNull()
    }

    /**
     * Downloads the XPI to [target] and verifies its SHA-256. Reports progress in 0..1 (or null when the size is
     * unknown) through [onProgress]. Returns false and deletes [target] on any failure. Blocking.
     *
     * Gecko additionally verifies the AMO signature when the file is installed.
     */
    fun download(target: File, timeoutMs: Int, onProgress: (Float?) -> Unit): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val ok =
            runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
                        val total = connection.contentLengthLong.takeIf { it > 0 } ?: size.takeIf { it > 0 }
                        var received = 0L
                        connection.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(BUFFER)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    received += read
                                    if (received > MAX_XPI_BYTES) throw IOException("XPI too large")
                                    digest.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                    onProgress(total?.let { (received.toFloat() / it).coerceAtMost(1f) })
                                }
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) } == sha256
                    } finally {
                        connection.disconnect()
                    }
                }
                .getOrDefault(false)
        if (!ok) target.delete()
        return ok
    }
}
