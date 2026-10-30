package com.getdieter.operator

import java.io.File
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Housekeeping of the work folders (`$DIETER_HOME/worktrees/PROJECT/CARD`) the daemon makes for worktree cards
 * (bridge/housekeeping.py until 0.6.431.20).
 *
 * The daemon never removes one by itself. This sweep removes the folder of a card that has been in Done (or archived)
 * and untouched for `days` days, with the daemon's own operations, so the Dieter CLI stays as it is. A card on a
 * Dreamfall Implementation board counts as soon as it has been started (any lane but Todo): nobody has to move those
 * to Done, they wait in Review or Running for good.
 *
 * 1. `workspace run --kind cleanup`: removes the folder and the branch; the daemon refuses unless the branch is merged
 *    into its base (a fast-forward or merge commit).
 * 2. A squash-merged pull request leaves the branch unmerged for Git, so cleanup refuses. If GitHub reports a merged
 *    pull request whose head is exactly the workspace's head commit, every commit is in the base and
 *    `workspace run --kind discard` removes it (it keeps recovery artifacts under `$DIETER_HOME/recovery`).
 *
 * Anything else stays: an unmerged branch is never discarded, a running card is never touched.
 * [mergedHead] (path, branch, head) is replaceable for tests; [implementationBoards] are the Dreamfall
 * Implementation boards (their cards count once started).
 */
