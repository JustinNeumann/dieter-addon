package com.getdieter.operator

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Home Assistant core's REST API through the Supervisor (`homeassistant_api: true`). */
fun interface HomeAssistant {
    /** POST [body] to /core/api/[path]; the answer's text. Throws when HA cannot be reached or refuses. */
    fun post(path: String, body: JsonElement, timeout: Duration): String

    companion object {
        /**
         * Home Assistant for the operator: in the add-on through the web host's relay (DIETER_HA_RELAY_URL), which
         * holds the add-on's token so the operator and the agents need not; else directly with SUPERVISOR_TOKEN; null
         * outside an add-on.
         */
        fun of(env: Map<String, String>): HomeAssistant? {
            env["DIETER_HA_RELAY_URL"]?.takeIf { it.isNotBlank() }?.let { return client(it.trimEnd('/') + "/core/api/", null) }
            val token = env["SUPERVISOR_TOKEN"]?.takeIf { it.isNotBlank() } ?: return null
            return client("http://supervisor/core/api/", token)
        }

        private fun client(base: String, token: String?): HomeAssistant {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            return HomeAssistant { path, body, timeout ->
                val request = HttpRequest.newBuilder(URI(base + path)).timeout(timeout).header("Content-Type", "application/json")
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}: ${response.body().take(200)}" }
                response.body()
            }
        }
    }
}

/**
 * The first available ai_task entity of HA's Ollama integration, looked up again after [ttl] (an integration added
 * later is picked up) or after [forget]; null without one or outside an add-on.
 */
class OllamaEntity(private val ha: HomeAssistant?, private val ttl: Duration = Duration.ofMinutes(10), private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var entity: String? = null
    @Volatile private var at = Long.MIN_VALUE / 2

    fun get(): String? {
        val ha = ha ?: return null
        if (now() - at > ttl.toMillis()) {
            at = now()
            val found =
                runCatching {
                    (Json.parseToJsonElement(ha.post("template", buildJsonObject { put("template", TEMPLATE) }, Duration.ofSeconds(10))) as? JsonArray)
                        ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
                }.onFailure { Log.line("title: ollama lookup $it") }.getOrNull()
            if (found != entity) Log.line("title: ollama ai task ${found ?: "none"}")
            entity = found
        }
        return entity
    }

    /** Inside the add-on: Home Assistant can be asked at all. */
    val reachable: Boolean get() = ha != null

    fun forget() {
        at = Long.MIN_VALUE / 2
    }

    companion object {
        /** Finds the Ollama integration's ai_task entities (the web host's relay lets only this template through). */
        const val TEMPLATE = "{{ integration_entities('ollama') | select('match', 'ai_task\\\\.') | reject('is_state', 'unavailable') | list | tojson }}"
    }
}
