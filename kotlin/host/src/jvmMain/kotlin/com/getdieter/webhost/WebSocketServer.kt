package com.getdieter.webhost

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * The smallest RFC 6455 server the web host needs: one endpoint (any path ending in `/core`),
 * binary messages, ping/pong and close. The bridge relays the browser's upgrade to it over
 * loopback and proves itself with the per-boot secret in `X-Dieter-Web-Secret`; agents in the same
 * container never learn that secret, so they cannot drive the account's session.
 */
internal class WebSocketServer(
    private val bind: String,
    private val port: Int,
    private val secret: String?,
    /** Plain GET requests other than /health and the socket (path with query, headers) → reply or null for 404. */
    private val onHttp: (String, Map<String, String>) -> HttpReply? = { _, _ -> null },
    private val onSocket: (WebSocket) -> Unit,
) {
    private var server: ServerSocket? = null

    /** Binds and accepts on a background thread; returns the bound port (port 0 picks a free one). */
    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName(bind), port), 50)
        server = socket
        Log.line("listening on $bind:${socket.localPort}")
        Thread.ofPlatform().name("ws-accept").daemon(false).start {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread.ofVirtual().name("ws-${client.port}").start { handle(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        server?.close()
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = socket.getOutputStream()
            val request = runCatching { readRequest(input) }.getOrNull() ?: return
            val (path, headers) = request
            fun reply(status: String, body: String = "") {
                val bytes = body.toByteArray()
                output.write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                )
                output.flush()
            }
            if (path.substringBefore('?').endsWith("/health")) return reply("200 OK", "ok")
            if (secret != null) {
                val given = headers["x-dieter-web-secret"].orEmpty().toByteArray()
                if (!MessageDigest.isEqual(given, secret.toByteArray())) return reply("403 Forbidden", "forbidden")
            }
            if (!path.substringBefore('?').endsWith("/core")) {
                val answer = runCatching { onHttp(path, headers) }.getOrElse { HttpReply(500, "text/plain", "failed: $it") }
                    ?: return reply("404 Not Found", "not found")
                val bytes = answer.body.toByteArray()
                output.write(
                    ("HTTP/1.1 ${answer.status} ${if (answer.status == 200) "OK" else "Error"}\r\n" +
                            "Content-Type: ${answer.type}\r\nContent-Length: ${bytes.size}\r\n" +
                            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                )
                output.flush()
                return
            }
            val key = headers["sec-websocket-key"]
            if (headers["upgrade"]?.lowercase() != "websocket" || key == null)
                return reply("400 Bad Request", "WebSocket upgrade expected")
            val accept =
                Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray()))
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
            )
            output.flush()
            val ws = WebSocket(input, output, headers)
            try {
                onSocket(ws)
            } catch (error: Throwable) {
                Log.line("connection failed: $error")
            } finally {
                ws.close()
            }
        }
    }

    /** Request line and headers (lower-case names); null for anything that is not a GET. */
    private fun readRequest(input: InputStream): Pair<String, Map<String, String>>? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (++total > 32 * 1024) return null
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.clear()
                if (text.isEmpty()) break
                lines += text
            } else line.append(c.toChar())
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2 || parts[0] != "GET") return null
        val headers =
            lines.drop(1).mapNotNull { h ->
                val i = h.indexOf(':')
                if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
            }.toMap()
        return parts[1] to headers
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

internal class HttpReply(val status: Int, val type: String, val body: String)

/** One open WebSocket. [send] is safe from any thread; [receive] belongs to the connection's thread. */
internal class WebSocket(
    private val input: InputStream,
    private val output: OutputStream,
    val headers: Map<String, String>,
) {
    private val lock = Any()
    @Volatile var closed = false
        private set

    fun send(message: ByteArray) = frame(0x2, message)

    /** The next complete text or binary message; null once the peer closed or the stream ended. */
    fun receive(): ByteArray? {
        val message = ByteArrayOutputStream()
        try {
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) return null
                val opcode = b0 and 0x0F
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = number(2)
                else if (length == 127L) length = number(8)
                if (length > MAX_MESSAGE || message.size() + length > MAX_MESSAGE) {
                    frame(0x8, byteArrayOf(0x03, 0xF1.toByte())) // 1009 message too big
                    return null
                }
                val mask = if (b1 and 0x80 != 0) bytes(4) else null
                val payload = bytes(length.toInt())
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x8 -> {
                        frame(0x8, payload.copyOf(minOf(2, payload.size)))
                        return null
                    }
                    0x9 -> frame(0xA, payload)
                    0xA -> {}
                    0x0, 0x1, 0x2 -> {
                        message.write(payload)
                        if (b0 and 0x80 != 0) return message.toByteArray()
                    }
                    else -> return null
                }
            }
        } catch (_: EOFException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        }
    }

    fun close() {
        if (closed) return
        runCatching { frame(0x8, byteArrayOf(0x03, 0xE8.toByte())) }
        closed = true
    }

    private fun frame(opcode: Int, payload: ByteArray) {
        synchronized(lock) {
            if (closed) return
            val n = payload.size
            val head = ByteArrayOutputStream(10)
            head.write(0x80 or opcode)
            when {
                n < 126 -> head.write(n)
                n < 65536 -> {
                    head.write(126)
                    head.write(n ushr 8)
                    head.write(n and 0xFF)
                }
                else -> {
                    head.write(127)
                    for (i in 7 downTo 0) head.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt())
                }
            }
            try {
                output.write(head.toByteArray())
                output.write(payload)
                output.flush()
            } catch (error: java.io.IOException) {
                closed = true
            }
        }
    }

    private fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) throw EOFException()
            read += r
        }
        return out
    }

    private fun number(n: Int): Long = bytes(n).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    companion object {
        const val MAX_MESSAGE = 64L * 1024 * 1024 // attachments travel inside commands
    }
}
