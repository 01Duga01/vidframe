/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

/**
 * TCP-level DPI desync for the first TLS record of a connection, the technique of zapret's tpws and ByeDPI:
 * the ClientHello is cut inside the server name (SNI) into two TLS records, and those bytes are written as separate
 * TCP segments. Servers reassemble handshake messages across records and segments (RFC 8446 section 5.1), while
 * stateless middleboxes that match the SNI in a single packet or record no longer see it in one piece.
 *
 * Pure JVM code without Android dependencies.
 */
object TlsDesync {
    private const val RECORD_HEADER = 5
    private const val CONTENT_TYPE_HANDSHAKE = 0x16
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val EXTENSION_SERVER_NAME = 0x0000
    private const val NAME_TYPE_HOST = 0x00

    /** Largest TLS plaintext record plus slack for the encrypted forms. */
    const val MAX_RECORD_PAYLOAD = 16384 + 2048

    /**
     * @property splitRecord cut the ClientHello into two TLS records one byte into the host name.
     * @property splitSegments write the bytes as separate TCP segments: after the first byte and one byte into the host
     *   name.
     */
    data class Strategy(val splitRecord: Boolean = true, val splitSegments: Boolean = true)

    fun isTlsHandshakeHeader(header: ByteArray): Boolean =
        header.size >= RECORD_HEADER && header.u8(0) == CONTENT_TYPE_HANDSHAKE && header.u8(1) == 0x03

    fun recordPayloadLength(header: ByteArray): Int = header.u16(3)

    /**
     * Returns the offset of the first byte of the SNI host name in [record] (a complete TLS record including its
     * 5-byte header), or -1 if [record] is not a ClientHello with a host name.
     */
    fun sniHostOffset(record: ByteArray): Int =
        try {
            parseSniHostOffset(record)
        } catch (e: IndexOutOfBoundsException) {
            -1
        }

    private fun parseSniHostOffset(r: ByteArray): Int {
        if (!isTlsHandshakeHeader(r) || RECORD_HEADER + recordPayloadLength(r) > r.size) return -1
        var p = RECORD_HEADER
        if (r.u8(p) != HANDSHAKE_CLIENT_HELLO) return -1
        p += 4 // handshake type + 24-bit length
        p += 2 + 32 // legacy_version + random
        p += 1 + r.u8(p) // legacy_session_id
        p += 2 + r.u16(p) // cipher_suites
        p += 1 + r.u8(p) // legacy_compression_methods
        val extensionsEnd = p + 2 + r.u16(p)
        p += 2
        while (p + 4 <= extensionsEnd) {
            val type = r.u16(p)
            val length = r.u16(p + 2)
            val data = p + 4
            if (type == EXTENSION_SERVER_NAME) {
                // server_name_list length (2), name_type (1), host_name length (2), host_name
                if (r.u8(data + 2) != NAME_TYPE_HOST || r.u16(data + 3) == 0) return -1
                val host = data + 5
                return if (host < r.size) host else -1
            }
            p = data + length
        }
        return -1
    }

    /**
     * Returns the byte chunks to write, in order, for the first TLS record of a connection. Each chunk is meant to be
     * written and flushed on its own (with TCP_NODELAY) so it leaves as a separate segment. Non-ClientHello records
     * are returned unchanged as a single chunk.
     */
    fun desync(record: ByteArray, strategy: Strategy = Strategy()): List<ByteArray> {
        val host = sniHostOffset(record)
        if (host < 0) return listOf(record)

        val stream = if (strategy.splitRecord) splitRecord(record, host + 1) else record
        if (!strategy.splitSegments) return listOf(stream)

        // With a record split, host + 1 is exactly the end of the first record.
        val cuts = listOf(1, host + 1).filter { it in 1 until stream.size }.distinct().sorted()
        val chunks = ArrayList<ByteArray>(cuts.size + 1)
        var start = 0
        for (cut in cuts) {
            chunks += stream.copyOfRange(start, cut)
            start = cut
        }
        chunks += stream.copyOfRange(start, stream.size)
        return chunks
    }

    /** Splits [record] into two records of the same type and version; the first one ends before [at]. */
    internal fun splitRecord(record: ByteArray, at: Int): ByteArray {
        val payload = record.copyOfRange(RECORD_HEADER, record.size)
        val first = at - RECORD_HEADER
        if (first <= 0 || first >= payload.size) return record
        return header(record, first) + payload.copyOfRange(0, first) +
            header(record, payload.size - first) + payload.copyOfRange(first, payload.size)
    }

    private fun header(record: ByteArray, length: Int) =
        byteArrayOf(record[0], record[1], record[2], (length ushr 8).toByte(), length.toByte())

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
}
