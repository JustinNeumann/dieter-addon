package com.getdieter.operator

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TitlesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    /** A card or chat whose title is [title] until renamed; records the CLI calls. */
    private class Store(@Volatile var title: String) : Dieter {
        val calls = CopyOnWriteArrayList<List<String>>()
        val renamed = CountDownLatch(1)
        val shown = CountDownLatch(1)

        override fun run(args: List<String>, timeout: Duration): JsonElement? {
            calls += args
            return when (args[1]) {
                // Both `dieter card show` and `dieter chat show` return {"card": {...}}.
                "show" -> Json.parseToJsonElement("""{"card": {"id": "${args[2]}", "title": "$title"}}""").also { shown.countDown() }
                "rename" -> null.also { title = args[3]; renamed.countDown() }
                else -> throw IllegalStateException("unexpected $args")
            }
        }
    }

    /** Home Assistant's ai_task answering [answer] as the generated data; remembers what it was asked. */
    private class Ha(private val answer: String) : HomeAssistant {
        val asked = CopyOnWriteArrayList<Pair<String, JsonElement>>()

        override fun post(path: String, body: JsonElement, timeout: java.time.Duration): String {
            asked += path to body
            return """{"service_response": {"data": ${Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(answer))}}}"""
        }
    }

    @Test
    fun theProvisionalTitleIsTheFirstLineAndTheModelsAnswerIsCleaned() {
        assertEquals("Fix the heater", Titles.firstLine("\n\n# Fix the   heater\nin the bathroom"))
        assertEquals("New task", Titles.firstLine(" \n "))
        assertEquals("not a list", Titles.firstLine("> - - not a list"))
        val long = "Check every radiator in the house and write down which ones stay cold when the heating runs at night"
        assertEquals("Check every radiator in the house and write down which ones stay cold when the", Titles.firstLine(long))
        assertEquals("x".repeat(80), Titles.firstLine("x".repeat(120)))
        assertEquals("Heizung prüfen", Titles.clean("Title: „Heizung prüfen“."))
        assertEquals("Fix it", Titles.clean("\n\n  **Fix it**  \nmore"))
        assertEquals("Titel ohne Doppelpunkt", Titles.clean("Titel ohne Doppelpunkt"))
        assertNull(Titles.clean(" \n "))
        assertNull(Titles.clean(null))
    }

    @Test
    fun aNewTaskIsRenamedWhenTheModelAnswers() {
        val store = Store("Fix it")
        val ha = Ha("Title: Fix the heater.")
        val patched = CopyOnWriteArrayList<Pair<String, String>>()
        val titles = Titles(store, ha, onRenamed = { id, t -> patched += id to t }) { "ai_task.ollama" }
        assertEquals(obj("""{"ok": true}"""), titles.nameLater(obj("""{"kind": "chat", "id": "c_new", "prompt": "Fix it\nnow", "provisional": "Fix it"}""")))
        assertTrue(store.renamed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("chat", "rename", "--title", "Fix the heater", "c_new"), store.calls.last())
        assertEquals(listOf("c_new" to "Fix the heater"), patched.toList())
        val (path, body) = ha.asked.single()
        assertEquals("services/ai_task/generate_data?return_response", path)
        assertEquals("ai_task.ollama", body.jsonObject["entity_id"]!!.jsonPrimitive.content)
        assertTrue(body.jsonObject["instructions"]!!.jsonPrimitive.content.endsWith("Task:\nFix it\nnow"))
    }

    @Test
    fun aFailedModelCallInvalidatesTheCachedEntity() {
        val store = Store("Fix it")
        val forgotten = CountDownLatch(1)
        val failingHa = HomeAssistant { _, _, _ -> throw IllegalStateException("HTTP 502: Ollama unreachable") }
        val titles = Titles(store, failingHa, onFailure = forgotten::countDown) { "ai_task.ollama" }
        titles.nameLater(obj("""{"kind": "card", "id": "c_fail", "prompt": "Fix it", "provisional": "Fix it"}"""))
        assertTrue(forgotten.await(5, TimeUnit.SECONDS))
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aTitleSomeoneSetMeanwhileStays() {
        val store = Store("Renamed by hand")
        val titles = Titles(store, Ha("Fix the heater")) { "ai_task.ollama" }
        titles.nameLater(obj("""{"kind": "card", "id": "c_1", "prompt": "Fix it", "provisional": "Fix it"}"""))
        assertTrue(store.shown.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(listOf(listOf("card", "show", "c_1")), store.calls.toList())
        assertEquals("Renamed by hand", store.title)
    }

    @Test
    fun badRequestsAndNoOllamaAreRefused() {
        val titles = Titles(Store("x"), Ha("y")) { "ai_task.ollama" }
        for (bad in listOf(
            """{"kind": "board", "id": "c", "prompt": "p", "provisional": "p"}""", """{"kind": "card", "id": "../x", "prompt": "p", "provisional": "p"}""",
            """{"kind": "card", "id": "c", "prompt": " ", "provisional": "p"}""", """{"kind": "card", "id": "c", "prompt": "p"}""",
        )) assertEquals("titles: {kind: card|chat, id, prompt, provisional}", assertFailsWith<BadRequest>(bad) { titles.nameLater(obj(bad)) }.message)
        val off = Titles(Store("x"), Ha("y")) { null }
        assertEquals(
            "Ollama no longer names new tasks; this one keeps its first line as title",
            assertFailsWith<BadRequest> { off.nameLater(obj("""{"kind": "card", "id": "c_1", "prompt": "p", "provisional": "p"}""")) }.message,
        )
    }

    @Test
    fun theOperatorAnswersTitlesAndAChangedRetentionWakesTheSweep() {
        val file = kotlin.io.path.createTempFile().toFile()
        val settings = Settings(file, OllamaEntity(null)) { "" }
        val woken = CountDownLatch(2)
        val sweeps = CopyOnWriteArrayList<Int>()
        val housekeeping = Housekeeping(Sweeper({ _, _ -> null }, 14)) { settings.read().retentionDays.also { sweeps += it; woken.countDown() } }
        Thread.ofVirtual().start { housekeeping.loop(firstDelayMs = 0, periodMs = 60_000) }
        val server = OperatorServer(
            InetSocketAddress("127.0.0.1", 0),
            routes(Store("x"), settings, Projects(Store("x"), kotlin.io.path.createTempDirectory().toFile(), { null }), housekeeping = housekeeping),
        ).start()
        try {
            fun post(path: String, body: String): Pair<Int, JsonObject> {
                val r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/api/$path")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return r.statusCode() to obj(r.body())
            }
            // outside the add-on there is no Ollama to ask
            assertEquals(400, post("titles", """{"kind": "card", "id": "c_1", "prompt": "p", "provisional": "p"}""").first)
            Thread.sleep(200)
            assertEquals(200, post("settings", """{"title_model": "openai"}""").first)
            Thread.sleep(300)
            assertEquals(listOf(14), sweeps.toList(), "the same retention: no sweep")
            assertEquals(200, post("settings", """{"worktree_retention_days": 30}""").first)
            assertTrue(woken.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(14, 30), sweeps.toList())
        } finally {
            server.stop(0)
        }
    }
}
