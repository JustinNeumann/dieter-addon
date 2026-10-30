package com.getdieter.webhost

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * The add-on's front door on port 8099, where Home Assistant's Ingress, Home Assistant itself and the add-on core
 * call (bridge/dieter_bridge.py until 0.6.431.23). It runs in the web host, user dieter-web, so the page's core is
 * served in this process and no secret has to reach the agents' user:
 *
 *   GET  /, /index.html         the add-on's root: forwards to next/, with deep links (webhost/root.html)
 *   GET  /next/…                the Compose page ([webDir]): hashed files immutable, the rest no-cache + ETag;
 *                               *.wasm.gz raw for the loader, .br/.gz by Accept-Encoding outside Ingress only
 *                               (Ingress drops that header and compresses at Home Assistant's border)
 *   GET  /next/core             the page's core (WebSocket), and /next/signin the gateway's return after sign-in
 *   GET  /api/ws/echo           WebSocket echo (checks that WebSockets pass Ingress)
 *   GET, POST /api/…            the operator's ([operator]): passed on with query, body and the Home Assistant
 *                               user, answered as it answers; an event stream as it comes
 *
 * Who may call (Home Assistant's add-on rule: allow the Supervisor, deny every other address):
 *   [ingress]    Home Assistant's Ingress (the Supervisor, 172.30.32.2), i.e. a person signed in to Home Assistant:
 *                everything above
 *   [core]       Home Assistant itself (172.30.32.1: its REST sensor, rest_commands and scripts), or any caller with
 *                the add-on's [apiKey] (`Authorization: Bearer …`, option api_key): Home Assistant's routes only
 *                ([homeAssistantRoute])
 *   anyone else  nothing: other add-ons (172.30.33.x) and the agents on loopback, which would drive the session or
 *                act as the person
 * One request per connection.
 */
internal class FrontDoor(
    private val bind: String,
    private val port: Int,
    private val webDir: File,
    private val operator: URI,
    private val ingress: (InetAddress) -> Boolean,
    private val core: (InetAddress) -> Boolean,
    private val apiKey: String,
    private val onSignIn: (query: String) -> HttpReply,
    private val onCore: (WebSocket) -> Unit,
) {
    private var server: ServerSocket? = null
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    private val root: ByteArray by lazy { FrontDoor::class.java.getResourceAsStream("/webhost/root.html")!!.use { it.readBytes() } }

    /** Binds and accepts on a background thread; returns the bound port (port 0 picks a free one). */
    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName(bind), port), 128)
        server = socket
        Log.line("listening on $bind:${socket.localPort}, operator $operator")
        Thread.ofPlatform().name("front-door").daemon(false).start {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread.ofVirtual().name("http-${client.port}").start { handle(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        server?.close()
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>) {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val parts = path.split('/').filter { it.isNotEmpty() }
    }

    private class Exchange(val socket: Socket, val input: InputStream, val output: OutputStream, val request: Request) {
        var status = 0

        fun head(status: Int, vararg headers: Pair<String, String>) {
            this.status = status
            val text = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\n")
            for ((k, v) in headers) text.append(k).append(": ").append(v).append("\r\n")
            output.write(text.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        }

        fun reply(status: Int, type: String, body: ByteArray, vararg headers: Pair<String, String>) {
            head(status, "Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-store", *headers, "Connection" to "close")
            output.write(body)
            output.flush()
        }

        fun json(status: Int, body: String) = reply(status, "application/json; charset=utf-8", body.toByteArray())

        fun text(status: Int, body: String) = reply(status, "text/plain; charset=utf-8", body.toByteArray())
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000   // the request head; an upgraded socket or a stream waits as long as it lasts
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = socket.getOutputStream()
            val request = runCatching { readHead(input) }.getOrNull() ?: return
            val ex = Exchange(socket, input, output, request)
            try {
                route(ex)
            } catch (_: java.io.IOException) {   // the caller left
            } catch (e: Exception) {
                Log.line("${request.method} error ${request.target} $e")
                runCatching { if (ex.status == 0) ex.json(500, obj("error" to e.toString())) }
            }
            if (ex.status !in setOf(200, 101, 304) || request.method == "POST")
                Log.line("${socket.inetAddress.hostAddress} ${request.method} ${request.target} ${ex.status}")
        }
    }

    private fun route(ex: Exchange) {
        val r = ex.request
        if (r.method != "GET" && r.method != "POST") return ex.text(501, "unsupported method ${r.method}")
        val parts = r.parts
        val person = ingress(ex.socket.inetAddress)
        if (!person && !((core(ex.socket.inetAddress) || keyed(r.headers["authorization"])) && homeAssistantRoute(r.method, parts)))
            return ex.json(403, obj("error" to "forbidden"))
        if (r.method == "POST") {
            if (parts.firstOrNull() != "api") return ex.json(404, obj("error" to "not found"))
            val body = try {
                readBody(ex)
            } catch (e: BodyTooLarge) {
                return ex.json(400, obj("error" to "file too large (20 MB at most)"))
            }
            return forward(ex, body, person)
        }
        when {
            parts.size == 2 && parts[0] == "next" && (parts[1] == "core" || parts[1] == "signin") -> {
                if (parts[1] == "signin") onSignIn(r.query).let { ex.reply(it.status, it.type, it.body.toByteArray(), "X-Content-Type-Options" to "nosniff") }
                else upgrade(ex)?.let { ws ->
                    try {
                        onCore(ws)
                    } catch (error: Throwable) {
                        Log.line("connection failed: $error")
                    } finally {
                        ws.close()
                    }
                }
            }
            parts.firstOrNull() == "next" -> serveWeb(ex)
            parts == listOf("api", "ws", "echo") -> upgrade(ex)?.let(::echo)
            parts.isEmpty() || parts == listOf("index.html") -> ex.reply(200, "text/html; charset=utf-8", root)
            parts[0] != "api" -> ex.json(404, obj("error" to "not found"))
            else -> forward(ex, null, person)
        }
    }

    /** A request carrying the add-on's API key (none configured: no request does). */
    private fun keyed(authorization: String?): Boolean {
        if (apiKey.isEmpty() || authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)) return false
        return java.security.MessageDigest.isEqual(authorization.substring(7).trim().toByteArray(), apiKey.toByteArray())
    }

    // ------------------------------------------------------------------ WebSockets

    /** The RFC 6455 handshake; the open socket, or null after a refusal. */
    private fun upgrade(ex: Exchange): WebSocket? {
        val key = ex.request.headers["sec-websocket-key"]
        if (ex.request.headers["upgrade"]?.lowercase() != "websocket" || key == null) {
            ex.json(400, obj("error" to "WebSocket upgrade expected"))
            return null
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray()))
        ex.head(101, "Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Accept" to accept)
        ex.output.flush()
        ex.socket.soTimeout = 0
        return WebSocket(ex.input, ex.output, ex.request.headers)
    }

    private fun echo(ws: WebSocket) {
        try {
            while (true) {
                val message = ws.receive() ?: break
                if (ws.lastWasText) ws.sendText(message) else ws.send(message)
            }
        } finally {
            ws.close()
        }
    }

    // ------------------------------------------------------------------ the page

    private fun serveWeb(ex: Exchange) {
        val r = ex.request
        if (r.path == "/next") {   // a relative Location keeps the Ingress prefix: …/<token>/next -> …/<token>/next/
            ex.head(302, "Location" to "next/" + (if (r.query.isNotEmpty()) "?${r.query}" else ""), "Content-Length" to "0", "Connection" to "close")
            return ex.output.flush()
        }
        val rel = r.parts.drop(1).joinToString("/").ifEmpty { "index.html" }
        var file = webFile(rel) ?: return ex.text(404, if (webDir.isDirectory) "not found" else "The Compose web UI is not part of this build.")
        val name = rel.substringAfterLast('/')
        val type = if (name.endsWith(".wasm.gz")) "application/octet-stream"   // inflated by the loader (DecompressionStream)
        else WEB_TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        var encoding: String? = null
        if ("x-ingress-path" !in r.headers && !name.endsWith(".gz") && !name.endsWith(".br")) {
            val accepted = acceptedEncodings(r.headers["accept-encoding"])
            for ((coding, suffix) in listOf("br" to ".br", "gzip" to ".gz")) {
                val packed = File(file.path + suffix)
                if (coding in accepted && packed.isFile) {
                    file = packed
                    encoding = coding
                    break
                }
            }
        }
        val etag = "\"${file.length().toString(16)}-${(file.lastModified() / 1000).toString(16)}${encoding?.let { "-$it" }.orEmpty()}\""
        val cache = if (WEB_HASHED.containsMatchIn(name)) "public, max-age=31536000, immutable" else "no-cache"
        if (r.headers["if-none-match"].orEmpty().split(',').any { it.trim() == etag }) {
            ex.head(304, "ETag" to etag, "Cache-Control" to cache, "Vary" to "Accept-Encoding", "Connection" to "close")
            return ex.output.flush()
        }
        val headers = mutableListOf(
            "Content-Type" to type, "Content-Length" to file.length().toString(), "Cache-Control" to cache, "ETag" to etag,
            "Vary" to "Accept-Encoding", "X-Content-Type-Options" to "nosniff",
        )
        encoding?.let { headers += "Content-Encoding" to it }
        ex.head(200, *headers.toTypedArray(), "Connection" to "close")
        file.inputStream().use { it.copyTo(ex.output, 256 * 1024) }
        ex.output.flush()
    }

    /** A file of the page's bundle, null if missing or outside [webDir]. */
    private fun webFile(rel: String): File? {
        val base = runCatching { webDir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(base, rel).canonicalFile }.getOrNull() ?: return null
        if (file != base && !file.path.startsWith(base.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    // ------------------------------------------------------------------ the operator

    /** Passes the request on to the operator and its answer back; an event stream as it comes, until either side leaves.
     * Home Assistant's user headers only come from Ingress ([person]); nobody else can claim to be someone. */
    private fun forward(ex: Exchange, body: ByteArray?, person: Boolean) {
        val r = ex.request
        val uri = runCatching { URI.create(operator.toString().trimEnd('/') + r.target) }.getOrElse { return ex.json(400, obj("error" to "bad path")) }
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(OPERATOR_TIMEOUT_S))
        if (body != null) {
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body))
            request.header("Content-Type", r.headers["content-type"] ?: "application/json")
        } else request.GET()
        for (h in FORWARDED) if (person || h == "X-Filename") r.headers[h.lowercase()]?.takeIf { it.isNotEmpty() }?.let { request.header(h, it) }
        val answer = try {
            http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.io.IOException) {
            Log.line("operator: ${r.method} ${r.path} $e")
            return ex.json(503, obj("error" to "the add-on's operator is not running yet; try again in a moment"))
        }
        answer.body().use { stream ->
            val type = answer.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8")
            if (!type.startsWith("text/event-stream")) return ex.reply(answer.statusCode(), type, stream.readBytes())
            ex.head(answer.statusCode(), "Content-Type" to type, "Cache-Control" to "no-store", "X-Accel-Buffering" to "no", "Connection" to "close")
            ex.output.flush()
            ex.socket.soTimeout = 0
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                ex.output.write(buffer, 0, n)
                ex.output.flush()
            }
        }
    }

    private class BodyTooLarge : Exception()

    /** The request body, with Content-Length or chunked, at most [BODY_MAX] bytes. */
    private fun readBody(ex: Exchange): ByteArray {
        val h = ex.request.headers
        if (h["expect"]?.lowercase() == "100-continue") {
            ex.output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            ex.output.flush()
        }
        if ("chunked" in h["transfer-encoding"].orEmpty().lowercase()) {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line(ex.input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw java.io.EOFException()
                if (size == 0) {
                    while (!line(ex.input).isNullOrEmpty()) Unit   // trailers
                    return out.toByteArray()
                }
                if (out.size() + size > BODY_MAX) throw BodyTooLarge()
                out.write(ex.input.readNBytes(size))
                line(ex.input)
            }
        }
        val n = h["content-length"]?.trim()?.toLongOrNull() ?: 0
        if (n > BODY_MAX) throw BodyTooLarge()
        return ex.input.readNBytes(n.toInt())
    }

    companion object {
        const val OPERATOR_TIMEOUT_S = 660L        // a clone may take its 10 minutes
        const val BODY_MAX = 20 * 1024 * 1024      // an upload's 20 MB
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        /** what the operator learns of the caller: the Home Assistant user (its Dreamfall trail) and an upload's name */
        private val FORWARDED = listOf("X-Remote-User-Id", "X-Remote-User-Name", "X-Remote-User-Display-Name", "X-Filename")

        /** What Home Assistant itself calls (homeassistant/packages/dieter.yaml and its scripts): its sensor
         * (GET api/ha), the board (GET api/state), new cards, follow-ups, moves and focus. */
        fun homeAssistantRoute(method: String, parts: List<String>): Boolean = parts.none { it == "." || it == ".." || '%' in it } && when (method) {
            "GET" -> parts == listOf("api", "ha") || parts == listOf("api", "state")
            "POST" -> parts == listOf("api", "card") || parts == listOf("api", "focus") ||
                parts.size == 4 && parts[0] == "api" && parts[3] == "send" ||
                parts.size == 4 && parts[0] == "api" && parts[1] == "card" && parts[3] == "move"
            else -> false
        }
        // webpack's wasm assets (20 hex) and the packaged dieter.<16 hex>.js never change under their name
        private val WEB_HASHED = Regex("(^|[./])[0-9a-f]{16,}\\.(wasm|js|mjs)(\\.LICENSE\\.txt)?(\\.gz|\\.br)?$")
        private val WEB_TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
            "wasm" to "application/wasm", "json" to "application/json", "txt" to "text/plain; charset=utf-8",
            "ttf" to "font/ttf", "otf" to "font/otf", "woff2" to "font/woff2", "svg" to "image/svg+xml", "png" to "image/png",
            "css" to "text/css; charset=utf-8", "xml" to "application/xml", "ico" to "image/x-icon", "gz" to "application/gzip",
        )
        private val REASONS = mapOf(
            100 to "Continue", 101 to "Switching Protocols", 200 to "OK", 302 to "Found", 304 to "Not Modified", 400 to "Bad Request",
            403 to "Forbidden", 404 to "Not Found", 409 to "Conflict", 500 to "Internal Server Error", 501 to "Not Implemented",
            502 to "Bad Gateway", 503 to "Service Unavailable",
        )

        /** Content codings with q > 0 from an Accept-Encoding header. */
        fun acceptedEncodings(header: String?): Set<String> =
            header.orEmpty().lowercase().split(',').mapNotNull { item ->
                val name = item.substringBefore(';').trim()
                val q = Regex("q=([0-9.]+)").find(item.substringAfter(';', ""))?.let { it.groupValues[1].toDoubleOrNull() ?: 0.0 } ?: 1.0
                name.takeIf { it.isNotEmpty() && q > 0 }
            }.toSet()

        /** One header or chunk line without its line end; null at the end of the stream. */
        private fun line(input: InputStream): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                if (out.length > 32 * 1024) throw java.io.IOException("line too long")
                out.append(c.toChar())
            }
        }

        /** Request line and headers (lower-case names); null when the caller sent no complete head. */
        private fun readHead(input: InputStream): Request? {
            val first = line(input)?.split(' ') ?: return null
            if (first.size < 3 || !first[1].startsWith("/")) return null
            val headers = HashMap<String, String>()
            var total = 0
            while (true) {
                val h = line(input) ?: return null
                if (h.isEmpty()) break
                total += h.length
                if (total > 64 * 1024) return null
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            return Request(first[0], first[1], headers)
        }

        private fun obj(vararg pairs: Pair<String, String>): String =
            kotlinx.serialization.json.JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }).toString()
    }
}

