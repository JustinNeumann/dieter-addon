package com.getdieter.operator

import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The board's polls against a fake `dieter` CLI: which calls a change costs (bridge_poll_test.py and state_test.py until 0.6.431.21). */
class BoardTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val pipe = obj(
        """{"id": "df_0123456789", "name": "Dreamfall", "project_id": "p_df", "stages": [{"role": "enumeration", "board_id": "b_e"},
            {"role": "navigation", "board_id": "b_n"}, {"role": "implementation", "board_id": "b_i"}]}""",
    )

    private fun card(id: String, pid: String, board: String?, lane: String? = "todo", owner: String = "d_here", vararg extra: Pair<String, Any?>) =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id), "title" to JsonPrimitive(id), "project_id" to JsonPrimitive(pid), "board_id" to json(board),
                "lane" to json(lane), "owner_daemon_id" to JsonPrimitive(owner), "runtime" to JsonPrimitive("idle"),
                "scope" to JsonPrimitive("board"), "updated_at" to JsonPrimitive("2026-10-29T01:00:00Z"),
            ),
        ).updated(*extra)

    /** `card show`: camelCase under "card" (and the response counter as a string). */
    private fun shown(c: JsonObject): JsonObject {
        val camel = linkedMapOf(
            "id" to c["id"]!!, "title" to c["title"]!!, "projectId" to c["project_id"]!!, "boardId" to c["board_id"]!!, "lane" to c["lane"]!!,
            "ownerDaemonId" to c["owner_daemon_id"]!!, "runtime" to c["runtime"]!!, "scope" to c["scope"]!!, "updatedAt" to c["updated_at"]!!,
        )
        if (c["archived"].truthy()) camel["archived"] = JsonPrimitive(true)
        return JsonObject(mapOf("card" to JsonObject(camel), "project" to JsonObject(emptyMap()), "board" to JsonObject(emptyMap())))
    }

    private inner class Cli : Dieter {
        val calls = CopyOnWriteArrayList<List<String>>()
        val projects = listOf(obj("""{"id": "p_a", "name": "a"}"""), obj("""{"id": "p_df", "name": "df"}"""))
        val cards = linkedMapOf(
            "p_a" to mutableListOf(card("c_a1", "p_a", "b_a"), card("c_a2", "p_a", "b_a", owner = "d_pc")),
            "p_df" to mutableListOf(card("c_e1", "p_df", "b_e"), card("c_n1", "p_df", "b_n", "review"), card("c_x", "p_df", "b_other")),
        )
        val chats = mutableListOf(card("c_chat", "p_a", null, null, "d_here", "scope" to "chat"))
        /** set: the next `project list` waits for [release] after counting down [listing] */
        @Volatile var hold = false
        val listing = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun set(pid: String, i: Int, vararg fields: Pair<String, Any?>) {
            cards.getValue(pid)[i] = cards.getValue(pid)[i].updated(*fields)
        }

        override fun run(args: List<String>, timeout: Duration): JsonElement? {
            calls += args
            return when (args.take(2)) {
                listOf("status", "--format") -> obj("""{"health": {"status": "ok"}}""")
                listOf("project", "list") -> {
                    if (hold) {
                        hold = false
                        listing.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    JsonObject(mapOf("projects" to JsonArray(projects)))
                }
                listOf("board", "list") -> JsonObject(mapOf("boards" to JsonArray(listOf("p_a" to "b_a", "p_df" to "b_e", "p_df" to "b_n", "p_df" to "b_i").map { (p, b) -> obj("""{"id": "$b", "name": "$b", "projectId": "$p"}""") })))
                listOf("chat", "list") -> JsonArray(chats.toList())
                listOf("card", "list") -> JsonArray(cards.getValue(args[args.indexOf("--project") + 1]).take(args[args.indexOf("--limit") + 1].toInt()))
                listOf("card", "show") -> {
                    val c = (cards.values.flatten() + chats).firstOrNull { it.field("id") == args[2] }
                        ?: throw CliError("rpc error: code = NotFound desc = card \"${args[2]}\": not found")
                    if (c.field("owner_daemon_id") != "d_here") throw CliError("conversation belongs to another machine; target its owner: ${c.field("owner_daemon_id")}")
                    shown(c)
                }
                else -> throw IllegalStateException("unexpected $args")
            }
        }

        fun kinds(): List<String> = calls.map { it.take(2).joinToString(" ") }.also { calls.clear() }
    }

    private val cli = Cli()
    private val home = kotlin.io.path.createTempDirectory("board-test").toFile().apply {
        File(this, "runtime").mkdirs()
        File(this, "runtime/daemon.json").writeText("""{"daemonId": "d_here", "enrolled": true}""")
    }
    private val board = Board(cli, home, addon = false).apply {
        dreamfall.seed(mapOf("p.df_0123456789" to pipe))
        pollFast()
        cli.kinds()
    }

    private fun cards() = board.raw().objs("cards").associateBy { it.field("id") }

    private fun ids(key: String = "cards") = board.snapshot().second.objs(key).map { it.field("id") }

    // ------------------------------------------------------------------ polls

    @Test
    fun aFullPollListsADreamfallProjectWholeAndFeedsItsStageBoards() {
        board.refresh()
        board.pollFast()
        val lists = cli.calls.filter { it.take(2) == listOf("card", "list") }
        assertEquals(mapOf("p_a" to "200", "p_df" to "2000"), lists.associate { it[it.indexOf("--project") + 1] to it[it.indexOf("--limit") + 1] })
        assertEquals(listOf("board list", "card list", "card list", "chat list", "project list", "status --format"), cli.kinds().sorted())
        assertEquals(listOf("c_e1"), board.dreamfall.cardsOf("b_e")!!.map { it.field("id") })
        assertEquals(listOf("c_n1"), board.dreamfall.lane("b_n", "review")!!.map { it.field("id") })
        assertFalse("b_other" in board.dreamfall.heldBoards(), "not a stage board")
    }

    @Test
    fun theStateKeepsTheCardLimitOfAWholeListedProject() {
        cli.cards.getValue("p_df") += (0 until Board.CARD_LIMIT + 50).map { card("c_d%03d".format(it), "p_df", "b_e") }
        board.refresh()
        board.pollFast()
        assertEquals(Board.CARD_LIMIT, board.raw().objs("cards").count { it.field("project_id") == "p_df" })
        assertEquals(Board.CARD_LIMIT + 51, board.dreamfall.cardsOf("b_e")!!.size, "the stage board gets all of them")
    }

    @Test
    fun aChangeOfAnOwnCardIsOneCardShow() {
        cli.set("p_a", 0, "lane" to "running")
        board.refresh(ids = listOf("c_a1"))
        assertTrue(board.cheap())
        val listed = board.pollFast()
        assertEquals(listOf("card show"), cli.kinds())
        assertFalse(listed)
        assertEquals("running", cards().getValue("c_a1").field("lane"))
        assertEquals(setOf("c_a1", "c_a2", "c_e1", "c_n1", "c_x"), cards().keys)
    }

    @Test
    fun aChangeOfAnotherMachinesCardListsItsProjectOnly() {
        cli.set("p_a", 1, "title" to "renamed on the PC")
        board.refresh(ids = listOf("c_a2"))
        assertFalse(board.cheap())
        assertTrue(board.pollFast())
        assertEquals(listOf("card list"), cli.kinds())
        assertEquals("renamed on the PC", cards().getValue("c_a2").field("title"))
    }

    @Test
    fun aNewCardIsShownAndReachesDreamfall() {
        cli.cards.getValue("p_df") += card("c_e2", "p_df", "b_e")
        board.refresh(ids = listOf("c_e2"))
        board.pollFast()
        assertEquals(listOf("card show"), cli.kinds())
        assertTrue("c_e2" in cards())
        assertEquals(listOf("c_e1", "c_e2"), board.dreamfall.cardsOf("b_e")!!.map { it.field("id") }.sorted())
    }

    @Test
    fun aNewCardOfAnotherMachineListsEverything() {
        cli.cards.getValue("p_a") += card("c_pc", "p_a", "b_a", owner = "d_pc")
        board.refresh(ids = listOf("c_pc"))
        board.pollFast()
        assertTrue("project list" in cli.kinds())
        assertTrue("c_pc" in cards())
    }

    @Test
    fun archivedAndDeletedCardsLeaveTheStateAndDreamfall() {
        cli.set("p_df", 0, "archived" to true)
        cli.cards.getValue("p_a").removeAt(0)
        board.patch("c_e1", "archived" to true)
        board.refresh(ids = listOf("c_a1"))
        board.pollFast()
        assertEquals(listOf("card show", "card show"), cli.kinds())
        assertFalse("c_e1" in cards())
        assertFalse("c_a1" in cards())
        assertEquals(emptyList(), board.dreamfall.cardsOf("b_e"))
        assertEquals(emptySet(), board.pendingPatches(), "the re-read covered the patch")
    }

    @Test
    fun aPatchStaysUntilAPollReadItsCard() {
        board.patch("c_a1", "lane" to "done")   // e.g. `card move` answered, the daemon not yet
        board.wantOnly(setOf("c_e1"))            // a poll that re-reads another card only
        board.pollFast()
        assertTrue("c_a1" in board.pendingPatches())
        assertEquals("done", cards().getValue("c_a1").field("lane"))
        cli.set("p_a", 0, "lane" to "done")
        board.refresh(ids = listOf("c_a1"))
        board.pollFast()
        assertFalse("c_a1" in board.pendingPatches())
    }

    @Test
    fun tooManyChangesOrAnOldFullPollListEverything() {
        board.refresh(ids = (0..Board.SHOW_MAX).map { "c_n$it" })
        board.pollFast()
        assertTrue("project list" in cli.kinds())
        board.ageFullPoll(Board.IDLE_POLL_MS + 1000)
        board.refresh(ids = listOf("c_a1"))
        board.pollFast()
        assertTrue("project list" in cli.kinds())
    }

    @Test
    fun aChatChange() {
        cli.chats[0] = cli.chats[0].updated("title" to "new chat title")
        board.refresh(ids = listOf("c_chat"))
        board.pollFast()
        assertEquals(listOf("card show"), cli.kinds())
        val chat = board.raw().objs("chats").single()
        assertEquals("new chat title", chat.field("title"))
        assertEquals("chat", chat.field("kind"))
    }

    // ------------------------------------------------------------------ cards created here

    /** What the create handler puts into the state: the `card create` answer through normCard. */
    private fun created(id: String, kind: String = "card", runtime: String? = null): JsonObject {
        val raw = obj(
            """{"id": "$id", "title": "$id", "projectId": "p_a", "boardId": ${if (kind == "card") "\"b_a\"" else "null"},
                "lane": ${if (kind == "card") "\"running\"" else "null"}, "ownerDaemonId": "d_here", "runtime": "idle", "scope": "${if (kind == "card") "board" else "chat"}"}""",
        )
        return Board.normCard(raw, kind).let { if (runtime != null) it.updated("runtime" to runtime) else it }
    }

    @Test
    fun aNewCardIsShownAtOnceWithoutWaitingForAPoll() {
        val rev = board.rev
        board.add(created("c_new", runtime = "running"))
        assertEquals("c_new", ids().first())
        assertTrue(board.rev > rev)
        assertEquals(emptyList(), cli.kinds(), "no CLI call before the page has it")
        assertFalse("c_new" in ids("chats"))
    }

    @Test
    fun theNextPollReadsOnlyTheNewCard() {
        cli.cards.getValue("p_a") += card("c_new", "p_a", "b_a", "running", "d_here", "title" to "Daemon's title")
        board.add(created("c_new", runtime = "running"))
        assertTrue(board.cheap())
        board.pollFast()
        assertEquals(listOf("card show"), cli.kinds())
        assertEquals("Daemon's title", board.snapshot().second.objs("cards").first { it.field("id") == "c_new" }.field("title"))
        assertEquals(emptySet(), board.pendingAdded())
    }

    @Test
    fun aPollThatStartedBeforeKeepsIt() {
        board.refresh()
        cli.hold = true
        val poll = Thread.ofVirtual().start { board.pollFast() }
        assertTrue(cli.listing.await(5, TimeUnit.SECONDS))   // the daemon answered before the create
        board.add(created("c_new"))
        cli.release.countDown()
        poll.join(5000)
        assertTrue("c_new" in ids())
        assertTrue("c_new" in board.pendingAdded())
    }

    @Test
    fun aLaterPollThatDidNotReadItKeepsIt() {
        board.add(created("c_new"))
        cli.set("p_a", 0, "lane" to "review")
        board.wantOnly(setOf("c_a1"))   // the re-read of c_new is still to come
        board.pollFast()
        assertEquals(listOf("card show"), cli.kinds())
        assertTrue("c_new" in ids())
        assertTrue("c_new" in board.pendingAdded())
    }

    @Test
    fun goneOnceALaterPollReadItAndTheDaemonHasNoSuchCard() {
        board.add(created("c_new"))
        board.pollFast()   // `card show c_new`: not found (e.g. deleted meanwhile)
        assertFalse("c_new" in ids())
        assertEquals(emptySet(), board.pendingAdded())
    }

    @Test
    fun aChatGoesToTheChatsAndNothingIsShownTwice() {
        board.add(created("c_chat2", "chat", "running"))
        assertEquals("c_chat2", ids("chats").first())
        assertFalse("c_chat2" in ids())
        board.add(created("c_a1", runtime = "running"))
        assertEquals(1, ids().count { it == "c_a1" })
    }

    @Test
    fun patchesStillWaitForTheirCard() {
        board.add(created("c_new"))
        board.patch("c_a1", "lane" to "done")
        board.pollFast()
        assertEquals("todo", board.snapshot().second.objs("cards").first { it.field("id") == "c_a1" }.field("lane"), "read from the unchanged daemon: the patch ended")
        assertFalse("c_a1" in board.pendingPatches())
    }

    @Test
    fun theStateCarriesTheQuestionsAndDreamfall() {
        val (_, state) = board.snapshot()
        assertEquals(JsonArray(emptyList()), state["questions"])
        assertEquals(listOf("df_0123456789"), state.obj("dreamfall")!!.objs("pipelines").map { it.field("id") })
        assertEquals(false, state["addon"].truthy())
        assertNull(state["mail"], "Dieter Mail served the classic page only")
    }

    // ------------------------------------------------------------------ change frames

    private fun rec(id: String, value: String, kind: String = "item") =
        obj("""{"kind": "$kind", "id": "$id", "versions": [{"valueJson": "${Base64.getEncoder().encodeToString(value.toByteArray())}"}]}""")

    private fun frame(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    @Test
    fun ownedCardTokenAndTimeUpdatesAreQuiet() {
        val w = ChangeWatch.Seen()
        val own = obj("""{"id": "c_1", "workspace": {"state": "ready"}, "tokenUsage": {"totalTokens": "1"}, "updatedAt": "t1"}""")
        assertEquals(false to setOf("c_1"), w.classify(frame("ownedCards" to listOf(own))))
        assertEquals(false to emptySet(), w.classify(frame("ownedCards" to listOf(own.updated("tokenUsage" to obj("""{"totalTokens": "2"}"""), "updatedAt" to "t2")))))
        assertEquals(false to setOf("c_1"), w.classify(frame("ownedCards" to listOf(own.updated("workspace" to obj("""{"state": "dirty"}"""))))))
    }

    @Test
    fun aSummarysActivityTimeIsQuiet() {
        val w = ChangeWatch.Seen()
        w.classify(frame("records" to listOf(rec("c_1.summary", """{"runtime": "running", "lastActivityAt": "t1"}"""))))
        assertEquals(false to emptySet(), w.classify(frame("records" to listOf(rec("c_1.summary", """{"runtime": "running", "lastActivityAt": "t2"}""")))))
        assertEquals(false to setOf("c_1"), w.classify(frame("records" to listOf(rec("c_1.summary", """{"runtime": "idle", "lastActivityAt": "t3"}""")))))
    }

    @Test
    fun cardRecordsNameTheCardStructureListsEverything() {
        val w = ChangeWatch.Seen()
        assertEquals(false to setOf("c_2"), w.classify(frame("records" to listOf(rec("c_2.placement", """{"lane": "review"}""")))))
        assertEquals(true to emptySet(), w.classify(frame("records" to listOf(rec("b_1.name", "\"Main\"", "board")))))
        assertEquals(true to emptySet(), w.classify(frame("records" to listOf(rec("p_1.name", "\"x\"", "project")))))
        assertEquals(true to emptySet(), w.classify(frame("resetRecords" to true)))
        // the Dreamfall records (kv) and schedules are not on the board
        assertEquals(false to emptySet(), w.classify(frame("records" to listOf(rec("c.c_1", """{"stage": "enumeration"}""", "kv.dreamfall"), rec("sch_1", "{}", "schedule")))))
    }

    @Test
    fun checkoutsAndActivityStatus() {
        val w = ChangeWatch.Seen()
        val co = obj("""{"id": "co_1", "projectId": "p_1", "path": "/x"}""")
        assertEquals(true to emptySet(), w.classify(frame("ownedCheckouts" to listOf(co))))
        assertEquals(false to emptySet(), w.classify(frame("ownedCheckouts" to listOf(co))))
        assertEquals(false to setOf("c_3"), w.classify(frame("activities" to listOf(obj("""{"cardId": "c_3", "status": "running"}""")))))
        assertEquals(false to emptySet(), w.classify(frame("activities" to listOf(obj("""{"cardId": "c_3", "status": "running", "messages": [1]}""")))))
    }

    @Test
    fun aListedAndAShownCardAgree() {
        val listed = Board.normCard(obj("""{"id": "c_1", "response_seq": 5, "token_usage": {"input_tokens": 3, "output_tokens": 4, "total_tokens": 7}}"""))
        val show = Board.normCard(obj("""{"id": "c_1", "responseSeq": "5", "tokenUsage": {"inputTokens": "3", "outputTokens": "4", "totalTokens": "7"}}"""))
        assertEquals(obj("""{"inputTokens": 3, "outputTokens": 4, "totalTokens": 7}"""), listed["tokens"])
        assertEquals(listed, show)
        assertEquals(JsonNull, Board.normCard(obj("""{"id": "c_2"}"""))["tokens"])
        assertEquals("(ohne Titel)", Board.normCard(obj("""{"id": "c_2"}""")).field("title"))
    }
}
