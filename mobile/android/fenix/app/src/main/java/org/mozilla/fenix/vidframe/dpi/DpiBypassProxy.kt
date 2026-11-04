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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 proxy (CONNECT only, no authentication) bound to 127.0.0.1 that applies [TlsDesync] to the first
 * record of every TLS connection and relays everything else unchanged. Gecko is pointed at it, so the bypass works
 * inside the app without root and without a VPN. Gecko resolves names itself (over DoH) and passes IP addresses.
 *
 * Resource use is bounded because the video site opens and abandons many connections (Shorts preloading): each
 * connection uses two small-stack threads and 16 KiB buffers, a connection is torn down completely as soon as either
 * side ends it, at most [maxConnections] are relayed at once, and no exception can escape a worker thread (on
 * Android an uncaught exception in any thread kills the whole app).
 *
 * Pure JVM code without Android dependencies.
 */
class DpiBypassProxy(
    private val strategy: TlsDesync.Strategy = TlsDesync.Strategy(),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val desyncPorts: Set<Int> = setOf(HTTPS_PORT),
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val threadCount = AtomicInteger()
    private val activeConnections = AtomicInteger()
    private val pool: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(null, runnable, "vidframe-dpi-${threadCount.incrementAndGet()}", THREAD_STACK_BYTES).apply {
                isDaemon = true
            }
        }

    /** Connections currently being relayed. */
    val connectionCount: Int
        get() = activeConnections.get()

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
            if (activeConnections.incrementAndGet() > maxConnections) {
                activeConnections.decrementAndGet()
                log("connection limit reached, refusing", null)
                runCatching { client.close() }
                continue
            }
            val submitted = runCatching { pool.execute { handle(client) } }.isSuccess
            if (!submitted) {
                activeConnections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val connection = Connection(client)
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream().buffered(BUFFER))
            val output = client.getOutputStream()
            val target = readConnectRequest(input, output)
            if (target == null) {
                connection.close()
                return
            }

            val upstream =
                Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                }
            connection.upstream = upstream
            try {
                upstream.connect(target, connectTimeoutMs)
            } catch (e: IOException) {
                runCatching { output.write(REPLY_HOST_UNREACHABLE) }
                connection.close()
                return
            }
            output.write(REPLY_SUCCEEDED)
            output.flush()

            pool.execute {
                runCatching { pump(upstream.getInputStream(), output) }
                connection.close()
            }
            if (target.port in desyncPorts) {
                sendFirstRecord(input, upstream.getOutputStream())
            }
            pump(input, upstream.getOutputStream())
        } catch (e: Exception) {
            // Includes RejectedExecutionException after close() and anything unexpected: never let it escape.
            if (e !is IOException) log("connection failed", e)
        } finally {
            connection.close()
        }
    }

    /** Both sockets of one relayed connection; closing is idempotent and releases the connection slot once. */
    private inner class Connection(private val client: Socket) {
        @Volatile var upstream: Socket? = null
        private val closed = AtomicBoolean(false)

        fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { client.close() }
                runCatching { upstream?.close() }
                activeConnections.decrementAndGet()
            }
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

    /** Copies until end of stream or error; the caller then closes the whole connection. */
    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) return
                to.write(buffer, 0, read)
            }
        } catch (e: Exception) {
            // Connection reset, or closed because the other direction finished.
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 128
        private const val BUFFER = 16 * 1024
        private const val THREAD_STACK_BYTES = 256L * 1024
        private const val MAX_CONNECTIONS = 384
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