class Sweeper(
    private val dieter: Dieter,
    var days: Int,
    private val mergedHead: (String, String, String) -> Boolean = ::ghMergedHead,
    private val now: () -> Instant = Instant::now,
    private val implementationBoards: () -> Set<String> = { emptySet() },
    private val log: (String) -> Unit = Log::line,
) {
    private var dfBoards = emptySet<String>()
    /** card -> reason last logged, so a card that stays put is logged once, not every sweep */
    private val skipped = mutableMapOf<String, String>()

    /** One pass over every project: {card id: "cleanup" | "discard"} for the folders it removed. */
    fun sweep(): Map<String, String> {
        val removed = linkedMapOf<String, String>()
        val cutoff = now().epochSecond - days * 86_400L
        dfBoards = try {
            implementationBoards()
        } catch (e: Exception) {   // Dreamfall is optional: without it, only Done and archived cards count
            note("dreamfall", "Implementation boards unknown: ${e.message}")
            emptySet()
        }
        for (p in dieter.run("project", "list", "--format", "json", timeout = 20.seconds).unwrap("projects")) {
            for (w in dieter.run("workspace", "list", "--project", p.field("id"), timeout = 30.seconds).unwrap("workspaces")) {
                if (w.field("mode") != "worktree" || w.field("state") != "ready") continue
                val cid = w.field("cardId")
                val kind = try {
                    one(p, w, cutoff)
                } catch (e: Exception) {
                    note(cid, "failed: ${e.message}")
                    null
                } ?: continue
                removed[cid] = kind
                skipped.remove(cid)
            }
        }
        return removed
    }

    private fun one(project: JsonObject, w: JsonObject, cutoff: Long): String? {
        val cid = w.field("cardId")
        val card = (dieter.run("card", "show", cid, timeout = 20.seconds) as? JsonObject)?.obj("card") ?: JsonObject(emptyMap())
        val since = idleSince(card, startedCounts = card.field("boardId") in dfBoards)
        if (since == null || since > cutoff || card.field("runtime") == "running") return null
        val title = card.field("title").take(50)
        var (status, error) = remove(cid, "cleanup")
        if (status == "succeeded") {
            log("housekeeping: removed the work folder of $cid ('$title'), branch merged")
            return "cleanup"
        }
        if ("not merged" !in error) {
            note(cid, "cleanup $status: $error")
            return null
        }
        val (branch, head) = w.field("branch") to w.field("headSha")
        if (branch.isEmpty() || head.isEmpty() || !mergedHead(project.field("path"), branch, head)) {
            note(cid, "branch is not merged, kept")
            return null
        }
        remove(cid, "discard").let { status = it.first; error = it.second }
        if (status == "succeeded") {
            log("housekeeping: removed the work folder of $cid ('$title'), pull request merged (recovery copy in the store's recovery folder)")
            return "discard"
        }
        note(cid, "discard $status: $error")
        return null
    }

    private fun remove(cardId: String, kind: String): Pair<String, String> {
        val out = dieter.run("workspace", "run", "--kind", kind, "--wait", cardId, timeout = 2.minutes)
        return operationResult(if (out is JsonPrimitive) out.content else out?.toString().orEmpty())
    }

    private fun note(cid: String, why: String) {
        if (skipped[cid] != why) {
            skipped[cid] = why
            log("housekeeping: $cid: $why")
        }
    }

    companion object {
        /** RFC 3339 (nanoseconds too) -> epoch seconds; null when absent or unreadable. */
        fun parseTime(s: String?): Long? = s?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

        /**
         * When a Done or archived card was last touched: its last agent activity or its move into Done (archived cards
         * also count the archiving, which the daemon records in updatedAt). Null unless it is Done or archived, or,
         * with [startedCounts], started (any lane but Todo).
         */
        fun idleSince(card: JsonObject, startedCounts: Boolean = false): Long? {
            val archived = card["archived"].truthy() || card["archivedAt"].truthy()
            val lane = card.field("lane")
            if (!archived && lane != "done" && !(startedCounts && lane !in setOf("", "todo"))) return null
            val keys = listOf("lastActivityAt", "phaseChangedAt") + if (archived) listOf("updatedAt") else emptyList()
            return keys.mapNotNull { parseTime(card.field(it).ifEmpty { null }) }.maxOrNull()
        }

        /** The last frame of `workspace run --wait` (a JSON line per change) -> (status, error). */
        fun operationResult(out: String): Pair<String, String> {
            val last = out.lines().mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.lastOrNull()
            val op = last?.obj("operation") ?: JsonObject(emptyMap())
            return op.field("status") to op.field("error")
        }

        /** True when a merged GitHub pull request of [branch] ended at commit [head] (read only, via `gh`). */
        fun ghMergedHead(path: String, branch: String, head: String): Boolean {
            val done = runCatching {
                Command.run(
                    listOf("gh", "pr", "list", "--head", branch, "--state", "merged", "--limit", "10", "--json", "headRefOid"),
                    cwd = File(path).takeIf { path.isNotEmpty() },
                    timeout = 60.seconds,
                )
            }.getOrNull() ?: return false
            if (done.exit != 0) return false
            val prs = runCatching { Json.parseToJsonElement(done.stdout.ifBlank { "[]" }) as? JsonArray }.getOrNull() ?: return false
            return prs.any { (it as? JsonObject)?.field("headRefOid") == head }
        }
    }
}

/**
 * The sweep once an hour, the first ten minutes after the start (the daemon and the first board poll come first),
 * and right after the retention changes in Settings ([wake]); 0 days keeps everything.
 */
class Housekeeping(private val sweeper: Sweeper, private val retentionDays: () -> Int) {
    private val wakeup = Semaphore(0)

    fun wake() {
        if (wakeup.availablePermits() == 0) wakeup.release()
    }

    fun loop(firstDelayMs: Long = 600_000, periodMs: Long = 3_600_000) {
        Thread.sleep(firstDelayMs)
        while (true) {
            wakeup.drainPermits()
            sweeper.days = retentionDays()
            if (sweeper.days > 0)
                runCatching { sweeper.sweep() }.onFailure { Log.line("housekeeping: sweep failed: ${it.message}") }
            wakeup.tryAcquire(periodMs, TimeUnit.MILLISECONDS)
        }
    }
}

/** Dieter's kv records of every Dreamfall pipeline: the ids of their Implementation boards. */
fun Dreamfall.implementationBoards(): Set<String> =
    listPipelines().mapNotNull { p -> Dreamfall.stage(p, "implementation")?.field("board_id")?.ifEmpty { null } }.toSet()
