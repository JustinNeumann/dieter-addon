package com.getdieter.operator

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallersTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val me = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int

    /** 127.0.0.1 as the kernel prints it on this machine. */
    private val lo = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "0100007F" else "7F000001"

    @Test
    fun theSocketTableNamesWhoOpenedAConnection() {
        assertEquals(InetSocketAddress(loopback, 8096), Callers.endpoint("$lo:1FA0"))
        assertEquals(InetSocketAddress(loopback, 8096), Callers.endpoint(
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "0000000000000000FFFF00000100007F:1FA0" else "00000000000000000000FFFF7F000001:1FA0"))
        assertNull(Callers.endpoint("nonsense"))
        val table = """
            |  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
            |   0: $lo:1FA0 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1 0 100 0 0 10 0
            |   1: $lo:D2A4 $lo:1FA0 01 00000000:00000000 00:00000000 00000000  1001        0 2 1 0 20 4 30 10 -1
            |   2: $lo:1FA0 $lo:D2A4 01 00000000:00000000 00:00000000 00000000  1000        0 3 1 0 20 4 30 10 -1
            |   3: $lo:D2A5 $lo:1FA0 06 00000000:00000000 03:00000000 00000000     0        0 0 3 0
            |""".trimMargin()
        val callers = Callers(setOf(1001), listOf({ null }, { table }))
        val server = InetSocketAddress(loopback, 8096)
        assertEquals(1001, callers.owner(InetSocketAddress(loopback, 0xD2A4), server))   // the caller's end, not the server's
        assertTrue(callers.trusted(InetSocketAddress(loopback, 0xD2A4), server))
        assertNull(callers.owner(InetSocketAddress(loopback, 0xD2A5), server))   // TIME_WAIT is no caller
        assertFalse(callers.trusted(InetSocketAddress(loopback, 0xD2A6), server))
    }

    @Test
    fun agentsGetTheirQuestionsOnlyAndTrustedUsersEverything() {
        val questions = Questions { System.currentTimeMillis() }
        val noDieter = Dieter { _, _ -> throw IllegalStateException("not called") }
        fun serve(callers: Callers) = OperatorServer(
            InetSocketAddress("127.0.0.1", 0),
            routes(noDieter, Settings(kotlin.io.path.createTempFile().toFile(), OllamaEntity(null)) { "" },
                Projects(noDieter, kotlin.io.path.createTempDirectory().toFile(), { null }), questions = questions),
            callers,
        ).start()
        val http = HttpClient.newHttpClient()
        fun call(port: Int, method: String, path: String, body: String? = null): Int =
            http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$path"))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()
        val question = """{"id": "toolu_1", "session": "c_card1", "input": {"questions": [{"id": "q1", "question": "Which colour?", "options": [{"id": "red"}]}]}}"""
        val answer = """{"action": "answered", "answers": {"q1": {"optionIds": ["red"]}}}"""

        // this test's own user stands for an agent: the real socket table does not name a trusted user
        val agents = serve(Callers(setOf(me + 1)))
        try {
            assertEquals(200, call(agents.address.port, "POST", "questions", question))
            assertEquals(404, call(agents.address.port, "GET", "questions/toolu_1"))   // allowed; without its token, unknown
            assertEquals(403, call(agents.address.port, "GET", "questions"))
            assertEquals(403, call(agents.address.port, "POST", "questions/toolu_1/answer", answer))
            assertEquals(403, call(agents.address.port, "POST", "gateway/unenroll", "{}"))
            assertEquals(403, call(agents.address.port, "GET", "settings"))
        } finally {
            agents.stop(0)
        }
        // and now as the trusted user (the web host's)
        val page = serve(Callers(setOf(me)))
        try {
            assertEquals(200, call(page.address.port, "GET", "questions"))
            assertEquals(200, call(page.address.port, "POST", "questions/toolu_1/answer", answer))
            assertEquals(200, call(page.address.port, "GET", "settings"))
        } finally {
            page.stop(0)
        }
        assertEquals(setOf(0, 1234), Callers.fromEnv(" 0, 1234 ")?.uids)
        assertNull(Callers.fromEnv(""))
    }
}
