package com.getdieter.operator

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Who may use the operator beyond what agents need. The agents run as the operator's own user (dieter) on the same
 * loopback, so an address tells nothing; the kernel's socket table does: /proc/net/tcp names the user that opened
 * the caller's end of a loopback connection, which no other user can fake. Trusted are [uids]: the web host's user
 * (the page, i.e. the person) and root. Everyone else, the agents included, gets [agentRoute] only: questions are
 * registered and polled by the harness worker, never listed or answered by it (an answer would reach the other
 * turn as the person's), and device trust (gateway enroll/unenroll) stays the person's.
 */
class Callers(internal val uids: Set<Int>, private val tables: List<() -> String?> = TABLES) {
    /** Whether the connection from [remote] to [local] was opened by a trusted user. */
    fun trusted(remote: InetSocketAddress, local: InetSocketAddress): Boolean = owner(remote, local)?.let { it in uids } == true

    /** The uid that opened the established connection [remote] → [local], from the socket table; null when not found. */
    fun owner(remote: InetSocketAddress, local: InetSocketAddress): Int? {
        for (table in tables) {
            val text = table() ?: continue
            for (line in text.lineSequence().drop(1)) {
                val f = line.trim().split(Regex("\\s+"))
                if (f.size < 8 || f[3] != "01") continue   // ESTABLISHED
                if (endpoint(f[1]) == remote && endpoint(f[2]) == local) return f[7].toIntOrNull()
            }
        }
        return null
    }

    companion object {
        private val TABLES: List<() -> String?> = listOf("/proc/net/tcp", "/proc/net/tcp6").map { p -> { runCatching { File(p).readText() }.getOrNull() } }

        /** Callers from DIETER_OPERATOR_TRUSTED_UIDS (comma separated); unset: no check (outside the add-on). */
        fun fromEnv(spec: String?): Callers? =
            spec?.takeIf { it.isNotBlank() }?.let { s -> Callers(s.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()) }

        /** What the harness worker uses (DIETER_QUESTIONS_URL): register a question, poll its answer by id. */
        fun agentRoute(method: String, parts: List<String>): Boolean =
            parts.firstOrNull() == "questions" && (method == "POST" && parts.size == 1 || method == "GET" && parts.size == 2)

        /** "0100007F:1F90" (address words in host order, port big-endian hex) → 127.0.0.1:8080; IPv4-mapped IPv6 → IPv4. */
        internal fun endpoint(spec: String): InetSocketAddress? {
            val (hex, port) = spec.split(':').takeIf { it.size == 2 } ?: return null
            if (hex.length != 8 && hex.length != 32) return null
            val bytes = ByteBuffer.allocate(hex.length / 2)
            for (i in hex.indices step 8) bytes.put(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(hex.substring(i, i + 8).toLong(16).toInt()).array())
            var address = bytes.array()
            if (address.size == 16 && address.take(10).all { it == 0.toByte() } && address[10] == (-1).toByte() && address[11] == (-1).toByte())
                address = address.copyOfRange(12, 16)
            return runCatching { InetSocketAddress(InetAddress.getByAddress(address), port.toInt(16)) }.getOrNull()
        }
    }
}
