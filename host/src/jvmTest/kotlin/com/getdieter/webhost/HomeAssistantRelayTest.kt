package com.getdieter.webhost

import com.getdieter.operator.HomeAssistant
import com.getdieter.operator.OllamaEntity
import com.getdieter.operator.Titles
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HomeAssistantRelayTest {
    private val seen = LinkedBlockingQueue<String>()
    private val core = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 10).apply {
        createContext("/core/api/") { ex ->
            seen += "${ex.requestURI} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestBody.readAllBytes().decodeToString()}"
            val bytes = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val relay = HomeAssistantRelay("t0ken", InetSocketAddress("127.0.0.1", 0), URI("http://127.0.0.1:${core.address.port}/core/api/")).start()
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        relay.stop(0)
        core.stop(0)
    }

    private fun post(path: String, body: String, method: String = "POST"): Int =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${relay.address.port}/core/api/$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun onlyTheOperatorsCallsPass() {
        val title = buildJsonObject { put("entity_id", "ai_task.qwen"); put("task_name", Titles.TASK_NAME); put("instructions", "Task: x") }
        assertTrue(HomeAssistantRelay.allowed("events/dieter_question", json("""{"card_id":"c_1"}""")))
        assertTrue(HomeAssistantRelay.allowed("events/dieter_turn_finished", json("{}")))
        assertTrue(HomeAssistantRelay.allowed("template", buildJsonObject { put("template", OllamaEntity.TEMPLATE) }))
        assertTrue(HomeAssistantRelay.allowed("services/ai_task/generate_data?return_response", title))
        assertFalse(HomeAssistantRelay.allowed("events/call_service", json("{}")))
        assertFalse(HomeAssistantRelay.allowed("template", json("""{"template":"{{ states | list }}"}""")))
        assertFalse(HomeAssistantRelay.allowed("services/rest_command/dieter_send_message", json("{}")))
        assertFalse(HomeAssistantRelay.allowed("services/ai_task/generate_data?return_response", JsonObject(title + ("attachments" to json("{}")))))
        assertFalse(HomeAssistantRelay.allowed("services/ai_task/generate_data?return_response", JsonObject(title + ("entity_id" to kotlinx.serialization.json.JsonPrimitive("light.x")))))
        assertFalse(HomeAssistantRelay.allowed("services/ai_task/generate_data", title))
        assertFalse(HomeAssistantRelay.allowed("states", json("{}")))
    }

    @Test
    fun itAddsTheTokenOnTheWayToHomeAssistantAndRefusesTheRest() {
        assertEquals(403, post("services/rest_command/dieter_send_message", """{"card":"c_1","message":"hi"}"""))
        assertEquals(403, post("states", "{}", method = "GET"))
        assertEquals(403, post("events/dieter_question", "not json"))
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS), "nothing refused reaches Home Assistant")
        // the operator's client, pointed at the relay, sends no token of its own
        val ha = HomeAssistant.of(mapOf("DIETER_HA_RELAY_URL" to "http://127.0.0.1:${relay.address.port}", "SUPERVISOR_TOKEN" to "unused"))!!
        assertEquals("""{"ok":true}""", ha.post("events/dieter_question", json("""{"card_id":"c_1"}"""), Duration.ofSeconds(5)))
        assertEquals("""/core/api/events/dieter_question Bearer t0ken {"card_id":"c_1"}""", seen.poll(5, TimeUnit.SECONDS))
    }
}