internal class HttpReply(val status: Int, val type: String, val body: String)

/** An address range ("172.30.32.0/23", "127.0.0.2/32", "::1/128"). */
internal class Net(spec: String) {
    private val base: ByteArray
    private val bits: Int

    init {
        val (addr, len) = spec.trim().split('/', limit = 2).let { it[0] to it.getOrNull(1) }
        base = InetAddress.getByName(addr).address   // a literal only: no name lookup for an address
        bits = len?.toInt() ?: (base.size * 8)
        require(bits in 0..base.size * 8) { "bad prefix in $spec" }
    }

    operator fun contains(address: InetAddress): Boolean {
        val a = address.address
        if (a.size != base.size) return false
        for (i in 0 until bits) {
            val mask = 0x80 ushr (i % 8)
            if ((a[i / 8].toInt() and mask) != (base[i / 8].toInt() and mask)) return false
        }
        return true
    }

    companion object {
        /** Home Assistant's Ingress: the Supervisor's address, the only one the add-on rules allow. */
        val INGRESS = Net("172.30.32.2/32")

        /** Home Assistant core, which runs on the host network and so reaches add-ons from the network's gateway. */
        val HOME_ASSISTANT = Net("172.30.32.1/32")

        fun list(specs: String?): List<Net> = specs.orEmpty().split(',').filter { it.isNotBlank() }.map(::Net)
    }
}
