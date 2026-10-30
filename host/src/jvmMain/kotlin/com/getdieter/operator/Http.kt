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

/** One request to /api/…: the path's segments after "api", the route's {placeholders}, the query and a JSON body. */
class Request(
    val method: String,
    val parts: List<String>,
    val params: Map<String, String>,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    val body: JsonObject,
)

/** Routes by method and path pattern ("dreamfall/{pid}/act"); a handler answers JSON or throws [BadRequest]/[CliError]. */
class Routes {
    private class Route(val method: String, val pattern: List<String>, val handle: (Request) -> JsonElement)

    private val routes = mutableListOf<Route>()

    fun get(pattern: String, handle: (Request) -> JsonElement) = add("GET", pattern, handle)

    fun post(pattern: String, handle: (Request) -> JsonElement) = add("POST", pattern, handle)

    private fun add(method: String, pattern: String, handle: (Request) -> JsonElement) =
        apply { routes += Route(method, pattern.split("/"), handle) }

    /** (status, answer) for a request; 404 when no route takes it, 405 when only another method does. */
    fun answer(method: String, parts: List<String>, query: Map<String, List<String>>, headers: Map<String, String>, body: () -> JsonObject): Pair<Int, JsonElement> {
        var other = false
        for (route in routes) {
            val params = route.match(parts) ?: continue
            if (route.method != method) {
                other = true
                continue
            }
            return try {
                200 to route.handle(Request(method, parts, params, query, headers, body()))
            } catch (e: BadRequest) {
                400 to error(e.message)
            } catch (e: CliError) {
                502 to error(e.message)
            }
        }
        return if (other) 405 to error("method not allowed") else 404 to error("not found")
    }

    private fun Route.match(parts: List<String>): Map<String, String>? {
        if (parts.size != pattern.size) return null
        val params = mutableMapOf<String, String>()
        for ((p, s) in pattern.zip(parts)) {
            if (p.startsWith("{") && p.endsWith("}")) params[p.substring(1, p.length - 1)] = s else if (p != s) return null
        }
        return params
    }
}

fun error(message: String?) = buildJsonObject { put("error", message ?: "error") }

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
                routes.answer(exchange.requestMethod, parts, query(uri.rawQuery), headers) { body(exchange) }
            } catch (e: BadRequest) {
                400 to error(e.message)
            } catch (e: Exception) {
                Log.line("${exchange.requestMethod} ${exchange.requestURI.path}: $e")
                500 to error(e.toString())
            }
        exchange.reply(status, answer)
    }

    private fun body(exchange: HttpExchange): JsonObject {
        val text = exchange.requestBody.readBytes().decodeToString()
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw BadRequest("body: a JSON object")
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
