/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

import mozilla.components.concept.engine.preferences.Branch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiBypassTest {

    @Test
    fun `auto mode turns the bypass on for Russian networks, SIMs or regions only`() {
        assertTrue(DpiBypass.isEnabled("auto", listOf("ru", null, "US")))
        assertTrue(DpiBypass.isEnabled("auto", listOf("", "RU", "US")))
        assertTrue(DpiBypass.isEnabled("auto", listOf(null, null, "RU")))
        assertFalse(DpiBypass.isEnabled("auto", listOf("de", "de", "DE")))
        assertFalse(DpiBypass.isEnabled("auto", listOf(null, null, "")))
    }

    @Test
    fun `explicit modes override detection`() {
        assertTrue(DpiBypass.isEnabled("on", listOf("de")))
        assertFalse(DpiBypass.isEnabled("off", listOf("ru")))
    }

    @Test
    fun `Gecko resolves over DoH, uses the local SOCKS5 proxy and skips QUIC`() {
        val prefs = DpiBypass.geckoPrefs(port = 4242).associate { it.pref to it.value }

        assertEquals("127.0.0.1", prefs["network.proxy.socks"])
        assertEquals(4242, prefs["network.proxy.socks_port"])
        assertEquals(5, prefs["network.proxy.socks_version"])
        assertEquals(false, prefs["network.proxy.socks5_remote_dns"])
        assertEquals(false, prefs["network.http.http3.enable"])
        assertEquals(1, prefs["network.proxy.type"])
        assertTrue(DpiBypass.geckoPrefs(4242).all { it.branch == Branch.DEFAULT })
        assertEquals("network.proxy.type", DpiBypass.geckoPrefs(4242).last().pref)
    }
}
