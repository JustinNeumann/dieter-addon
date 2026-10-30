package com.getdieter.operator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One request to /api/…: the path's segments after "api", the route's {placeholders}, the query and the body, read
 * when a handler asks: as bytes ([raw], an upload) or as a JSON object ([body]).
 */
class Request(
    val method: String,
    val parts: List<String>,
    val params: Map<String, String>,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    read: () -> ByteArray,
) {
    val raw: ByteArray by lazy(read)

    /** The Home Assistant user behind Ingress (it forwards the logged-in user's name, the bridge passes it on); "you" without one. */
    val user: String
        get() = listOfNotNull(headers["x-remote-user-display-name"], headers["x-remote-user-name"]).firstOrNull { it.isNotEmpty() }.orEmpty().trim().take(60).ifEmpty { "you" }

    val body: JsonObject by lazy {
        val text = raw.decodeToString()
        if (text.isBlank()) JsonObject(emptyMap())
        else runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw BadRequest("body: a JSON object")
    }
}

/** Nothing at this path (an unknown question); answered with 404 and the message. */
class NotFound(message: String) : Exception(message)

/** Server-Sent Events to one client: each [send] is flushed at once; it throws once the client has left. */
class Sse(private val out: java.io.OutputStream) {
    fun send(event: String, data: JsonElement) {
        out.write("event: $event\ndata: $data\n\n".toByteArray())
        out.flush()
    }
}

/**
 * Routes by method and path pattern ("dreamfall/{pid}/act"); a handler answers JSON or throws [BadRequest]/[CliError].
 * A [stream] route answers GET with Server-Sent Events instead, for as long as the client stays.
 */
class Routes {
    private class Route(val method: String, val pattern: List<String>, val handle: (Request) -> JsonElement)

    private val routes = mutableListOf<Route>()
    private val streams = mutableListOf<Pair<List<String>, (Request, Sse) -> Unit>>()

    fun get(pattern: String, handle: (Request) -> JsonElement) = add("GET", pattern, handle)

    fun post(pattern: String, handle: (Request) -> JsonElement) = add("POST", pattern, handle)

    fun stream(pattern: String, handle: (Request, Sse) -> Unit) = apply { streams += pattern.split("/") to handle }

    private fun add(method: String, pattern: String, handle: (Request) -> JsonElement) =
        apply { routes += Route(method, pattern.split("/"), handle) }

    /** The stream that answers GET [parts], ready to run with its response body; null when none does. */
    fun streamFor(method: String, parts: List<String>, query: Map<String, List<String>>, headers: Map<String, String>): ((Sse) -> Unit)? {
        if (method != "GET") return null
        for ((pattern, handle) in streams) {
            val params = match(pattern, parts) ?: continue
            return { sse -> handle(Request(method, parts, params, query, headers) { ByteArray(0) }, sse) }
        }
        return null
    }

    /** (status, answer) for a request; 404 when no route takes it, 405 when only another method does. */
    fun answer(method: String, parts: List<String>, query: Map<String, List<String>>, headers: Map<String, String>, read: () -> ByteArray): Pair<Int, JsonElement> {
        var other = false
        for (route in routes) {
            val params = match(route.pattern, parts) ?: continue
            if (route.method != method) {
                other = true
                continue
            }
            return try {
                200 to route.handle(Request(method, parts, params, query, headers, read))
            } catch (e: BadRequest) {
                400 to errorAnswer(e.message)
            } catch (e: NotFound) {
                404 to errorAnswer(e.message)
            } catch (e: CliError) {
                502 to errorAnswer(e.message)
            }
        }
        return if (other) 405 to errorAnswer("method not allowed") else 404 to errorAnswer("not found")
    }

    private fun match(pattern: List<String>, parts: List<String>): Map<String, String>? {
        if (parts.size != pattern.size) return null
        val params = mutableMapOf<String, String>()
        for ((p, s) in pattern.zip(parts)) {
            if (p.startsWith("{") && p.endsWith("}")) params[p.substring(1, p.length - 1)] = s else if (p != s) return null
        }
        return params
    }
}

fun errorAnswer(message: String?) = buildJsonObject { put("error", message ?: "error") }

/** The JDK's HTTP server on loopback, one virtual thread per request, every answer JSON and never cached. */
class OperatorServer(private val address: InetSocketAddress, private val routes: Routes) {
    fun start(): HttpServer =
        HttpServer.create(address, 50).apply {
            executor = Executors.newVirtualThreadPerTaskExecutor()
            createContext("/health") { it.reply(200, buildJsonObject { put("ok", true) }) }
            createContext("/api/") { handle(it) }
            start()
        }

    private fun handle(exchange: HttpExchange) {
        val (status, answer) =
            try {
                val uri = exchange.requestURI
                val parts = uri.rawPath.split("/").filter { it.isNotEmpty() }.drop(1).map { URLDecoder.decode(it, Charsets.UTF_8) }
                val headers = exchange.requestHeaders.mapNotNull { (k, v) -> v.firstOrNull()?.let { k.lowercase() to it } }.toMap()
                routes.streamFor(exchange.requestMethod, parts, query(uri.rawQuery), headers)?.let { stream ->
                    exchange.responseHeaders["Content-Type"] = "text/event-stream; charset=utf-8"
                    exchange.responseHeaders["Cache-Control"] = "no-store"
                    exchange.responseHeaders["X-Accel-Buffering"] = "no"
                    exchange.sendResponseHeaders(200, 0)
                    try {
                        exchange.responseBody.use { stream(Sse(it)) }
                    } catch (_: java.io.IOException) {   // the client left
                    } finally {
                        exchange.close()
                    }
                    return
                }
                routes.answer(exchange.requestMethod, parts, query(uri.rawQuery), headers) { read(exchange) }
            } catch (e: BadRequest) {
                400 to errorAnswer(e.message)
            } catch (e: Exception) {
                Log.line("${exchange.requestMethod} ${exchange.requestURI.path}: $e")
                500 to errorAnswer(e.toString())
            }
        exchange.reply(status, answer)
    }

    /** The body, up to an upload's 20 MB. */
    private fun read(exchange: HttpExchange): ByteArray {
        val bytes = exchange.requestBody.readNBytes(Uploads.MAX + 1)
        if (bytes.size > Uploads.MAX) throw BadRequest("file too large (20 MB at most)")
        return bytes
    }

    private fun query(raw: String?): Map<String, List<String>> =
        raw.orEmpty().split("&").filter { it.isNotEmpty() }
            .map { it.substringBefore("=").decode() to it.substringAfter("=", "").decode() }
            .groupBy({ it.first }, { it.second })

    private fun String.decode() = URLDecoder.decode(this, Charsets.UTF_8)

    private fun HttpExchange.reply(status: Int, answer: JsonElement) {
        val bytes = answer.toString().toByteArray()
        responseHeaders["Content-Type"] = "application/json; charset=utf-8"
        responseHeaders["Cache-Control"] = "no-store"
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
