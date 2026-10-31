package com.getdieter.webhost

import com.getdieter.operator.OllamaEntity
import com.getdieter.operator.Titles
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Home Assistant for the operator, without the add-on's token. That token is the Supervisor's in Home Assistant, an
 * administrator's; the operator shares its user with the agents, which would read it from its environment. So the
 * web host (user dieter-web) keeps it and passes on to Home Assistant core, through the Supervisor, only what the
 * operator does there:
 *   POST /core/api/events/dieter_turn_finished, …/dieter_card_review, …/dieter_question   its events
 *   POST /core/api/template                  the one template that finds the Ollama ai_task entity ([OllamaEntity])
 *   POST /core/api/services/ai_task/generate_data?return_response   a task title ([Titles]): an ai_task entity,
 *                                            the task name "Dieter task title" and the instructions
 * Anything else is 403. Agents on loopback can make these calls too; the most they get is a made-up event (a
 * notification) or a title from the local model. On loopback only.
 */
class HomeAssistantRelay(
    private val token: String,
    private val address: InetSocketAddress,
    private val target: URI = URI("http://supervisor/core/api/"),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun start(): HttpServer =
        HttpServer.create(address, 50).apply {
            executor = Executors.newVirtualThreadPerTaskExecutor()
            createContext("/core/api/") { handle(it) }
            start()
            Log.line("home assistant relay on ${address.hostString}:${this.address.port}")
        }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val path = ex.requestURI.rawPath.removePrefix("/core/api/") + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val body = ex.requestBody.readNBytes(MAX_BODY + 1)
            val json = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()
            if (ex.requestMethod != "POST" || body.size > MAX_BODY || json == null || !allowed(path, json)) {
                Log.line("home assistant relay: refused ${ex.requestMethod} $path")
                return reply(ex, 403, """{"error":"forbidden"}""".toByteArray())
            }
            val request = HttpRequest.newBuilder(target.resolve(path)).timeout(Duration.ofSeconds(Titles.TIMEOUT_S + 10))
                .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
            val answer = try {
                http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                return reply(ex, 502, """{"error":"home assistant: ${e.javaClass.simpleName}"}""".toByteArray())
            }
            reply(ex, answer.statusCode(), answer.body())
        }
    }

    private fun reply(ex: HttpExchange, status: Int, bytes: ByteArray) {
        ex.responseHeaders["Content-Type"] = "application/json; charset=utf-8"
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MAX_BODY = 64_000
        /** The operator's events and the field that names their card. */
        private val EVENTS = mapOf("dieter_turn_finished" to "id", "dieter_card_review" to "id", "dieter_question" to "card_id")

        /** The daemon's card and chat ids: `c_` and hex (12 random, 24 deterministic, up to 16 on its fallback). */
        private val CARD_ID = Regex("^c_[0-9a-f]{12,32}$")
        private val AI_TASK = Regex("^ai_task\\.[a-z0-9_]{1,100}$")

        /** Whether the operator does this in Home Assistant (see the class). */
        fun allowed(path: String, body: JsonObject): Boolean = when {
            // the package builds a notification's link and tag from the card id: only a card's id shape passes, so a
            // forged event can carry made-up text but cannot point the link elsewhere in Home Assistant
            path.startsWith("events/") -> EVENTS[path.removePrefix("events/")]?.let { key -> body.text(key)?.let(CARD_ID::matches) } == true
            path == "template" -> body == JsonObject(mapOf("template" to JsonPrimitive(OllamaEntity.TEMPLATE)))
            path == "services/ai_task/generate_data?return_response" ->
                body.keys == setOf("entity_id", "task_name", "instructions") &&
                    body.text("entity_id")?.let(AI_TASK::matches) == true &&
                    body.text("task_name") == Titles.TASK_NAME &&
                    (body.text("instructions")?.length ?: Int.MAX_VALUE) <= 6000
            else -> false
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
