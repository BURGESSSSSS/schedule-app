package com.burgess.schedule.school.sync.debug

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** One connection only; the caller can request exactly GET /json/list. */
internal class DevtoolsBridge(private val socketName: String, private val token: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val lock = Any()
    private var client: Socket? = null
    private var upstream: LocalSocket? = null
    val port: Int = server.localPort

    init {
        server.soTimeout = 4_000
        Thread(::serveOnce, "school-cdp-one-shot").apply { isDaemon = true }.start()
    }

    private fun serveOnce() {
        try {
            server.accept().use { accepted ->
                synchronized(lock) { client = accepted }
                accepted.soTimeout = 4_000
                val input = accepted.getInputStream()
                var remainingHeaderBytes = 4_096
                val request = readLine(input, remainingHeaderBytes) ?: return
                remainingHeaderBytes -= request.length + 2
                var providedToken: String? = null
                while (true) {
                    val header = readLine(input, remainingHeaderBytes) ?: return
                    remainingHeaderBytes -= header.length + 2
                    if (header.isEmpty()) break
                    if (header.startsWith("X-Probe-Token:", ignoreCase = true)) {
                        providedToken = header.substringAfter(':').trim()
                    }
                }
                if (request != "GET /json/list HTTP/1.1" || providedToken != token) {
                    accepted.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    return
                }
                LocalSocket().use { unix ->
                    synchronized(lock) { upstream = unix }
                    unix.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    unix.soTimeout = 4_000
                    unix.outputStream.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    val bytes = ByteArray(8_192)
                    var remaining = 512 * 1_024
                    while (remaining > 0) {
                        val read = unix.inputStream.read(bytes, 0, minOf(bytes.size, remaining))
                        if (read < 0) break
                        accepted.getOutputStream().write(bytes, 0, read)
                        remaining -= read
                    }
                    accepted.getOutputStream().flush()
                }
            }
        } catch (_: Exception) {
            // The caller reports a typed bridge failure; no raw response or URL enters Logcat.
        } finally {
            close()
        }
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        if (limit <= 0) return null
        val bytes = ArrayList<Byte>(minOf(limit, 128))
        while (bytes.size < limit) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            bytes += value.toByte()
        }
        return null
    }

    override fun close() {
        synchronized(lock) {
            try { upstream?.close() } catch (_: Exception) { }
            try { client?.close() } catch (_: Exception) { }
            try { server.close() } catch (_: Exception) { }
            upstream = null
            client = null
        }
    }
}
