package com.getdieter.webhost

import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket as Client
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeAssistantAgentRelayTest {
    private val seen = LinkedBlockingQueue<String>()
    private val asked = AtomicInteger()

    // Home Assistant core behind the Supervisor: REST, and a WebSocket that wants the add-on's token
    private val rest = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 10).apply {
        createContext("/core/api/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestBody.readAllBytes().decodeToString()}"
            val bytes = """[{"entity_id":"sun.sun"}]""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val ws = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")).also { server ->
        Thread.ofVirtual().start {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread.ofVirtual().start {
                    socket.use {
                        val input = BufferedInputStream(socket.getInputStream())
                        val head = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                        seen += "WS ${head.first()}"
                        val key = head.first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(':').trim()
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                        val peer = WebSocket(input, socket.getOutputStream(), emptyMap())
                        peer.sendText("""{"type":"auth_required","ha_version":"2026.10.4"}""".toByteArray())
                        val auth = peer.receive()?.decodeToString() ?: return@use
                        seen += "WS auth $auth"
                        if ("\"supervisor-t0ken\"" !in auth) return@use peer.sendText("""{"type":"auth_invalid"}""".toByteArray())
                        peer.sendText("""{"type":"auth_ok","ha_version":"2026.10.4"}""".toByteArray())
                        while (true) peer.sendText(("echo " + (peer.receive() ?: break).decodeToString()).toByteArray())
                    }
                }
            }
        }
    }
    private val access: (String) -> HomeAssistantAgentRelay.Access = { token ->
        asked.incrementAndGet()
        when (token) {
            "dtt1:c_ha:t1:mac" -> HomeAssistantAgentRelay.Access(true, "c_ha")
            "dtt1:c_plain:t1:mac" -> HomeAssistantAgentRelay.Access(false, "c_plain")
            else -> HomeAssistantAgentRelay.Access(false, reason = "not a turn token")
        }
    }

    // two relays to one fake Supervisor each: REST on its HTTP server, the WebSocket on the raw one
    private val restRelay = HomeAssistantAgentRelay("supervisor-t0ken", InetSocketAddress("127.0.0.1", 0), access, URI("http://127.0.0.1:${rest.address.port}/core"))
    private val relayPort = restRelay.start()
    private val wsRelay = HomeAssistantAgentRelay("supervisor-t0ken", InetSocketAddress("127.0.0.1", 0), access, URI("http://127.0.0.1:${ws.localPort}/core"))
    private val wsPort = wsRelay.start()
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        restRelay.stop()
        wsRelay.stop()
        rest.stop(0)
        ws.close()
    }

    private fun call(method: String, path: String, token: String?, body: String = ""): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$relayPort$path"))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .header("Content-Type", "application/json")
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())

    @Test
    fun onlyAConversationWithAccessReachesHomeAssistantsRestApi() {
        for (token in listOf(null, "dtt1:c_plain:t1:mac", "supervisor-t0ken"))
            call("GET", "/api/states", token).let { assertEquals(403, it.statusCode(), "token $token"); assertTrue("Allow Home Assistant access" in it.body()) }
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS), "nothing refused reaches Home Assistant")

        val states = call("GET", "/api/states", "dtt1:c_ha:t1:mac")
        assertEquals(200, states.statusCode())
        assertEquals("""[{"entity_id":"sun.sun"}]""", states.body())
        assertEquals("GET /core/api/states Bearer supervisor-t0ken ", seen.poll(5, TimeUnit.SECONDS), "signed in with the add-on's token, not the turn's")
        assertEquals(200, call("POST", "/api/services/light/turn_on", "dtt1:c_ha:t1:mac", """{"entity_id":"light.x"}""").statusCode())
        assertEquals("""POST /core/api/services/light/turn_on Bearer supervisor-t0ken {"entity_id":"light.x"}""", seen.poll(5, TimeUnit.SECONDS))
        assertEquals(404, call("GET", "/core/api/states", "dtt1:c_ha:t1:mac").statusCode(), "only /api/")
        assertEquals(1, asked.get() - 3, "the answer for a token is cached")
    }

    @Test
    fun theWebSocketPassesOnlyAfterTheTurnsAuth() {
        fun open(token: String): List<String> {
            val received = LinkedBlockingQueue<String>()
            val socket = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$wsPort/api/websocket"), object : Client.Listener {
                override fun onText(webSocket: Client, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    received += data.toString()
                    webSocket.request(1)
                    return null
                }
            }).get(10, TimeUnit.SECONDS)
            val out = mutableListOf(received.poll(10, TimeUnit.SECONDS)!!)
            socket.sendText("""{"type":"auth","access_token":"$token"}""", true).join()
            out += received.poll(10, TimeUnit.SECONDS)!!
            if ("auth_ok" in out.last()) {
                socket.sendText("""{"id":1,"type":"system_log/list"}""", true).join()
                out += received.poll(10, TimeUnit.SECONDS)!!
            }
            socket.abort()
            return out
        }
        val refused = open("dtt1:c_plain:t1:mac")
        assertTrue("auth_required" in refused[0] && "auth_invalid" in refused[1] && "Allow Home Assistant access" in refused[1], "$refused")
        val allowed = open("dtt1:c_ha:t1:mac")
        assertEquals(listOf("""{"type":"auth_required","ha_version":"2026.10.4"}""", """{"type":"auth_ok","ha_version":"2026.10.4"}""",
            """echo {"id":1,"type":"system_log/list"}"""), allowed)
        val auths = generateSequence { seen.poll(1, TimeUnit.SECONDS) }.filter { it.startsWith("WS auth") }.toList()
        assertEquals(listOf("""WS auth {"type":"auth","access_token":"supervisor-t0ken"}"""), auths, "upstream sees only the add-on's token, and only for the allowed turn")
    }

    private fun readLine(input: java.io.InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return out.toString().trimEnd('\r')
            out.append(b.toChar())
        }
    }
}
