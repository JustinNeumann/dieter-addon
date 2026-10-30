package com.getdieter.operator

import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HousekeepingTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val old = "2026-11-01T10:00:00.123456789Z"   // 19 days before now
    private val recent = "2026-11-10T10:00:00Z"           // 10 days before now

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun card(lane: String = "done", at: String = old, extra: String = "", runtime: String = "idle") =
        obj("""{"lane": "$lane", "lastActivityAt": "$at", "phaseChangedAt": "$at", "runtime": "$runtime", "title": "t"$extra}""")

    private fun frames(status: String, error: String? = null): String {
        val op = { s: String -> """{"operation": {"id": "gitop_1", "status": "$s"${if (error != null && s == status) ", \"error\": \"$error\"" else ""}}}""" }
        return listOf("queued", "running", status).joinToString("\n") { op(it) } + "\n"
    }

    /** Cards by id, cleanup outcomes by id; records every removal. */
    private inner class Fake(
        val cards: Map<String, JsonObject>,
        val cleanup: Map<String, Pair<String, String>> = emptyMap(),
        val ws: List<String> = cards.keys.map { """{"cardId": "$it", "mode": "worktree", "state": "ready", "branch": "dieter/$it", "headSha": "h1"}""" },
    ) : Dieter {
        val calls = mutableListOf<Pair<String, String>>()
        var failOn: String? = null

        override fun run(args: List<String>, timeout: Duration): JsonElement? = when (args.take(2)) {
            listOf("project", "list") -> obj("""{"projects": [{"id": "p_1", "path": "/p"}]}""")
            listOf("workspace", "list") -> obj("""{"workspaces": [${ws.joinToString()}]}""")
            listOf("card", "show") -> {
                if (args[2] == failOn) throw CliError("boom")
                JsonObject(mapOf("card" to cards.getValue(args[2])))
            }
            listOf("workspace", "run") -> {
                val kind = args[args.indexOf("--kind") + 1]
                calls += kind to args.last()
                val (status, error) = if (kind == "cleanup") cleanup[args.last()] ?: ("succeeded" to "") else "succeeded" to ""
                JsonPrimitive(frames(status, error.ifEmpty { null }))   // several JSON lines: the CLI's text
            }
            else -> throw IllegalStateException("unexpected $args")
        }
    }

    private val logs = mutableListOf<String>()

    private fun sweeper(fake: Fake, merged: Boolean = false, days: Int = 14, boards: () -> Set<String> = { emptySet() }) =
        Sweeper(fake, days, mergedHead = { _, _, _ -> merged }, now = { now }, implementationBoards = boards, log = { logs += it })

    private val notMerged = "failed" to "workspace branch is not merged into its base"

    @Test
    fun timesAndIdleness() {
        assertEquals(Sweeper.parseTime("2026-10-29T05:38:37Z"), Sweeper.parseTime("2026-10-29T05:38:37.999999999Z"))
        assertNull(Sweeper.parseTime(null))
        assertNull(Sweeper.parseTime("soon"))
        assertNull(Sweeper.idleSince(card("review")))
        assertEquals(Sweeper.parseTime(old), Sweeper.idleSince(card("done", old)))
        // archived recently: the clock starts there; a plain edit does not count
        assertEquals(Sweeper.parseTime(recent), Sweeper.idleSince(card("review", old, """, "archived": true, "updatedAt": "$recent"""")))
        assertEquals(Sweeper.parseTime(old), Sweeper.idleSince(card("done", old, """, "updatedAt": "$recent"""")))
        assertEquals(("failed" to "conflicts"), Sweeper.operationResult(frames("failed", "conflicts")))
        assertEquals("" to "", Sweeper.operationResult("not json"))
    }

    @Test
    fun anOldDoneCardIsCleanedUpAndTheRestIsKept() {
        val f = Fake(mapOf("c_a" to card()))
        assertEquals(mapOf("c_a" to "cleanup"), sweeper(f).sweep())
        assertEquals(listOf("cleanup" to "c_a"), f.calls)
        val kept = Fake(mapOf("c_recent" to card(at = recent), "c_review" to card("review"), "c_running" to card(runtime = "running"), "c_todo" to card("todo")))
        assertEquals(emptyMap(), sweeper(kept).sweep())
        assertEquals(emptyList(), kept.calls)
        assertEquals(mapOf("c_a" to "cleanup"), sweeper(Fake(mapOf("c_a" to card("review", extra = """, "archived": true, "updatedAt": "$old"""")))).sweep())
        assertEquals(mapOf("c_a" to "cleanup"), sweeper(Fake(mapOf("c_a" to card(at = recent))), days = 5).sweep())
        val project = Fake(mapOf("c_a" to card()), ws = listOf("""{"cardId": "c_a", "mode": "project", "state": "ready"}"""))
        assertEquals(emptyMap(), sweeper(project).sweep())
    }

    @Test
    fun startedDreamfallImplementationCardsCountInAnyLane() {
        val impl = """, "boardId": "b_impl""""
        val cards = mapOf(
            "c_rev" to card("review", extra = impl), "c_run" to card("running", extra = impl), "c_todo" to card("todo", extra = impl),
            "c_recent" to card("review", recent, impl), "c_busy" to card("running", extra = impl, runtime = "running"),
            "c_other" to card("review", extra = """, "boardId": "b_other""""),
        )
        assertEquals(mapOf("c_rev" to "cleanup", "c_run" to "cleanup"), sweeper(Fake(cards), boards = { setOf("b_impl") }).sweep())
        assertEquals(emptyMap(), sweeper(Fake(cards)).sweep(), "without the Dreamfall boards only Done and archived count")
        val unmerged = Fake(mapOf("c_a" to card("review", extra = impl)), cleanup = mapOf("c_a" to notMerged))
        assertEquals(emptyMap(), sweeper(unmerged, boards = { setOf("b_impl") }).sweep())
        assertEquals(listOf("cleanup" to "c_a"), unmerged.calls)
        // the Dreamfall lookup failing keeps the rest working
        val f = Fake(mapOf("c_a" to card(), "c_b" to card("review", extra = impl)))
        assertEquals(mapOf("c_a" to "cleanup"), sweeper(f, boards = { throw IllegalStateException("kv down") }).sweep())
    }

    @Test
    fun aSquashMergedPullRequestIsDiscardedUnmergedWorkNever() {
        val squashed = Fake(mapOf("c_a" to card()), cleanup = mapOf("c_a" to notMerged))
        assertEquals(mapOf("c_a" to "discard"), sweeper(squashed, merged = true).sweep())
        assertEquals(listOf("cleanup" to "c_a", "discard" to "c_a"), squashed.calls)
        val unmerged = Fake(mapOf("c_a" to card()), cleanup = mapOf("c_a" to notMerged))
        val s = sweeper(unmerged)
        assertEquals(emptyMap(), s.sweep())
        s.sweep()
        assertEquals(listOf("cleanup" to "c_a", "cleanup" to "c_a"), unmerged.calls)
        assertEquals(1, logs.count { "kept" in it }, "logged once, not every hour")
        val conflicts = Fake(mapOf("c_a" to card()), cleanup = mapOf("c_a" to ("failed" to "workspace has conflicts")))
        assertEquals(emptyMap(), sweeper(conflicts, merged = true).sweep())
        assertEquals(listOf("cleanup" to "c_a"), conflicts.calls, "other failures do not fall back to discard")
    }

    @Test
    fun oneFailureDoesNotStopTheOthers() {
        val f = Fake(mapOf("c_a" to card(), "c_b" to card())).apply { failOn = "c_a" }
        assertEquals(mapOf("c_b" to "cleanup"), sweeper(f).sweep())
    }

    @Test
    fun theLoopSweepsWithTheRetentionAndWakesOnAChange() {
        val f = Fake(mapOf("c_a" to card(at = recent)))
        val days = AtomicInteger(0)
        val swept = LinkedBlockingQueue<Int>()
        val loop = Housekeeping(Sweeper(f, 14, now = { now }, log = { logs += it })) { days.get().also { swept.add(it) } }
        Thread.ofVirtual().start { loop.loop(firstDelayMs = 0, periodMs = 60_000) }
        assertEquals(0, swept.poll(5, TimeUnit.SECONDS))
        assertEquals(emptyList(), f.calls, "0 days keeps everything")
        days.set(5)
        loop.wake()
        assertEquals(5, swept.poll(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(listOf("cleanup" to "c_a"), f.calls)
    }
}
