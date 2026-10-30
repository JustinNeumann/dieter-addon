package com.getdieter.operator

import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class QuestionsTest {
    private val noDieter = Dieter { _, _ -> throw IllegalStateException("not called") }
    private var clock = System.currentTimeMillis()
    private val questions = Questions { clock }
    private val dir = kotlin.io.path.createTempDirectory("uploads").toFile()
    private val server =
        OperatorServer(
            InetSocketAddress("127.0.0.1", 0),
            routes(
                noDieter,
                Settings(kotlin.io.path.createTempFile().toFile(), OllamaEntity(null)) { "" },
                Projects(noDieter, kotlin.io.path.createTempDirectory().toFile(), { null }),

                questions = questions,
                uploads = Uploads(dir) { clock },
            ),
        ).start()
    private val base = "http://127.0.0.1:${server.address.port}/api/"
    private val http = HttpClient.newHttpClient()

    @AfterTest fun stop() = server.stop(0)

    private fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, JsonObject> {
        val request = HttpRequest.newBuilder(URI(base + path)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> request.header(k, v) }
        val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return answer.statusCode() to (Json.parseToJsonElement(answer.body()) as JsonObject)
    }

    private val question = """{"id": "toolu_1", "session": "c_card1", "input": {"questions": [{"id": "q1", "question": "Which colour?", "options": [{"id": "red"}]}]}}"""

    @Test
    fun theWorkerWaitsForTheAnswerGivenOnThePageAndGetsItOnce() {
        assertEquals(200, call("POST", "questions", question).first)
        val pending = call("GET", "questions").second["questions"]!!.jsonArray
        assertEquals(listOf("toolu_1"), pending.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("c_card1", pending[0].jsonObject["session"]!!.jsonPrimitive.content)
        assertTrue(pending[0].jsonObject["created_at"]!!.jsonPrimitive.content.matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d[+-]\\d{4}")))
        assertEquals("""{"done":false}""", call("GET", "questions/toolu_1").second.toString())

        val answer = """{"action": "answered", "answers": {"q1": {"optionIds": ["red", 2, 2.5, true, null], "freeform": "  "}, "q2": {"optionIds": []}, "q3": "x"}}"""
        assertEquals(200, call("POST", "questions/toolu_1/answer", answer).first)
        assertEquals(0, call("GET", "questions").second["questions"]!!.jsonArray.size, "answered: no longer waiting for the person")
        assertEquals((400 to "already answered"), call("POST", "questions/toolu_1/answer", """{"action": "declined"}""").let { it.first to it.second["error"]!!.jsonPrimitive.content })

        val polled = call("GET", "questions/toolu_1")
        assertEquals("""{"done":true,"output":{"action":"answered","answers":{"q1":{"optionIds":["red","2"]}}}}""", polled.second.toString())
        assertEquals(404 to "no such question", call("GET", "questions/toolu_1").let { it.first to it.second["error"]!!.jsonPrimitive.content }, "handed over once")
    }

    @Test
    fun badQuestionsAndAnswersAreRefusedAndAnUnansweredOneIsForgottenAfterADay() {
        assertEquals("invalid question id", call("POST", "questions", """{"id": "a b", "session": "c", "input": {"questions": [{}]}}""").second["error"]!!.jsonPrimitive.content)
        assertEquals("invalid session", call("POST", "questions", """{"id": "a", "session": "c/1", "input": {"questions": [{}]}}""").second["error"]!!.jsonPrimitive.content)
        assertEquals("input.questions required", call("POST", "questions", """{"id": "a", "session": "c", "input": {"questions": []}}""").second["error"]!!.jsonPrimitive.content)
        call("POST", "questions", question)
        assertEquals("action must be one of answered, partially-answered, declined, cancelled", call("POST", "questions/toolu_1/answer", """{"action": "maybe"}""").second["error"]!!.jsonPrimitive.content)
        assertEquals("answers required", call("POST", "questions/toolu_1/answer", """{"action": "answered"}""").second["error"]!!.jsonPrimitive.content)
        assertEquals("no such question (the agent may have moved on)", call("POST", "questions/other/answer", """{"action": "declined"}""").second["error"]!!.jsonPrimitive.content)
        clock += Questions.TTL_MS + 1
        assertEquals(0, call("GET", "questions").second["questions"]!!.jsonArray.size)
        assertFailsWith<BadRequest> { questions.answer("toolu_1", Json.parseToJsonElement("""{"action": "declined"}""") as JsonObject) }
    }

    @Test
    fun anUploadIsKeptUnderItsIdAndOldOnesNobodySentAreRemoved() {
        val (status, first) = call("POST", "upload", "first file", mapOf("X-Filename" to "My%20Plan+v2%20(final).pdf"))
        assertEquals(200, status)
        val id = first["id"]!!.jsonPrimitive.content
        assertTrue(id.matches(Regex("^[A-Za-z0-9_-]{24}$")), id)
        assertEquals("My-Plan-v2-final-.pdf", first["name"]!!.jsonPrimitive.content)
        assertEquals("first file", File(dir, "$id/My-Plan-v2-final-.pdf").readText())
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(File(dir, "$id/My-Plan-v2-final-.pdf").toPath())))

        File(dir, id).setLastModified(clock - Uploads.DAY_MS - 1)
        val second = call("POST", "upload", "x").second
        assertEquals("file", second["name"]!!.jsonPrimitive.content, "no name: file")
        assertEquals(listOf(second["id"]!!.jsonPrimitive.content), dir.list()!!.toList(), "the day-old upload nobody sent is gone")
    }
}
