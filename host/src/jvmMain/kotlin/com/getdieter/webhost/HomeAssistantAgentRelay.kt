package com.getdieter.webhost

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket as Upstream
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Home Assistant for the agent turns that may use it: the agents' `hass-cli` and `ha-logs` point here
 * (HASS_SERVER) and sign in with their turn's token (DIETER_TURN_TOKEN) instead of the add-on's. A conversation
 * created with Home Assistant access ([access] asks the daemon through the operator) gets core's REST and WebSocket
 * API through the Supervisor's proxy, signed in with the add-on's token, which never reaches the agent; any other
 * caller gets 403, or `auth_invalid` on the WebSocket. Answers are cached for [CACHE_MS]; a token stops working with
 * its turn, and an open WebSocket is asked about again every [recheck] and closed once its turn is gone (a process the
 * turn left running keeps nothing). Loopback only (127.0.0.1:8098). Everything an agent with access sends passes: Home Assistant cannot narrow
 * the add-on's token, so such a conversation can do what Home Assistant's own administrator can.
 */
class HomeAssistantAgentRelay(
    private val token: String,
    private val address: InetSocketAddress,
    private val access: (String) -> Access,
    private val target: URI = URI("http://supervisor/core"),
    private val recheck: Duration = Duration.ofSeconds(30),
) {
    /** The daemon's answer for a turn token: whether it may pass, its card, and why not. */
    data class Access(val allowed: Boolean, val card: String = "", val reason: String = "")

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, Access>>()
    private var server: ServerSocket? = null

    /** Binds and accepts on a background thread; returns the bound port (port 0 picks a free one). */
    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(address, 64)
        server = socket
        Log.line("Home Assistant relay for agents with access on ${address.hostString}:${socket.localPort}")
        Thread.ofPlatform().name("ha-agent-relay").daemon(true).start {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread.ofVirtual().name("ha-agent-${client.port}").start { handle(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        server?.close()
    }

    private fun allowed(turnToken: String, fresh: Boolean = false): Access {
        val now = System.currentTimeMillis()
        if (!fresh) cache[turnToken]?.let { (at, answer) -> if (now - at < CACHE_MS) return answer }
        val answer = runCatching { access(turnToken) }.getOrElse { Access(false, reason = "the operator did not answer: ${it.message}") }
        if (cache.size > 1000) cache.clear()
        cache[turnToken] = now to answer
        return answer
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = socket.getOutputStream()
            val head = runCatching { readHead(input) }.getOrNull() ?: return
            val (method, target, headers) = head
            try {
                if (!target.startsWith("/api/") || ".." in target || '%' in target.substringBefore('?'))
                    return reply(output, 404, message("not found"))
                if (headers["upgrade"]?.lowercase() == "websocket") return websocket(socket, input, output, target, headers)
                val bearer = headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
                val access = allowed(bearer)
                if (!access.allowed) {
                    Log.line("agent relay refused $method ${target.substringBefore('?')}: ${access.reason.ifEmpty { "conversation ${access.card} has no Home Assistant access" }}")
                    return reply(output, 403, message(DENIED))
                }
                if (headers["transfer-encoding"] != null) return reply(output, 411, message("send a Content-Length"))
                val length = headers["content-length"]?.toLongOrNull() ?: 0
                if (length !in 0..MAX_BODY) return reply(output, 413, message("request too large"))
                val body = input.readNBytes(length.toInt())
                val request = HttpRequest.newBuilder(URI(target.let { this.target.toString().trimEnd('/') + it })).timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer $token")
                    .apply { headers["content-type"]?.let { header("Content-Type", it) } }
                    .method(method, if (body.isEmpty() && method in setOf("GET", "HEAD", "DELETE")) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                Log.line("agent relay ${access.card} $method ${target.substringBefore('?')} ${response.statusCode()}")
                reply(output, response.statusCode(), response.body(), response.headers().firstValue("content-type").orElse("application/json"))
            } catch (_: java.io.IOException) {   // the caller left, or Home Assistant cannot be reached
                runCatching { reply(output, 502, message("Home Assistant cannot be reached")) }
            } catch (e: Exception) {
                Log.line("agent relay error $method ${target.substringBefore('?')}: $e")
                runCatching { reply(output, 500, message("relay error")) }
            }
        }
    }

    /**
     * The WebSocket API: Home Assistant's `auth_required` is passed on, the agent's `auth` must carry a turn token with
     * access, and the relay signs in upstream with the add-on's token; from then on messages pass both ways.
     */
    private fun websocket(socket: Socket, input: InputStream, output: OutputStream, target: String, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return reply(output, 400, message("WebSocket upgrade expected"))
        val fromHa = LinkedBlockingQueue<String>()
        val closedMark = "\u0000closed"
        val upstream = runCatching {
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI(this.target.toString().trimEnd('/').replaceFirst("http", "ws") + target), object : Upstream.Listener {
                    private val text = StringBuilder()

                    override fun onText(ws: Upstream, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        text.append(data)
                        if (last) {
                            fromHa.put(text.toString())
                            text.setLength(0)
                        }
                        ws.request(1)
                        return null
                    }

                    override fun onClose(ws: Upstream, status: Int, reason: String): CompletionStage<*>? {
                        fromHa.put(closedMark)
                        return null
                    }

                    override fun onError(ws: Upstream, error: Throwable) {
                        fromHa.put(closedMark)
                    }
                }).get(15, TimeUnit.SECONDS)
        }.getOrElse { return reply(output, 502, message("Home Assistant cannot be reached")) }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray()))
        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        socket.soTimeout = 0
        val agent = WebSocket(input, output, headers)
        fun next(): String? = fromHa.poll(15, TimeUnit.SECONDS)?.takeIf { it != closedMark }
        val ended = AtomicBoolean()
        try {
            agent.sendText((next() ?: return).toByteArray())                                    // auth_required
            val auth = agent.receive()?.let { runCatching { Json.parseToJsonElement(it.decodeToString()) as JsonObject }.getOrNull() } ?: return
            val turnToken = (auth["access_token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val access = if ((auth["type"] as? JsonPrimitive)?.contentOrNull == "auth") allowed(turnToken) else Access(false, reason = "no auth message")
            if (!access.allowed) {
                Log.line("agent relay refused the WebSocket: ${access.reason.ifEmpty { "conversation ${access.card} has no Home Assistant access" }}")
                agent.sendText(buildJsonObject { put("type", "auth_invalid"); put("message", DENIED) }.toString().toByteArray())
                return
            }
            upstream.sendText(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString(), true).join()
            Log.line("agent relay ${access.card} WebSocket")
            // the sign-in was checked once; the turn may end while the socket stays open, so ask again (past the cache)
            Thread.ofVirtual().start {
                while (!ended.get()) {
                    Thread.sleep(recheck.toMillis())
                    if (ended.get()) break
                    val now = allowed(turnToken, fresh = true)
                    if (!now.allowed) {
                        Log.line("agent relay ${access.card} WebSocket closed: ${now.reason.ifEmpty { "no Home Assistant access" }}")
                        agent.close()
                        upstream.abort()
                        runCatching { socket.close() }
                        break
                    }
                }
            }
            Thread.ofVirtual().start {
                while (true) {
                    val message = fromHa.take()
                    if (message == closedMark) break
                    agent.sendText(message.toByteArray())
                }
                agent.close()
            }
            while (true) {
                val message = agent.receive() ?: break
                upstream.sendText(message.decodeToString(), true).join()
            }
        } finally {
            ended.set(true)
            runCatching { upstream.sendClose(Upstream.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS) }
            upstream.abort()
            agent.close()
        }
    }

    private fun reply(output: OutputStream, status: Int, body: ByteArray, type: String = "application/json") {
        output.write("HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun message(text: String) = buildJsonObject { put("message", text) }.toString().toByteArray()

    private fun readHead(input: InputStream): Triple<String, String, Map<String, String>>? {
        val first = line(input)?.split(' ') ?: return null
        if (first.size < 3 || !first[1].startsWith("/")) return null
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val h = line(input) ?: return null
            if (h.isEmpty()) break
            total += h.length
            if (total > 32 * 1024) return null
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Triple(first[0], first[1], headers)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
            if (out.size() > 16 * 1024) return null
            out.write(b)
        }
    }

    companion object {
        /** Asks the operator (`POST <operator>api/turns/caller`), which asks the daemon. */
        fun operator(url: String): (String) -> Access {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val endpoint = URI(url.trimEnd('/') + "/api/turns/caller")
            return { turnToken ->
                val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("token", turnToken) }.toString())).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                val answer = runCatching { Json.parseToJsonElement(response.body()) as JsonObject }.getOrNull()
                fun text(key: String) = (answer?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    response.statusCode() != 200 || answer == null -> Access(false, reason = "operator HTTP ${response.statusCode()}")
                    else -> Access(text("home_assistant_access") == "true", text("card_id"), text("reason"))
                }
            }
        }

        const val CACHE_MS = 10_000L
        const val MAX_BODY = 16L * 1024 * 1024
        const val DENIED = "This conversation was created without Home Assistant access: create the task or schedule with " +
            "\"Allow Home Assistant access\" (or turn on the add-on's option \"Home Assistant for every task\")."
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val REASONS = mapOf(200 to "OK", 201 to "Created", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 411 to "Length Required", 413 to "Payload Too Large", 500 to "Internal Server Error",
            502 to "Bad Gateway")
    }
}
