package com.getdieter.operator

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OperatorTest {
    /** Answers `dieter …` from a table of "first two args" → JSON, and records every call. */
    private class FakeDieter(private val answers: Map<String, String>, private val fail: String? = null) : Dieter {
        val calls = mutableListOf<List<String>>()

        override fun run(args: List<String>, timeout: kotlin.time.Duration): JsonElement? {
            calls += args
            val key = args.take(2).joinToString(" ")
            if (fail != null && key == "prompt update") throw CliError(fail)
            return answers[key]?.let(Json::parseToJsonElement)
        }
    }

    private val show = """{"promptTemplate":"Every {{project.instructions_block}}","boardSkillTemplate":"Cards","chatSkillTemplate":"","variables":["card.title"]}"""
    private val dieter =
        FakeDieter(mapOf(
            "prompt show" to show,
            "project list" to """{"projects":[{"id":"p1","name":"Home","promptTemplate":"own"},{"id":"p2","name":"Other"}]}""",
            "board list" to """[{"id":"b1","name":"Main","project_id":"p2","prompt_template":"board's"},{"id":"b2","name":"Plain","projectId":"p1"}]""",
        ))

    @Test
    fun `the view has the three texts, the variables and the projects and boards with their own template`() {
        val v = Prompts(dieter).view()
        assertEquals("Every {{project.instructions_block}}", v["template"]!!.jsonPrimitive.content)
        assertEquals("Cards", v["board_skill"]!!.jsonPrimitive.content)
        assertEquals("", v["chat_skill"]!!.jsonPrimitive.content)
        assertEquals("card.title", v["variables"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            listOf("""{"kind":"project","id":"p1","name":"Home"}""", """{"kind":"board","id":"b1","name":"Main","project":"Other"}"""),
            v["overrides"]!!.jsonArray.map { it.toString() },
        )
    }

    @Test
    fun `an update sets texts with = and resets by the CLI's names`() {
        Prompts(dieter).update(body("""{"board_skill":"-starts with a dash","reset":["chat_skill"]}"""))
        assertEquals(listOf("prompt", "update", "--board-skill=-starts with a dash", "--reset", "chat-skill"), dieter.calls.first { it[0] == "prompt" && it[1] == "update" })
    }

    @Test
    fun `an update is refused when it is empty, blank or resets what it sets`() {
        for (b in listOf("""{}""", """{"template":"  "}""", """{"template":"x","reset":["template"]}""", """{"reset":["nope"]}""", """{"reset":"template"}"""))
            assertFailsWith<BadRequest>(b) { Prompts(dieter).update(body(b)) }
        assertTrue(dieter.calls.none { it.getOrNull(1) == "update" })
    }

    @Test
    fun `the daemon's refusal reaches the page as its message`() {
        val refusing = FakeDieter(emptyMap(), "error: rpc error: code = InvalidArgument desc = template must contain {{labels.instructions_block}} exactly once")
        val e = assertFailsWith<BadRequest> { Prompts(refusing).update(body("""{"template":"Hello"}""")) }
        assertEquals("template must contain {{labels.instructions_block}} exactly once", e.message)
        assertFailsWith<CliError> { Prompts(FakeDieter(emptyMap(), "error: daemon unreachable")).update(body("""{"template":"Hello"}""")) }
    }

    @Test
    fun `the server answers JSON on loopback with the bridge's status codes`() {
        val server = OperatorServer(InetSocketAddress("127.0.0.1", 0), routes(FakeDieter(mapOf("prompt show" to show), "error: daemon unreachable"), Settings(kotlin.io.path.createTempFile().toFile(), OllamaEntity(null)) { "" })).start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            fun call(method: String, path: String, body: String = ""): Pair<Int, JsonObject> {
                val r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("$base$path")).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return r.statusCode() to Json.parseToJsonElement(r.body()).jsonObject
            }
            assertEquals(200, call("GET", "/health").first)
            assertEquals("Cards", call("GET", "/api/prompts").second["board_skill"]!!.jsonPrimitive.content)
            assertEquals(400, call("POST", "/api/prompts", """{"template":" "}""").first)
            assertEquals(400, call("POST", "/api/prompts", "[1]").first)
            assertEquals(502 to "error: daemon unreachable", call("POST", "/api/prompts", """{"template":"x"}""").let { it.first to it.second["error"]!!.jsonPrimitive.content })
            assertEquals(404, call("GET", "/api/nothing").first)
            assertEquals(405, call("DELETE", "/api/prompts").first)
        } finally {
            server.stop(0)
        }
    }

    private fun body(json: String) = Json.parseToJsonElement(json) as JsonObject
}
