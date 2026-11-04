/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 proxy (CONNECT only, no authentication) bound to 127.0.0.1 that applies [TlsDesync] to the first
 * record of every TLS connection and relays everything else unchanged. Gecko is pointed at it, so the bypass works
 * inside the app without root and without a VPN. Gecko resolves names itself (over DoH) and passes IP addresses.
 *
 * Pure JVM code without Android dependencies.
 */
class DpiBypassProxy(
    private val strategy: TlsDesync.Strategy = TlsDesync.Strategy(),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val desyncPorts: Set<Int> = setOf(HTTPS_PORT),
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val threadCount = AtomicInteger()
    private val pool: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "vidframe-dpi-${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }

    /** Port the proxy listens on, on [LOOPBACK]. */
    val port: Int
        get() = server.localPort

    fun start(): DpiBypassProxy {
        pool.execute(::acceptLoop)
        return this
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (e: IOException) {
                    if (!server.isClosed) log("accept failed", e)
                    return
                }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream().buffered(BUFFER))
            val output = client.getOutputStream()
            val target = readConnectRequest(input, output)
            if (target == null) {
                client.close()
                return
            }

            val connected =
                Socket().apply {
                    tcpNoDelay = true
                    connect(target, connectTimeoutMs)
                }
            upstream = connected
            output.write(REPLY_SUCCEEDED)
            output.flush()

            val pair = Pair(client, connected)
            val remaining = AtomicInteger(2)
            pool.execute {
                pump(connected.getInputStream(), output)
                finishDirection(pair, toUpstream = false, remaining)
            }
            if (target.port in desyncPorts) {
                sendFirstRecord(input, connected.getOutputStream())
            }
            pump(input, connected.getOutputStream())
            finishDirection(pair, toUpstream = true, remaining)
        } catch (e: IOException) {
            if (upstream == null) {
                runCatching { client.getOutputStream().write(REPLY_HOST_UNREACHABLE) }
            }
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** Performs the SOCKS5 greeting and reads a CONNECT request; replies with an error and returns null otherwise. */
    private fun readConnectRequest(input: DataInputStream, output: OutputStream): InetSocketAddress? {
        if (input.readUnsignedByte() != SOCKS_VERSION) return null
        input.readFully(ByteArray(input.readUnsignedByte()))
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH))
        output.flush()

        val version = input.readUnsignedByte()
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val addressType = input.readUnsignedByte()
        val address: InetAddress? =
            when (addressType) {
                ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
                ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
                else -> null
            }
        // Only used if Gecko is ever configured to send names; normally it resolves over DoH and sends addresses.
        val name =
            if (addressType == ATYP_DOMAIN) {
                String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII)
            } else {
                null
            }
        if (address == null && name == null) return reject(output, REPLY_ADDRESS_NOT_SUPPORTED)
        val port = input.readUnsignedShort()
        if (version != SOCKS_VERSION || command != CMD_CONNECT) return reject(output, REPLY_COMMAND_NOT_SUPPORTED)
        return if (address != null) InetSocketAddress(address, port) else InetSocketAddress(name, port)
    }

    private fun reject(output: OutputStream, reply: ByteArray): InetSocketAddress? {
        output.write(reply)
        output.flush()
        return null
    }

    /** Reads the client's first TLS record completely and writes it through [TlsDesync]. */
    private fun sendFirstRecord(input: DataInputStream, upstream: OutputStream) {
        val header = ByteArray(RECORD_HEADER_SIZE).also(input::readFully)
        val length = TlsDesync.recordPayloadLength(header)
        if (!TlsDesync.isTlsHandshakeHeader(header) || length > TlsDesync.MAX_RECORD_PAYLOAD) {
            upstream.write(header)
            return
        }
        val record = header + ByteArray(length).also(input::readFully)
        for (chunk in TlsDesync.desync(record, strategy)) {
            upstream.write(chunk)
            upstream.flush()
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) return
                to.write(buffer, 0, read)
            }
        } catch (e: IOException) {
            // Connection reset or closed by the other direction.
        }
    }

    /** Half-closes the finished direction; the sockets are closed once both directions are done. */
    private fun finishDirection(pair: Pair<Socket, Socket>, toUpstream: Boolean, remaining: AtomicInteger) {
        val (client, upstream) = pair
        runCatching { if (toUpstream) upstream.shutdownOutput() else client.shutdownOutput() }
        if (remaining.decrementAndGet() == 0) {
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 128
        private const val BUFFER = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HTTPS_PORT = 443
        private const val RECORD_HEADER_SIZE = 5

        private const val SOCKS_VERSION = 5
        private const val METHOD_NO_AUTH: Byte = 0
        private const val CMD_CONNECT = 1
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4

        private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

        private val REPLY_SUCCEEDED = reply(0)
        private val REPLY_HOST_UNREACHABLE = reply(4)
        private val REPLY_COMMAND_NOT_SUPPORTED = reply(7)
        private val REPLY_ADDRESS_NOT_SUPPORTED = reply(8)
    }
}
