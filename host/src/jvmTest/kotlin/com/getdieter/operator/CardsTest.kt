package com.getdieter.operator

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Home Assistant's and the page's card actions (Cards) and the live routes over HTTP, the events stream among them. */
class CardsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private inner class Cli : Dieter {
        val calls = CopyOnWriteArrayList<List<String>>()
        val cards = mutableListOf(
            obj("""{"id": "c_a1", "title": "Heater", "project_id": "p_a", "board_id": "b_a", "lane": "review", "owner_daemon_id": "d_here", "runtime": "idle", "scope": "board"}"""),
            obj("""{"id": "c_a2", "title": "Shutters", "project_id": "p_a", "board_id": "b_a", "lane": "running", "owner_daemon_id": "d_here", "runtime": "running", "scope": "board"}"""),
        )

        override fun run(args: List<String>, timeout: Duration): JsonElement? {
            calls += args
            return when (args.take(2)) {
                listOf("status", "--format") -> obj("""{"health": {"status": "ok"}}""")
                listOf("project", "list") -> obj("""{"projects": [{"id": "p_a", "name": "House"}]}""")
                listOf("board", "list") -> obj("""{"boards": [{"id": "b_a", "name": "Main", "projectId": "p_a"}]}""")
                listOf("chat", "list") -> JsonArray(emptyList())
                listOf("card", "list") -> JsonArray(cards.toList())
                listOf("card", "show") -> throw CliError("rpc error: code = NotFound desc = card \"${args[2]}\": not found")
                listOf("card", "create"), listOf("chat", "create") -> {
                    val kind = args[0]
                    obj(
                        """{"card": {"id": "c_new", "title": "${args.getOrNull(args.indexOf("--title") + 1).takeIf { "--title" in args } ?: ""}", "projectId": "p_a",
                            "boardId": ${if (kind == "card") "\"b_a\"" else "null"}, "lane": ${if (kind == "card") "\"running\"" else "null"},
                            "ownerDaemonId": "d_here", "runtime": "idle", "scope": "${if (kind == "card") "board" else "chat"}"}}""",
                    )
                }
                listOf("card", "move") -> JsonPrimitive("moved")
                listOf("card", "read") -> JsonPrimitive("read")
                else -> throw IllegalStateException("unexpected $args")
            }
        }

