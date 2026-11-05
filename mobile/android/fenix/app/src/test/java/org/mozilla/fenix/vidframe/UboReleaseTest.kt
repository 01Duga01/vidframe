/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UboReleaseTest {
    private var server: HttpServer? = null

    @After
    fun teardown() {
        server?.stop(0)
    }

    private fun amoJson(guid: String = VidframeConfig.UBO_ID, hash: String = "sha256:ABCDEF") =
        """{"guid":"$guid","current_version":{"version":"1.63.2","file":{"url":"https://addons.mozilla.org/x.xpi",""" +
            """"hash":"$hash","size":4096}}}"""

    @Test
    fun `newer versions compare numerically, suffixes and missing parts are tolerated`() {
        assertTrue(UboRelease.compareVersions("1.63.2", "1.62.0") > 0)
        assertTrue(UboRelease.compareVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, UboRelease.compareVersions("1.62", "1.62.0"))
        assertEquals(0, UboRelease.compareVersions("1.62.0", "1.62.0b3"))
        assertTrue(UboRelease.compareVersions("1.62.0", "") > 0)
        assertTrue(UboRelease.compareVersions("1.61.9", "1.62.0") < 0)
    }

    @Test
    fun `the AMO response is parsed, other add-ons and non-SHA-256 hashes are rejected`() {
        val release = UboRelease.parse(amoJson())!!
        assertEquals("1.63.2", release.version)
        assertEquals("https://addons.mozilla.org/x.xpi", release.url)
        assertEquals("abcdef", release.sha256)
        assertEquals(4096L, release.size)

        assertNull(UboRelease.parse(amoJson(guid = "uBOLite@raymondhill.net")))
        assertNull(UboRelease.parse(amoJson(hash = "md5:00")))
        assertNull(UboRelease.parse("not json"))
    }

    private fun serve(body: ByteArray): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/ubo.xpi") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/ubo.xpi"
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a download with the published hash succeeds and reports progress up to 100 percent`() {
        val body = ByteArray(100_000) { (it % 251).toByte() }
        val release = UboRelease("1.63.2", serve(body), sha256(body), body.size.toLong())
        val target = File.createTempFile("ubo", ".xpi")
        val progress = mutableListOf<Float?>()

        assertTrue(release.download(target, 5000) { progress += it })

        assertEquals(body.size.toLong(), target.length())
        assertEquals(1f, progress.last()!!, 0.0001f)
        assertTrue(progress.filterNotNull().zipWithNext().all { (a, b) -> b >= a })
        target.delete()
    }

    @Test
    fun `a download whose hash does not match is rejected and deleted`() {
        val body = "tampered".toByteArray()
        val release = UboRelease("1.63.2", serve(body), sha256("original".toByteArray()), 0)
        val target = File.createTempFile("ubo", ".xpi")

        assertFalse(release.download(target, 5000) {})
        assertFalse(target.exists())
    }
}
