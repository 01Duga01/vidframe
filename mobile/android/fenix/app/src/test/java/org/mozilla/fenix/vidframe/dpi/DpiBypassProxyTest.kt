/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe.dpi

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DpiBypassProxyTest {
    private lateinit var upstream: ServerSocket
    private lateinit var proxy: DpiBypassProxy

    @Before
    fun setup() {
        upstream = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        proxy = DpiBypassProxy(desyncPorts = setOf(upstream.localPort)).start()
    }

    @After
    fun teardown() {
        proxy.close()
        upstream.close()
    }

    /** Opens a SOCKS5 CONNECT to the upstream test server through the proxy and returns the client socket. */
    private fun connectThroughProxy(): Socket {
        val socket = Socket(DpiBypassProxy.LOOPBACK, proxy.port)
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        out.write(byteArrayOf(5, 1, 0))
        assertArrayEquals(byteArrayOf(5, 0), ByteArray(2).also(input::readFully))
        val port = upstream.localPort
        out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port ushr 8).toByte(), port.toByte()))
        val reply = ByteArray(10).also(input::readFully)
        assertEquals("CONNECT succeeded", 0, reply[1].toInt())
        return socket
    }

    @Test
    fun `the first TLS record is desynced and the rest of the stream is relayed both ways`() {
        val hello = clientHello("i.ytimg.com")
        val tail = "application data".toByteArray()
        val received =
            CompletableFuture.supplyAsync {
                upstream.accept().use { server ->
                    val input = DataInputStream(server.getInputStream())
                    val bytes = ByteArray(hello.size + 5 + tail.size).also(input::readFully)
                    server.getOutputStream().write("pong".toByteArray())
                    bytes
                }
            }

        connectThroughProxy().use { client ->
            client.getOutputStream().write(hello + tail)
            val pong = ByteArray(4).also(DataInputStream(client.getInputStream())::readFully)
            assertEquals("pong", String(pong))
        }

        val expected = TlsDesync.desync(hello).reduce { a, b -> a + b } + tail
        assertArrayEquals(expected, received.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `non-TLS traffic on other ports is relayed unchanged`() {
        proxy.close()
        proxy = DpiBypassProxy(desyncPorts = emptySet()).start()
        val payload = clientHello("example.com")
        val received =
            CompletableFuture.supplyAsync {
                upstream.accept().use { server ->
                    val buffer = ByteArrayOutputStream()
                    server.getInputStream().copyTo(buffer)
                    buffer.toByteArray()
                }
            }

        connectThroughProxy().use { client ->
            client.getOutputStream().write(payload)
            client.shutdownOutput()
            client.getInputStream().read()
        }

        assertArrayEquals(payload, received.get(5, TimeUnit.SECONDS))
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `an abandoned connection is torn down although the server keeps its side open`() {
        val serverSide = CompletableFuture.supplyAsync { upstream.accept() }

        connectThroughProxy().use { client ->
            client.getOutputStream().write(clientHello("rr1---sn-abc.googlevideo.com"))
            assertTrue(waitUntil { proxy.connectionCount == 1 })
        }

        // The browser dropped the connection (Shorts preloading does this a lot); the server never closes. The proxy
        // must still release both sockets and its threads instead of waiting for the server.
        assertTrue("connection slot released", waitUntil { proxy.connectionCount == 0 })
        val held = serverSide.get(5, TimeUnit.SECONDS)
        held.soTimeout = 3000
        held.getInputStream().readBytes() // returns only once the proxy has closed its side
        held.close()
    }

    @Test
    fun `connections beyond the limit are refused instead of exhausting threads`() {
        proxy.close()
        proxy = DpiBypassProxy(desyncPorts = emptySet(), maxConnections = 1).start()
        val serverSide = CompletableFuture.supplyAsync { upstream.accept() }

        connectThroughProxy().use {
            assertTrue(waitUntil { proxy.connectionCount == 1 })
            Socket(DpiBypassProxy.LOOPBACK, proxy.port).use { extra ->
                extra.soTimeout = 3000
                assertEquals("refused connection is closed", -1, extra.getInputStream().read())
            }
        }
        serverSide.get(5, TimeUnit.SECONDS).close()
    }

    @Test
    fun `an unreachable target gets a SOCKS error reply and frees the slot`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Socket(DpiBypassProxy.LOOPBACK, proxy.port).use { socket ->
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            input.readFully(ByteArray(2))
            out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (closedPort ushr 8).toByte(), closedPort.toByte()))
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(4, reply[1].toInt())
        }
        assertTrue(waitUntil { proxy.connectionCount == 0 })
    }

    @Test
    fun `unsupported SOCKS commands are rejected`() {
        Socket(DpiBypassProxy.LOOPBACK, proxy.port).use { socket ->
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            input.readFully(ByteArray(2))
            out.write(byteArrayOf(5, 2, 0, 1, 127, 0, 0, 1, 0, 80)) // BIND
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(7, reply[1].toInt())
        }
    }
}
