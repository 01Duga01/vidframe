/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds a minimal TLS 1.3 ClientHello record with an SNI extension (after a supported_versions extension). */
internal fun clientHello(host: String): ByteArray {
    fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    val name = host.toByteArray(Charsets.US_ASCII)
    val serverNameList = u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
    val extensions =
        u16(0x002b) + u16(3) + byteArrayOf(2) + u16(0x0304) + // supported_versions
            u16(0x0000) + u16(serverNameList.size) + serverNameList // server_name
    val body =
        u16(0x0303) + ByteArray(32) + byteArrayOf(32) + ByteArray(32) +
            u16(4) + u16(0x1301) + u16(0x1302) + byteArrayOf(1, 0) +
            u16(extensions.size) + extensions
    val handshake = byteArrayOf(1) + u24(body.size) + body
    return byteArrayOf(0x16) + u16(0x0301) + u16(handshake.size) + handshake
}

class TlsDesyncTest {
    private val host = "rr1---sn-abc.googlevideo.com"

    @Test
    fun `the SNI host name is located inside the ClientHello`() {
        val record = clientHello(host)
        val offset = TlsDesync.sniHostOffset(record)

        assertEquals(host, String(record, offset, host.length, Charsets.US_ASCII))
    }

    @Test
    fun `the ClientHello is cut into two records one byte into the host name and three segments`() {
        val record = clientHello(host)
        val offset = TlsDesync.sniHostOffset(record)

        val chunks = TlsDesync.desync(record)

        assertEquals(3, chunks.size)
        assertEquals(1, chunks[0].size)
        assertEquals(offset + 1, chunks[0].size + chunks[1].size)
        val stream = chunks.reduce { a, b -> a + b }
        val firstLength = ((stream[3].toInt() and 0xFF) shl 8) or (stream[4].toInt() and 0xFF)
        assertEquals(offset + 1 - 5, firstLength)
        assertEquals(host[0].code.toByte(), stream[offset])
        val second = stream.copyOfRange(5 + firstLength, stream.size)
        assertEquals(0x16.toByte(), second[0])
        assertEquals(second.size - 5, ((second[3].toInt() and 0xFF) shl 8) or (second[4].toInt() and 0xFF))
        assertArrayEquals(
            "payloads of both records concatenate to the original payload",
            record.copyOfRange(5, record.size),
            stream.copyOfRange(5, 5 + firstLength) + second.copyOfRange(5, second.size),
        )
    }

    @Test
    fun `strategies can be switched off individually`() {
        val record = clientHello(host)

        assertEquals(1, TlsDesync.desync(record, TlsDesync.Strategy(splitRecord = true, splitSegments = false)).size)
        val segmentsOnly = TlsDesync.desync(record, TlsDesync.Strategy(splitRecord = false, splitSegments = true))
        assertArrayEquals(record, segmentsOnly.reduce { a, b -> a + b })
        assertEquals(3, segmentsOnly.size)
    }

    @Test
    fun `non-TLS data and records without SNI are passed through unchanged`() {
        val http = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        assertArrayEquals(http, TlsDesync.desync(http).single())

        val truncated = clientHello(host).copyOfRange(0, 60)
        assertEquals(-1, TlsDesync.sniHostOffset(truncated))
        assertArrayEquals(truncated, TlsDesync.desync(truncated).single())
    }

    @Test
    fun `header helpers recognise a TLS handshake record`() {
        val record = clientHello(host)

        assertTrue(TlsDesync.isTlsHandshakeHeader(record))
        assertEquals(record.size - 5, TlsDesync.recordPayloadLength(record))
    }
}