        fun last(kind: String) = calls.last { it.take(2).joinToString(" ") == kind }
    }

    private val cli = Cli()
    private val home = kotlin.io.path.createTempDirectory("cards-test").toFile().apply {
        File(this, "runtime").mkdirs()
        File(this, "runtime/daemon.json").writeText("""{"daemonId": "d_here", "enrolled": true}""")
    }
    private val board = Board(cli, home, addon = true).apply { pollFast() }
    private var clock = 1_000_000L
    private var ollama = false
    private val cards = Cards(cli, board, Dreamfall(cli), Titles(cli, null) { null }, { ollama }, home, addon = true) { clock }

    private fun upload(id: String) = File(home, "uploads/$id").apply { mkdirs(); File(this, "photo.jpg").writeText("jpeg") }

    @Test
    fun aCreatedCardIsOnTheBoardAtOnceAndItsTurnRunning() {
        val made = cards.create("card", obj("""{"project": "p_a", "board": "b_a", "prompt": "Fix the heater\nin the bath"}"""))
        assertEquals("c_new", made.field("id"))
        val args = cli.last("card create")
        assertTrue("--auto-title" in args && "--title" !in args, "$args")
        assertEquals("running", args[args.indexOf("--lane") + 1])
        val shown = board.snapshot().second.objs("cards").first()
        assertEquals("c_new" to "running", shown.field("id") to shown.field("runtime"))
    }

    @Test
    fun withOllamaTheCardStartsUnderItsFirstLine() {
        ollama = true
        cards.create("chat", obj("""{"project": "p_a", "prompt": "\n# Fix the   heater\nin the bath", "start": false}"""))
        val args = cli.last("chat create")
        assertEquals("Fix the heater", args[args.indexOf("--title") + 1])
        assertFalse("--board" in args)
        assertEquals("c_new", board.snapshot().second.objs("chats").first().field("id"))
    }

    @Test
    fun aCardWithoutStartWaitsInTodoAndPublishModesAreChecked() {
        cards.create("card", obj("""{"project": "p_a", "board": "b_a", "prompt": "x", "title": "Later", "start": false, "publish": "pull_request", "vault": true}"""))
        val args = cli.last("card create")
        assertEquals("todo", args[args.indexOf("--lane") + 1])
        assertEquals("Later", args[args.indexOf("--title") + 1])
        assertEquals("pull_request", args[args.indexOf("--remote-publish") + 1])
        assertTrue("--vault=true" in args)
        assertEquals("publish must be manual, pull_request or push_base", assertFailsWith<BadRequest> { Cards.publishMode("force") }.message)
    }

    @Test
    fun refusals() {
        assertEquals("project required", assertFailsWith<BadRequest> { cards.create("card", obj("""{"prompt": "x"}""")) }.message)
        assertEquals("prompt required", assertFailsWith<BadRequest> { cards.create("card", obj("""{"project": "p_a"}""")) }.message)
        assertEquals("board required", assertFailsWith<BadRequest> { cards.create("card", obj("""{"project": "p_a", "prompt": "x"}""")) }.message)
        assertEquals("message required", assertFailsWith<BadRequest> { cards.send("card", "c_a1", obj("""{"message": " "}""")) }.message)
        assertEquals("lane must be one of todo, running, review, done", assertFailsWith<BadRequest> { cards.move("c_a1", obj("""{"lane": "later"}""")) }.message)
        assertEquals("seq required", assertFailsWith<BadRequest> { cards.read("c_a1", obj("""{"seq": "1a"}""")) }.message)
        assertEquals("bad project", assertFailsWith<CliError> { cards.schedules("x; rm") }.message)
        assertEquals("id required", assertFailsWith<BadRequest> { cards.setFocus(obj("""{"id": "nope"}""")) }.message)
        assertTrue(cli.calls.none { it.take(2) in listOf(listOf("card", "create"), listOf("card", "move"), listOf("card", "read")) })
    }

    @Test
    fun attachmentsAreCheckedPassedOnAndForgotten() {
        val pic = upload("u_0123456789abcdef")
        cards.create("card", obj("""{"project": "p_a", "board": "b_a", "prompt": "x", "attachments": ["u_0123456789abcdef"]}"""))
        val args = cli.last("card create")
        assertEquals(File(pic, "photo.jpg").path, args[args.indexOf("--attach") + 1])
        assertFalse(pic.exists(), "sent once, then gone")
        fun refused(ids: String) = assertFailsWith<BadRequest> { cards.create("card", obj("""{"project": "p_a", "board": "b_a", "prompt": "x", "attachments": $ids}""")) }.message
        assertEquals("attachment expired, add it again", refused("""["u_0123456789abcdef"]"""))
        assertEquals("invalid attachment id", refused("""["../../etc"]"""))
        assertEquals("at most four attachments", refused((1..5).joinToString(",", "[", "]") { "\"u_0123456789abcde$it\"" }))
    }

    @Test
    fun aMoveShowsAtOnce() {
        cards.move("c_a1", obj("""{"lane": "done"}"""))
        assertEquals(listOf("card", "move", "--lane", "done", "c_a1"), cli.last("card move"))
        assertEquals("done", board.snapshot().second.objs("cards").first { it.field("id") == "c_a1" }.field("lane"))
    }

    @Test
    fun theFocusIsKeptForAQuarterOfAnHour() {
        assertNull(cards.focus(false).field("id").ifEmpty { null })
        cards.setFocus(obj("""{"id": "c_a1"}"""))
        assertEquals("c_a1", cards.focus(false).field("id"))
        assertEquals("c_a1", cards.focus(true).field("id"))
        assertEquals("", cards.focus(false).field("id"), "consumed")
        cards.setFocus(obj("""{"id": "c_a2"}"""))
        clock += Cards.FOCUS_TTL_MS
        assertEquals("", cards.focus(false).field("id"), "expired")
    }

    @Test
    fun homeAssistantsSummary() {
        val s = cards.haSummary()
        assertEquals(true, s["ok"].truthy())
        assertEquals(obj("""{"todo": 0, "running": 1, "review": 1, "done": 0}"""), s["lanes"])
        assertEquals(1, s.field("needs_you").toInt())
        assertEquals(listOf("Shutters"), (s["running_titles"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(listOf("Heater"), (s["review_titles"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(listOf("House"), (s["projects"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("0", s.field("questions"))
        assertTrue(s["addon"].truthy())
    }

    // ------------------------------------------------------------------ over HTTP

    private fun <T> served(block: (port: Int) -> T): T {
        val routes = routes(cli, Settings(kotlin.io.path.createTempFile().toFile(), OllamaEntity(null)) { "" }, Projects(cli, home, { null }), live = Live(board, cards))
        val server = OperatorServer(java.net.InetSocketAddress("127.0.0.1", 0), routes).start()
        return try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    private fun call(port: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port/api/$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        return status to ((if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
    }

    @Test
    fun theLiveRoutes() = served { port ->
        assertEquals(200, call(port, "GET", "ha").first)
        assertEquals(200, call(port, "POST", "focus", """{"id": "c_a1"}""").first)
        assertEquals("c_a1", obj(call(port, "GET", "focus?consume=1").second).field("id"))
        assertEquals("", obj(call(port, "GET", "focus").second).field("id"))
        val state = obj(call(port, "GET", "state").second)
        assertTrue(state["rev"] is JsonPrimitive && state.objs("cards").size == 2)
        assertEquals(404, call(port, "POST", "card/x_1/move", """{"lane": "done"}""").first)
        assertEquals(400 to "bad id", call(port, "GET", "card/x_1").let { it.first to obj(it.second).field("error") })
        assertEquals(400, call(port, "POST", "card/c_a1/move", """{"lane": 3}""").first)
        assertEquals(200, call(port, "POST", "card/c_a1/read", """{"seq": "4"}""").first)
        assertEquals(listOf("card", "read", "--response-seq", "4", "c_a1"), cli.last("card read"))
    }

    @Test
    fun theEventsStreamPushesTheFieldsAsked() = served { port ->
        val c = URI("http://127.0.0.1:$port/api/events?fields=cards,nonsense!,boards").toURL().openConnection() as HttpURLConnection
        c.readTimeout = 10_000
        assertEquals("text/event-stream", c.getHeaderField("Content-Type")?.substringBefore(";"))
        val lines = c.inputStream.bufferedReader()
        fun next(): Pair<String, JsonObject> {
            val event = lines.readLine().removePrefix("event: ")
            val data = obj(lines.readLine().removePrefix("data: "))
            assertEquals("", lines.readLine())
            return event to data
        }
        val (event, first) = next()
        assertEquals("state", event)
        assertEquals(setOf("rev", "cards", "boards"), first.keys)
        assertEquals("review", first.objs("cards").first { it.field("id") == "c_a1" }.field("lane"))
        assertEquals(200, call(port, "POST", "card/c_a1/move", """{"lane": "done"}""").first)
        val (event2, pushed) = next()
        assertEquals("state", event2)
        assertEquals("done", pushed.objs("cards").first { it.field("id") == "c_a1" }.field("lane"))
        assertTrue(pushed.field("rev").toLong() > first.field("rev").toLong())
        c.disconnect()
    }
}
