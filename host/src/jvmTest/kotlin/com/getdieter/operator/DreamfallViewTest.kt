package com.getdieter.operator

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The live view of Dreamfall boards: the kv watch, the stage cards, the state's "dreamfall" block and the trail (dreamfall_view_test.py until 0.6.431.21). */
class DreamfallViewTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun entry(key: String, value: String? = null, deleted: Boolean = false): JsonObject {
        val enc = value?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }.orEmpty()
        return obj("""{"namespace": "dreamfall", "key": "$key", "revision": "r", "deleted": $deleted, "valueJson": "$enc", "versions": [${if (value != null) """{"valueJson": "$enc"}""" else ""}]}""")
    }

    private fun frame(vararg entries: JsonObject, reset: Boolean = false, caughtUp: Boolean = false) =
        JsonObject(mapOf("entries" to JsonArray(entries.toList()), "reset" to JsonPrimitive(reset), "caughtUp" to JsonPrimitive(caughtUp)))

    private val pid = "df_0123456789"
    private val pipeText =
        """{"id": "$pid", "name": "Dreamfall", "project_id": "p_1", "created_at": "2026-10-28T00:00:00Z", "stages": [
            {"role": "enumeration", "name": "Enumeration", "board_id": "b_e", "actor": "agent"},
            {"role": "navigation", "name": "Navigation", "board_id": "b_n", "actor": "agent"},
            {"role": "implementation", "name": "Implementation", "board_id": "b_i", "actor": "human"}]}"""
    private val pipe = obj(pipeText)

    private var changes = 0
    private val view = DreamfallView(onChange = { changes++ })

    private fun card(id: String, board: String, lane: String, extra: String = "") = obj("""{"id": "$id", "board_id": "$board", "lane": "$lane"$extra}""")

    @Test
    fun aSnapshotIsPublishedOnlyWhenCaughtUp() {
        view.frame(frame(reset = true))
        view.frame(frame(entry("p.$pid", pipeText)))
        assertEquals(emptyList(), view.pipelines())
        view.frame(frame(entry("c.c_1", """{"stage": "enumeration", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}]}"""), caughtUp = true))
        assertEquals(listOf(pid), view.pipelines().map { it.field("id") })
        assertTrue(changes >= 1)
        view.frame(frame(entry("c.c_1", deleted = true)))
        assertNull(view.record("c.c_1"))
    }

    @Test
    fun decorateMergesStageCardsAndRecords() {
        view.frame(frame(reset = true))
        view.frame(
            frame(
                entry("p.$pid", pipeText),
                entry("c.c_2", """{"stage": "navigation", "source": "c_1", "verdict": {"kind": "promote", "rationale": "good", "at": "2026-10-28T05:00:00Z"}, "events": [{"id": "e", "kind": "created", "at": "2026-10-28T05:00:00Z"}]}"""),
                entry("r.c_run", """{"pipeline": "$pid", "stage": "navigation", "started_at": "2026-10-28T05:00:00Z", "status": "done"}"""),
                caughtUp = true,
            ),
        )
        view.seedLane("b_n", "todo", listOf(card("c_2", "b_n", "todo", """, "updated_at": "2026-10-28T05:00:00Z"""")))
        val out = view.decorate(obj("""{"ok": true, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "review", "updated_at": "2026-10-28T04:00:00Z", "orphaned": false}, {"id": "c_other", "board_id": "b_x", "lane": "todo"}]}"""))
        val cards = out.objs("cards").associateBy { it.field("id") }
        assertEquals("todo", cards.getValue("c_2").field("lane"), "the newer copy wins")
        assertTrue("c_other" in cards)
        val df = out.obj("dreamfall")!!
        assertEquals("promote", df.obj("records")!!.obj("c_2")!!.obj("verdict")!!.field("kind"))
        assertEquals(JsonNull, df.obj("records")!!.obj("c_2")!!["source_verdict"], "its source c_1 has no record here")
        assertEquals("done", df.obj("runs")!!.objs(pid).first().field("status"))
        assertEquals(JsonPrimitive(true), df["ready"])
        assertEquals(Dreamfall.DEFAULT_NAVIGATION_LOGIC, df.obj("defaults")!!.field("navigation_logic"))
        assertEquals(obj("""{"ok": false}"""), view.decorate(obj("""{"ok": false}""")), "a failed state stays as it is")
    }

    @Test
    fun applyLocalMovesAndAddsWithoutListing() {
        view.seed(mapOf("p.$pid" to pipe))
        view.seedLane("b_n", "todo", listOf(card("c_2", "b_n", "todo", """, "updated_at": "2026-10-28T05:00:00Z"""")))
        view.seedLane("b_n", "done", emptyList())
        view.seedLane("b_i", "todo", emptyList())
        assertEquals(listOf("c_2"), view.cardsOf("b_n")!!.map { it.field("id") })
        assertNull(view.cardsOf("b_x"))
        val new = card("c_3", "b_i", "todo", """, "updated_at": "2026-10-28T19:00:00Z"""")
        assertTrue(view.applyLocal(mapOf("c_2" to "done"), listOf(new)))
        assertEquals(emptyList(), view.lane("b_n", "todo"))
        assertEquals("done", view.lane("b_n", "done")!!.single().field("lane"))
        assertEquals(listOf(new), view.lane("b_i", "todo"))
        assertTrue(changes >= 1)
        val cards = view.decorate(obj("""{"ok": true, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "todo", "updated_at": "2026-10-28T05:00:00Z"}]}""")).objs("cards").associateBy { it.field("id") }
        assertEquals("done", cards.getValue("c_2").field("lane"), "the local move wins over the older state copy")
        assertTrue("c_3" in cards)
        assertFalse(view.applyLocal(mapOf("c_2" to "done")), "already there: nothing to do")
    }

    @Test
    fun feedTakesTheStageLanesFromTheProjectList() {
        view.seed(mapOf("p.$pid" to pipe))
        view.seedLane("b_gone", "todo", emptyList())
        val old = (0 until 80).map { i -> card("c_d$i", "b_n", "done", """, "phase_changed_at": "2026-10-%02dT%02d:00:00Z"""".format(1 + i / 24, i % 24)) }
        val cards = listOf(card("c_1", "b_e", "todo"), card("c_2", "b_n", "running"), card("c_3", "b_x", "todo")) + old
        assertTrue(view.feed("p_1", cards))
        assertEquals(listOf("c_1"), view.cardsOf("b_e")!!.map { it.field("id") })
        assertEquals(emptyList(), view.cardsOf("b_i"), "read, empty")
        assertFalse("b_x" in view.heldBoards(), "not a stage board")
        assertFalse("b_gone" in view.heldBoards(), "no longer a stage board")
        val done = view.lane("b_n", "done")!!
        assertEquals(DreamfallView.LANE_TAIL + 20, done.size)
        assertEquals("c_d79", done.last().field("id"), "the newest stay")
        assertFalse(view.feed("p_1", cards), "nothing new")
        assertFalse(view.feed("p_other", cards), "another project's list")
        assertEquals(setOf("p_1"), view.stageProjects())
    }

    @Test
    fun putCardsReplacesMovesAndDrops() {
        view.seed(mapOf("p.$pid" to pipe))
        view.feed("p_1", listOf(card("c_1", "b_e", "todo"), card("c_2", "b_n", "todo")))
        val before = changes
        assertTrue(view.putCards(listOf(card("c_1", "b_e", "review"), card("c_9", "b_x", "todo")), listOf("c_2", "c_unknown")))
        assertEquals(listOf("c_1" to "review"), view.cardsOf("b_e")!!.map { it.field("id") to it.field("lane") })
        assertEquals(emptyList(), view.cardsOf("b_n"))
        assertFalse("b_x" in view.heldBoards())
        assertTrue(changes > before)
        assertFalse(view.putCards(listOf(card("c_1", "b_e", "review")), listOf("c_unknown")), "the same copy: no change")
    }

    @Test
    fun aNewPipelineAsksForItsBoards() {
        var asked = 0
        val v = DreamfallView(onBoards = { asked++ })
        v.frame(frame(reset = true))
        v.frame(frame(entry("c.c_1", """{"stage": "enumeration"}"""), caughtUp = true))
        assertEquals(0, asked)
        v.frame(frame(entry("p.$pid", pipeText)))
        assertEquals(1, asked)
        v.frame(frame(entry("c.c_2", """{"stage": "navigation"}""")))
        assertEquals(1, asked, "an idea's record: the boards stay")
    }

    @Test
    fun knownCopiesNameTheirSources() {
        view.seed(mapOf("p.$pid" to pipe))
        view.seedLane("b_n", "todo", listOf(card("c_2", "b_n", "todo", """, "initial_prompt": "brief\n<!-- dreamfall-source: c_1 -->\n"""")))
        view.seedLane("b_i", "todo", listOf(card("c_3", "b_i", "todo", """, "initial_prompt": "brief\n<!-- dreamfall-source: c_1 -->\nmore\n<!-- dreamfall-source: c_2 -->\n"""")))
        view.seedLane("b_i", "done", emptyList())
        assertEquals(mapOf("b_n" to mapOf("c_1" to "c_2"), "b_i" to mapOf("c_1" to "c_3", "c_2" to "c_3")), view.knownCopies())
    }

    @Test
    fun ideasAreCountedOnceNotPerCopy() {
        view.seed(
            mapOf(
                "p.$pid" to pipe,
                "c.c_1" to obj("""{"pipeline": "$pid", "stage": "enumeration", "transferred_to": "c_2"}"""),
                "c.c_2" to obj("""{"pipeline": "$pid", "stage": "navigation", "source": "c_1", "transferred_to": "c_3"}"""),
                "c.c_3" to obj("""{"pipeline": "$pid", "stage": "implementation", "source": "c_2"}"""),
                "c.c_4" to obj("""{"pipeline": "$pid", "stage": "enumeration"}"""),
                "c.c_5" to obj("""{"pipeline": "$pid", "stage": "implementation", "created_by": {"actor": "human"}}"""),
                "c.c_9" to obj("""{"pipeline": "df_other00000", "stage": "enumeration"}"""),
            ),
        )
        assertEquals(obj("""{"$pid": 3, "df_other00000": 1}"""), view.decorate(obj("""{"ok": true, "cards": []}""")).obj("dreamfall")!!.obj("ideas"))
    }

    @Test
    fun theDetailHasTheChainAndTheTrail() {
        view.seed(
            mapOf(
                "c.c_1" to obj("""{"stage": "enumeration", "transferred_to": "c_2", "verdict": {"kind": "promote", "rationale": "good", "at": "2026-10-28T05:00:00Z"},
                    "events": [{"id": "a", "kind": "created", "at": "2026-10-28T02:00:00Z"}, {"id": "b", "kind": "transferred", "to": "c_2", "at": "2026-10-28T05:00:00Z"}]}"""),
                "c.c_2" to obj("""{"stage": "navigation", "source": "c_1", "transferred_to": "c_3", "events": [{"id": "c", "kind": "created", "from": "c_1", "at": "2026-10-28T05:00:00Z"}]}"""),
                "c.c_3" to obj("""{"stage": "implementation", "source": "c_2", "events": [{"id": "d", "kind": "created", "from": "c_2", "at": "2026-10-28T19:00:00Z"}]}"""),
            ),
        )
        val out = view.decorate(obj("""{"ok": true, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "todo"}]}"""))
        assertEquals("good", out.obj("dreamfall")!!.obj("records")!!.obj("c_2")!!.obj("source_verdict")!!.field("rationale"), "the copy shows why it came")
        val d = view.detail("c_2", listOf(obj("""{"id": "c_2", "title": "Idea", "initial_prompt": "brief"}""")))
        assertEquals(listOf("c_1", "c_2", "c_3"), d.objs("chain").map { it.field("card") })
        assertTrue(d.objs("chain")[1]["self"].truthy())
        assertEquals(listOf("a", "b"), d.objs("events").map { it.field("id") }, "a copy's created duplicates its source's transferred")
        assertEquals("brief", d.field("brief"))
    }

    @Test
    fun theStageCardsAreTheOpenOnesWithTheStartOfTheirBrief() {
        view.seed(mapOf("p.$pid" to pipe))
        val state = view.decorate(
            obj(
                """{"ok": true, "cards": [{"id": "c_1", "board_id": "b_e", "lane": "todo", "initial_prompt": "${"x".repeat(1500)}"},
                    {"id": "c_2", "board_id": "b_e", "lane": "done", "archived": true}, {"id": "c_3", "board_id": "b_other", "lane": "todo"}]}""",
            ),
        )
        val cards = DreamfallView.stageCards(state) as JsonArray
        assertEquals(listOf("c_1"), cards.map { (it as JsonObject).field("id") })
        assertEquals(1200, (cards.single() as JsonObject).field("initial_prompt").length)
    }
}
