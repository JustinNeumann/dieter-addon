package com.getdieter.webhost

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

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

    fun sendText(message: ByteArray) = frame(0x1, message)

    /** Whether the message [receive] returned last came as text (opcode 1) rather than binary. */
    var lastWasText = false
        private set

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
                        if (opcode != 0x0) lastWasText = opcode == 0x1
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
