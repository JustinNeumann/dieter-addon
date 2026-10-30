package com.getdieter.operator

import java.nio.file.Files
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Dreamfall: a multi-stage idea pipeline on Dieter boards (bridge/dreamfall.py until 0.6.431.19).
 *
 * A Dreamfall board is three ordinary Dieter boards of one project, tied together in order by a pipeline record:
 * Enumeration (an agent, nightly schedule, writes new ideas as Todo cards), Navigation (an agent, later schedule, judges
 * every idea in the Enumeration queue: it transfers the strong ones, a copy on Navigation while the original moves to
 * Done, and releases the rest to Enumeration · Review) and Implementation (the human gate: only the person picks
 * Navigation cards, copied here as Todo drafts).
 *
 * Records live in Dieter's account-wide kv store, namespace "dreamfall" (keys allow letters, digits, . _ -):
 * `p.<pipeline id>` the pipeline (name, project, goal, categories, stages with role, board, actor and schedule, agent);
 * `c.<card id>` an idea card (stage, category, source card, transferred_to, verdict, created_by, events: the trail);
 * `r.<card id>` a run of a stage's agent (its schedule card: status, times, counts, note);
 * `l.<pipeline>.<role>` the latest run of a stage (ordering: Navigation waits for Enumeration).
 *
 * The operator answers the page's actions with it (the person), the `dreamfall` command the agents run from their run
 * cards (DreamfallCommand) the rest; both refuse what a stage's actor may not do (no agent writes to the human stage).
 * The bridge's live view of the records (bridge/dreamfall_view.py) reads what both write.
 */
class Dreamfall(private val dieter: Dieter, private val now: () -> Instant = Instant::now) {
    private val random = SecureRandom()

    // ------------------------------------------------------------------ the dieter CLI
    /** `dieter ARGS`; a failure is a [DreamfallError] (the page gets it as 400, an agent as exit status 1). */
    private fun cli(args: List<String>, timeout: Duration = 60.seconds): JsonElement? =
        try {
            dieter.run(args, timeout)
        } catch (e: CliError) {
            throw DreamfallError(e.message ?: "dieter ${args.take(2).joinToString(" ")}: failed")
        }

    private fun cli(vararg args: String, timeout: Duration = 60.seconds) = cli(args.toList(), timeout)

    fun nowIso(): String = STAMP.format(now())

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ kv records
    /** A record and its revision; (null, "") when there is none. */
    fun kvGet(key: String): Pair<JsonObject?, String> {
        val entry =
            try {
                cli("kv", "get", "--namespace", NS, "--key", key, timeout = 20.seconds)
            } catch (e: DreamfallError) {
                if ("not found" in e.message.orEmpty().lowercase()) return null to ""
                throw e
            }
        return decodeEntry(entry)
    }

    /** Writes [value] (its trail cut to what a kv value holds, 32 KiB) and returns what was written. */
    fun kvPut(key: String, value: JsonObject, revision: String = ""): JsonObject {
        if (!KEY.matches(key)) throw DreamfallError("bad kv key '$key'")
        var v = value
        (v["events"] as? JsonArray)?.let { if (it.size > MAX_EVENTS) v = v.updated("events" to JsonArray(it.takeLast(MAX_EVENTS))) }
        var body = v.toString()
        while (body.toByteArray().size > 30_000 && ((v["events"] as? JsonArray)?.size ?: 0) > 5) {
            val events = v["events"] as JsonArray
            v = v.updated("events" to JsonArray(events.drop(events.size / 4)))
            body = v.toString()
        }
        val file = Files.createTempFile("dreamfall-", ".json").toFile()
        try {
            file.writeText(body)
            cli(listOf("kv", "put", "--namespace", NS, "--key", key, "--file", file.path) + (if (revision.isNotEmpty()) listOf("--revision", revision) else emptyList()), 20.seconds)
        } finally {
            file.delete()
        }
        return v
    }

    fun kvDelete(key: String): Boolean {
        val (value, revision) = kvGet(key)
        if (value == null || revision.isEmpty()) return false
        cli("kv", "delete", "--namespace", NS, "--key", key, "--revision", revision, timeout = 20.seconds)
        return true
    }

    /** Read-modify-write with the observed revision: a concurrent write is read again and [change] applied again. [change]
     * answers null for "nothing to change"; the result is the record as written (or as found). */
    fun kvUpdate(key: String, attempts: Int = 8, change: (JsonObject?) -> JsonObject?): JsonObject? {
        repeat(attempts) { i ->
            val (cur, revision) = kvGet(key)
            val new = change(cur) ?: return cur
            try {
                return kvPut(key, new, revision)
            } catch (e: DreamfallError) {
                val msg = e.message.orEmpty().lowercase()
                if (("conflict" in msg || "revision" in msg || "exists" in msg) && i < attempts - 1) Thread.sleep(150L * (i + 1)) else throw e
            }
        }
        throw DreamfallError("kv $key: too many concurrent writes")
    }

    fun event(kind: String, actor: String, name: String = "", vararg extra: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("id", hex(4))
        put("kind", kind)
        put("actor", actor)
        put("name", name)
        put("at", nowIso())
        for ((k, v) in extra) {
            if (v == null || v == "") continue
            put(k, if (k == "note" || k == "rationale") JsonPrimitive(clip(v.toString())) else json(v))
        }
    }

    // ------------------------------------------------------------------ cards and boards
    fun cardShow(id: String): DfCard {
        if (!CARD.matches(id)) throw DreamfallError("not a card id: '$id'")
        return DfCard.of(cli("card", "show", id, timeout = 30.seconds))
    }

    fun boardCards(boardId: String, lane: String = "", archived: Boolean = false, limit: Int = 2000): List<DfCard> {
        val args = mutableListOf("card", "list", "--board", boardId, "--format", "json", "--limit", "$limit")
        if (lane.isNotEmpty()) args += listOf("--lane", lane)
        if (archived) args += "--archived"
        return cli(args, 60.seconds).unwrap("cards").map(DfCard::of)
    }

    fun move(cardId: String, lane: String) {
        cli("card", "move", "--lane", lane, cardId, timeout = 30.seconds)
    }

    /** `dieter card create ARGS` with [prompt] as its brief (a temporary file): the new card's id, "" when none came back. */
    fun createCard(args: List<String>, prompt: String, prefix: String): String {
        val file = Files.createTempFile(prefix, ".md").toFile()
        val out =
            try {
                file.writeText(prompt)
                cli(args + listOf("--prompt-file", file.path), 120.seconds)
            } finally {
                file.delete()
            }
        return ((out as? JsonObject)?.let { if ("card" in it) it["card"] else it } as? JsonObject)?.field("id").orEmpty()
    }

    /** source card id -> the first card on [boardId] whose brief names it (a copy made earlier), open and archived. */
    private fun copiesOn(boardId: String): Map<String, String> = copies(boardCards(boardId) + boardCards(boardId, archived = true))

    // ------------------------------------------------------------------ pipelines
    fun listPipelines(): List<JsonObject> {
        val out = cli("kv", "list", "--namespace", NS, "--prefix", "p.", timeout = 20.seconds) as? JsonObject ?: return emptyList()
        return out.objs("entries").mapNotNull { decodeEntry(it).first }.filter { it.field("id").isNotEmpty() }
    }

    fun loadPipeline(pid: String): JsonObject {
        if (!PID.matches(pid)) throw DreamfallError("not a Dreamfall id: '$pid'")
        return kvGet(pipelineKey(pid)).first ?: throw DreamfallError("Dreamfall $pid not found")
    }

    fun pipelineForBoard(boardId: String, pipelines: List<JsonObject> = listPipelines()) = pipelines.firstOrNull { stageOfBoard(it, boardId) != null }

    private fun scheduleArgs(p: JsonObject, role: String): List<String> {
        val s = stage(p, role)!!
        val agent = p.obj("agent") ?: JsonObject(emptyMap())
        val args = mutableListOf(
            "--project", p.field("project_id"), "--board", s.field("board_id"), "--name", "${p.field("name")} · ${s.field("name")}",
            "--cron", s.field("cron"), "--timezone", p.field("timezone").ifEmpty { "UTC" }, "--title", s.field("run_title"),
            "--prompt", if (role == "enumeration") enumerationPrompt(p) else navigationPrompt(p),
            "--action", "run", "--workspace", "project", "--open-card", "skip_if_open",
            "--enabled=" + (if ((p["enabled"] ?: JsonPrimitive(true)).truthy()) "true" else "false"),
        )
        for (k in listOf("provider", "model", "effort")) if (agent[k].truthy()) args += listOf("--$k", agent.field(k))
        if (role == "navigation" && p["mail"].truthy()) args += "--mail=true"
        return args
    }

    /** Creates or replaces the two agent schedules from the pipeline record (prompt, time, agent, enabled); the record
     * with the ids of schedules it made, which also go to [made] (removed again when the creation fails). */
    private fun syncSchedules(pipeline: JsonObject, made: MutableList<String> = mutableListOf()): JsonObject {
        var p = pipeline
        for (role in listOf("enumeration", "navigation")) {
            val s = stage(p, role)!!
            val sid = s.field("schedule_id")
            if (sid.isNotEmpty()) {
                try {
                    cli(listOf("schedule", "update") + scheduleArgs(p, role) + sid, 60.seconds)
                    continue
                } catch (e: DreamfallError) {
                    if ("not found" !in e.message.orEmpty().lowercase()) throw e
                }
            }
            val out = cli(listOf("schedule", "create") + scheduleArgs(p, role), 60.seconds)
            val schedule = (out as? JsonObject)?.let { if ("schedule" in it) it["schedule"] else it } as? JsonObject
            val id = schedule?.field("id").orEmpty()
            if (id.isEmpty()) throw DreamfallError("schedule for ${s.field("name")} was not created")
            made += id
            p = p.withStages { if (it.field("role") == role) it.updated("schedule_id" to id) else it }
        }
        return p
    }

    // ------------------------------------------------------------------ creating and changing a pipeline
    /** Creates the stage boards (or adopts existing boards of the project), the pipeline record and the two schedules;
     * on a failure everything created so far is removed again. */
    fun create(b: JsonObject, actorName: String = "you"): JsonObject {
        val project = b.field("project")
        if (!project.startsWith("p_")) throw DreamfallError("project required")
        val st = cleanSettings(b)
        val name = st.field("name")
        val mine = cli("board", "list", "--format", "json", timeout = 30.seconds).unwrap("boards")
            .filter { it.str("project_id", "projectId") == project }.associateBy { it.field("id") }
        val taken = listPipelines().flatMap(::stages).map { it.field("board_id") }.toSet()
        val names = mine.values.map { it.field("name") }.toMutableSet()
        val pid = "df_" + hex(5)
        val madeBoards = mutableListOf<String>()
        val madeSchedules = mutableListOf<String>()
        var recorded = false
        try {
            val stages = mutableListOf<JsonObject>()
            val adopt = b.obj("boards") ?: JsonObject(emptyMap())
            for (role in ROLES) {
                val d = STAGES.getValue(role)
                val sname = clip(b.obj("stage_names")?.field(role).orEmpty().ifEmpty { d.name }, 40)
                var bid = adopt.field(role)
                if (bid.isNotEmpty()) {
                    val board = mine[bid] ?: throw DreamfallError("board $bid is not a board of this project")
                    if (bid in taken || stages.any { it.field("board_id") == bid }) throw DreamfallError("board ${board.field("name")} is already a stage of a Dreamfall board")
                    if (board.objs("lanes").none { it.field("id") == "review" }) throw DreamfallError("board ${board.field("name")} has no Review lane (a Dreamfall stage needs one)")
                } else {
                    val bname = if (sname !in names) sname else "$name · $sname"
                    val out = cli("board", "create", "--project", project, "--name", bname, "--workflow", "review", "--description", "$sname stage of the Dreamfall board $name", timeout = 30.seconds)
                    bid = ((out as? JsonObject)?.let { it.obj("board") ?: it })?.field("id").orEmpty()
                    if (bid.isEmpty()) throw DreamfallError("board $bname was not created")
                    madeBoards += bid
                    names += bname
                }
                stages += buildJsonObject {
                    put("role", role)
                    put("name", sname)
                    put("board_id", bid)
                    put("actor", d.actor)
                    put("icon", d.icon)
                    put("color", d.color)
                    if (d.actor == "agent") {
                        put("cron", st.getValue(role + "_cron"))
                        put("run_title", "$name · $sname run")
                    }
                }
            }
            var p = buildJsonObject {
                put("v", 1)
                put("id", pid)
                put("project_id", project)
                put("created_at", nowIso())
                put("updated_at", nowIso())
                put("created_by", actorName)
                st.forEach { (k, v) -> if (!k.endsWith("_cron")) put(k, v) }
                put("stages", JsonArray(stages))
            }
            kvPut(pipelineKey(pid), p)
            recorded = true
            p = syncSchedules(p, madeSchedules)
            val withSchedules = p["stages"]!!
            kvUpdate(pipelineKey(pid)) { cur -> (cur ?: p).updated("stages" to withSchedules, "updated_at" to nowIso()) }
            Log.line("dreamfall: $name ($pid) created in $project")
            return p
        } catch (e: Exception) {
            for (sid in madeSchedules) quietly { cli("schedule", "delete", sid, timeout = 30.seconds) }
            if (recorded) quietly { kvDelete(pipelineKey(pid)) }
            madeBoards.forEach(::retireBoard)
            throw e
        }
    }

    /** A stage board this creation made, after the creation failed (boards are retired, not deleted). */
    private fun retireBoard(bid: String) = quietly {
        val shown = cli("board", "show", bid, timeout = 30.seconds) as? JsonObject ?: JsonObject(emptyMap())
        val board = if ("board" in shown) shown.obj("board") else shown
        val revision = board?.field("retirementRevision").orEmpty().ifEmpty { shown.field("retirementRevision") }
        if (revision.isNotEmpty()) cli("board", "retire", "--revision", revision, "--operation", "df-" + hex(6), bid, timeout = 30.seconds)
    }

    fun update(pid: String, b: JsonObject): JsonObject {
        val before = loadPipeline(pid)
        val st = cleanSettings(b, before)
        val changed = kvUpdate(pipelineKey(pid)) { found ->
            var cur = found ?: before
            st.forEach { (k, v) -> if (!k.endsWith("_cron")) cur = cur.updated(k to v) }
            val name = cur.field("name")
            cur.withStages { s ->
                if (s.field("actor") != "agent") return@withStages s
                val title = "$name · ${s.field("name")} run"
                val old = s.field("run_title")
                var x = s.updated("cron" to st.getValue(s.field("role") + "_cron"))
                if (old.isNotEmpty() && old != title) { // the earlier runs keep their titles: still runs, not ideas
                    x = x.updated("old_run_titles" to (s.strings("old_run_titles").filter { it != title } + old).takeLast(10))
                }
                x.updated("run_title" to title)
            }.updated("updated_at" to nowIso())
        }!!
        val p = syncSchedules(changed)
        return kvUpdate(pipelineKey(pid)) { cur -> (cur ?: p).updated("stages" to p["stages"]) }!!
    }

    /** Removes the Dreamfall view: the schedules go, the boards and their cards stay as ordinary Kanban boards. */
    fun delete(pid: String): JsonObject {
        val p = loadPipeline(pid)
        for (s in stages(p)) {
            val sid = s.field("schedule_id")
            if (sid.isEmpty()) continue
            try {
                cli("schedule", "delete", sid, timeout = 30.seconds)
            } catch (e: DreamfallError) {
                if ("not found" !in e.message.orEmpty().lowercase()) throw e
            }
        }
        kvDelete(pipelineKey(pid))
        Log.line("dreamfall: $pid removed, its boards stay")
        return buildJsonObject {
            put("ok", true)
            put("boards", json(stages(p).map { it.field("board_id") }))
        }
    }

    fun runNow(pid: String, role: String): JsonObject {
        val s = stage(loadPipeline(pid), role)
        if (s == null || s.field("actor") != "agent" || s.field("schedule_id").isEmpty()) throw DreamfallError("$role has no agent schedule")
        val out = cli("schedule", "run", s.field("schedule_id"), timeout = 60.seconds)
        return buildJsonObject {
            put("ok", true)
            put("run", out ?: JsonNull)
        }
    }

    // ------------------------------------------------------------------ moving ideas (agents and the person)
    fun copyBrief(srcPrompt: String, verb: String, fromName: String, who: String, rationale: String, srcId: String, extra: String = ""): String {
        val note = "**$verb from $fromName** by $who, ${LocalDate.now()}" + (if (rationale.isNotEmpty()) ": ${clip(rationale)}" else ".")
        val more = if (extra.isNotBlank()) "\n\n${extra.trim()}" else ""
        return "${srcPrompt.trimEnd()}\n\n---\n$note$more\n\n<!-- $MARKER $srcId -->\n"
    }

    /** A copy on the next stage: its id, whether this call completed the transfer, and the records it wrote. */
    class Moved(val id: String, val completed: Boolean, val records: Map<String, JsonObject?>)

    /**
     * Copies an idea to the next stage (provenance both ways) and releases the original (Done). Idempotent: a copy
     * recorded on the source, or a card on the destination board whose brief names the source, is reused. [known]
     * (board id) -> source card id -> its copy, from something that already holds the board's cards (the bridge's live
     * view), or null: then the board is listed through the CLI (two `card list` calls of ~1 s each on a Pi).
     */
    fun transfer(
        p: JsonObject,
        src: DfCard,
        destRole: String,
        actor: String,
        name: String,
        verb: String,
        rationale: String = "",
        score: Int? = null,
        run: String = "",
        note: String = "",
        settings: Map<String, String> = emptyMap(),
        start: Boolean = false,
        known: ((String) -> Map<String, String>?)? = null,
    ): Moved {
        val from = stageOfBoard(p, src.boardId)
        val to = stage(p, destRole)
        if (from == null || to == null) throw DreamfallError("the card is not on a stage of this Dreamfall board")
        val toBoard = to.field("board_id")
        val rec = kvGet("c." + src.id).first
        var newId = ""
        var created = false
        val recorded = rec?.field("transferred_to").orEmpty()
        if (recorded.isNotEmpty()) newId = try { if (cardShow(recorded).boardId == toBoard) recorded else "" } catch (_: DreamfallError) { "" }
        if (newId.isEmpty()) newId = (known?.invoke(toBoard) ?: copiesOn(toBoard))[src.id].orEmpty()
        if (newId.isEmpty()) {
            val (extra, extraArgs) = if (to.field("actor") == "human") implementationExtras(p) else "" to emptyList()
            val args = mutableListOf("card", "create", "--project", p.field("project_id"), "--board", toBoard, "--lane", "todo", "--title", src.title,
                "--workspace", settings["workspace"].orEmpty().ifEmpty { "project" }, "--format", "json")
            args += extraArgs
            for (k in listOf("provider", "model", "effort")) settings[k].orEmpty().ifEmpty { src.agent(k) }.takeIf { it.isNotEmpty() }?.let { args += listOf("--$k", it) }
            if (src.checkoutId.isNotEmpty()) args += listOf("--checkout", src.checkoutId)
            newId = createCard(args, copyBrief(src.prompt, verb, from.field("name"), name, rationale, src.id, extra), "dreamfall-brief-")
            if (newId.isEmpty()) throw DreamfallError("the copy was not created")
            created = true
        }
        val category = rec?.field("category").orEmpty().ifEmpty { splitCategory(src.title).first }
        val kind = if (destRole == "implementation") "picked" else "judged"
        val verdict = buildJsonObject {
            put("kind", if (destRole == "implementation") "pick" else "promote")
            put("rationale", clip(rationale.ifEmpty { note }))
            put("actor", actor)
            put("name", name)
            put("at", nowIso())
            if (score != null) put("score", score)
            if (run.isNotEmpty()) put("run", run)
        }
        val records = linkedMapOf<String, JsonObject?>()
        // the source's verdict first: whoever sees the copy's record (created_by) also sees the verdict it came with
        records["c." + src.id] = kvUpdate("c." + src.id) { found ->
            var cur = found ?: record("card" to src.id, "pipeline" to p.field("id"), "stage" to from.field("role"), "category" to category)
            if ("stage" !in cur) cur = cur.updated("stage" to from.field("role"))
            if (!created && cur.field("transferred_to") == newId && cur.objs("events").any { it.field("kind") == "transferred" && it.field("to") == newId }) {
                return@kvUpdate null // a rerun of a recorded transfer: nothing to add
            }
            cur = cur.updated("transferred_to" to newId)
            if (created || cur.obj("verdict")?.field("kind") !in setOf("promote", "pick")) cur = cur.updated("verdict" to verdict)
            addEvents(cur,
                event(kind, actor, name, "run" to run, "verdict" to verdict.field("kind"), "rationale" to rationale, "note" to note, "score" to score, "stage" to destRole),
                event("transferred", actor, name, "run" to run, "to" to newId, "stage" to destRole))
        }
        records["c.$newId"] = kvUpdate("c.$newId") { found ->
            var cur = found ?: record("card" to newId, "created_at" to nowIso())
            cur = cur.updated("pipeline" to p.field("id"), "stage" to destRole, "source" to src.id, "category" to category.ifEmpty { cur.field("category") })
            if ("created_by" !in cur) cur = cur.updated("created_by" to createdBy(actor, name, run))
            addEvents(cur, event("created", actor, name, "run" to run, "from" to src.id, "stage" to destRole))
        }
        if (src.lane != "done") move(src.id, "done")
        if (start) {
            cli("card", "start", newId, timeout = 60.seconds)
            records["c.$newId"] = kvUpdate("c.$newId") { cur -> addEvents(cur ?: JsonObject(mapOf("events" to JsonArray(emptyList()))), event("started", actor, name)) }
        }
        return Moved(newId, created || rec?.field("transferred_to") != newId, records)
    }

    /** Takes an idea out of its stage's queue without deleting it: Review (released / parked). The record as written. */
    fun release(p: JsonObject, src: DfCard, actor: String, name: String, kind: String = "release", rationale: String = "", score: Int? = null, run: String = ""): JsonObject? {
        val s = stageOfBoard(p, src.boardId)!!
        val verdict = buildJsonObject {
            put("kind", kind)
            put("rationale", clip(rationale))
            put("actor", actor)
            put("name", name)
            put("at", nowIso())
            if (score != null) put("score", score)
            if (run.isNotEmpty()) put("run", run)
        }
        val rec = kvUpdate("c." + src.id) { found ->
            var cur = found ?: record("card" to src.id, "pipeline" to p.field("id"), "stage" to s.field("role"), "category" to splitCategory(src.title).first)
            if ("stage" !in cur) cur = cur.updated("stage" to s.field("role"))
            if (cur.obj("verdict")?.field("kind") == kind && src.lane == "review" && (cur["events"] as? JsonArray).orEmpty().isNotEmpty()) {
                return@kvUpdate null // rerun: already released with this verdict
            }
            addEvents(cur.updated("verdict" to verdict),
                event(if (kind == "release") "released" else "parked", actor, name, "run" to run, "verdict" to kind, "rationale" to rationale, "score" to score))
        }
        if (src.lane != "review") move(src.id, "review")
        return rec
    }

    fun restore(p: JsonObject, src: DfCard, actor: String, name: String, note: String = ""): JsonObject? {
        val rec = kvUpdate("c." + src.id) { found ->
            addEvents((found ?: record("card" to src.id, "pipeline" to p.field("id"))).updated("verdict" to JsonNull), event("restored", actor, name, "note" to note))
        }
        if (src.lane != "todo") move(src.id, "todo")
        return rec
    }

    /** The record of a card made on a stage board ([title] carries its category unless [category] is given). */
    fun recordCreated(p: JsonObject, cardId: String, boardId: String, title: String, actor: String, name: String, run: String = "", category: String = ""): JsonObject? {
        val s = stageOfBoard(p, boardId) ?: throw DreamfallError("the card is not on a stage of this Dreamfall board")
        val cat = category.ifEmpty { splitCategory(title).first }
        return kvUpdate("c.$cardId") { found ->
            var cur = found ?: record("card" to cardId, "created_at" to nowIso())
            cur = cur.updated("pipeline" to p.field("id"), "stage" to s.field("role"), "category" to cat.ifEmpty { cur.field("category") })
            if ("created_by" !in cur) cur = cur.updated("created_by" to createdBy(actor, name, run))
            addEvents(cur, event("created", actor, name, "run" to run, "stage" to s.field("role")))
        }
    }

    /**
     * A person's decision on the Dreamfall board: promote/release (first stage), pick/park (second stage), restore (a
     * released or parked idea back to its queue), start (an Implementation card). The answer names the boards touched,
     * the lanes the cards are in now ("lanes": card id -> lane) and the records written ("records": key -> value), so
     * the caller can show the change without listing the boards again; [known]: see [transfer].
     */
    fun act(pid: String, b: JsonObject, actorName: String = "you", known: ((String) -> Map<String, String>?)? = null): JsonObject {
        val p = loadPipeline(pid)
        val action = b.field("action")
        val cid = b.field("card")
        val c = cardShow(cid)
        val s = stageOfBoard(p, c.boardId)
        if (s == null || c.archived) throw DreamfallError("the card is not an open idea of this Dreamfall board")
        val stages = stages(p)
        val i = stages.indexOfFirst { it.field("role") == s.field("role") }
        val note = clip(b.field("note"))
        fun answer(boards: List<String>, lanes: Map<String, String>, records: Map<String, JsonObject?>, extra: JsonObject.() -> JsonObject = { this }) =
            buildJsonObject {
                put("ok", true)
                put("boards", json(boards))
                put("lanes", json(lanes))
                put("records", json(records))
            }.extra()
        when (action) {
            "promote", "pick" -> {
                val next = stages.getOrNull(i + 1)
                if (next == null || (action == "pick") != (next.field("actor") == "human")) throw DreamfallError("$action is not possible from ${s.field("name")}")
                if (c.lane !in setOf("todo", "review")) {
                    val parked = STAGES.getValue(s.field("role")).lanes.getValue("review").lowercase()
                    throw DreamfallError("only waiting or $parked ideas can be ${if (action == "pick") "picked" else "promoted"}")
                }
                val settings = mutableMapOf("workspace" to b.field("workspace").takeIf { it in setOf("project", "worktree") }.orEmpty().ifEmpty { "project" })
                for (k in listOf("provider", "model", "effort")) if (b[k].truthy()) settings[k] = b.field(k)
                val started = b["start"].truthy() && action == "pick"
                val moved = transfer(p, c, next.field("role"), "human", actorName, if (action == "pick") "Picked" else "Promoted",
                    rationale = note, note = note, settings = settings, start = started, known = known)
                return answer(listOf(c.boardId, next.field("board_id")), mapOf(cid to "done", moved.id to if (started) "running" else "todo"), moved.records) {
                    updated("id" to moved.id, "created" to moved.completed)
                }
            }
            "release", "park" -> {
                if (c.lane != "todo" || s.field("actor") == "human") throw DreamfallError("only waiting ideas can be released or parked")
                val rec = release(p, c, "human", actorName, kind = if (s.field("role") == "enumeration") "release" else "park", rationale = note)
                return answer(listOf(c.boardId), mapOf(cid to "review"), mapOf("c.$cid" to rec))
            }
            "restore" -> {
                if (c.lane != "review" || s.field("actor") == "human") throw DreamfallError("only released or parked ideas go back to the queue")
                val rec = restore(p, c, "human", actorName, note)
                return answer(listOf(c.boardId), mapOf(cid to "todo"), mapOf("c.$cid" to rec))
            }
            "start" -> {
                if (s.field("actor") != "human" || c.lane != "todo" || c.started) throw DreamfallError("only a Todo card of the person's stage is started here")
                cli("card", "start", cid, timeout = 60.seconds)
                val rec = kvUpdate("c.$cid") { cur ->
                    addEvents(cur ?: record("card" to cid, "pipeline" to pid, "stage" to s.field("role")), event("started", "human", actorName))
                }
                return answer(listOf(c.boardId), mapOf(cid to "running"), mapOf("c.$cid" to rec))
            }
        }
        throw DreamfallError("action must be promote, release, pick, park, restore or start")
    }

    // ------------------------------------------------------------------ settings and prompts
    /** The settings of a new or changed Dreamfall board, checked; [cur] is the pipeline as it is. Besides the
     * record's fields it holds the two agent stages' schedules as "enumeration_cron" and "navigation_cron". */
    fun cleanSettings(b: JsonObject, cur: JsonObject = JsonObject(emptyMap())): JsonObject {
        fun given(key: String, fallback: JsonElement?): JsonElement? = if (key in b) b[key] else fallback
        fun or(e: JsonElement?, default: String): JsonElement = if (e.truthy()) e!! else JsonPrimitive(default)
        val out = linkedMapOf<String, JsonElement>()
        val name = text(given("name", or(cur["name"], "Dreamfall"))).trim()
        if (name.isEmpty() || name.length > 80) throw DreamfallError("name required (max 80 characters)")
        out["name"] = JsonPrimitive(name)
        out["goal"] = JsonPrimitive(clip(text(given("goal", or(cur["goal"], ""))), 1200))
        val cats = when (val c = given("categories", cur["categories"].takeIf { it.truthy() } ?: JsonArray(emptyList()))) {
            is JsonArray -> c.map { text(it) }

            is JsonPrimitive -> if (c is JsonNull) emptyList() else c.content.lines()
            else -> emptyList()
        }
        out["categories"] = json(cats.filter { it.isNotBlank() }.map { clip(it.replace(BULLET, ""), 80) }.take(40))
        val file = text(given("categories_file", or(cur["categories_file"], ""))).trim().take(200)
        out["categories_file"] = JsonPrimitive(file)
        if (file.isNotEmpty() && (file.startsWith("/") || ".." in file.split("/"))) throw DreamfallError("categories file must be a path inside the project")
        if ((out["categories"] as JsonArray).isEmpty() && file.isEmpty()) throw DreamfallError("categories required: one per line, or a categories file of the project")
        val per = given("per_category", cur["per_category"].takeIf { it.truthy() } ?: JsonPrimitive(3)) as? JsonPrimitive
        val n = per?.let { if (it.isString) it.content.trim().toIntOrNull() else it.content.toDoubleOrNull()?.toInt() }
            ?: throw DreamfallError("ideas per category must be a number")
        out["per_category"] = JsonPrimitive(n.coerceIn(1, 10))
        val logic = b["navigation_logic"].let { if (it == null || it is JsonNull) navigationLogic(cur) else text(it) }.paragraphs()
        if (logic.isEmpty()) throw DreamfallError("navigation logic required: how the Navigation agent decides which ideas go on")
        if (logic.length > MAX_LOGIC) throw DreamfallError("navigation logic too long (max $MAX_LOGIC characters)")
        out["navigation_logic"] = JsonPrimitive(logic)
        val impl = text(given("implementation_instructions", or(cur["implementation_instructions"], "")).takeIf { it.truthy() }).paragraphs()
        if (impl.length > MAX_LOGIC) throw DreamfallError("implementation instructions too long (max $MAX_LOGIC characters)")
        out["implementation_instructions"] = JsonPrimitive(impl)
        out["implementation_mail"] = JsonPrimitive(given("implementation_mail", cur["implementation_mail"]).truthy())
        val zone = text(given("timezone", or(cur["timezone"], "UTC"))).trim().ifEmpty { "UTC" }
        if (!ZONE.matches(zone)) throw DreamfallError("timezone must be an IANA name such as Europe/Berlin")
        out["timezone"] = JsonPrimitive(zone)
        out["enabled"] = JsonPrimitive(given("enabled", cur["enabled"] ?: JsonPrimitive(true)).truthy())
        out["mail"] = JsonPrimitive(given("mail", cur["mail"]).truthy())
        val a = b.obj("agent") ?: JsonObject(emptyMap())
        val ca = cur.obj("agent") ?: JsonObject(emptyMap())
        out["agent"] = buildJsonObject { for (k in listOf("provider", "model", "effort")) put(k, text((if (k in a) a[k] else ca[k]).takeIf { it.truthy() })) }
        val days = b["days"].let { d ->
            if (d == null || d is JsonNull) return@let null
            val list = (d as? JsonArray)?.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.content?.toIntOrNull()?.takeIf { x -> x in 0..6 } }
            if (list == null || null in list) throw DreamfallError("days must be a list of weekdays (0 = Sunday … 6 = Saturday)")
            if (list.isEmpty()) throw DreamfallError("pick at least one weekday")
            list.filterNotNull()
        }
        for (role in listOf("enumeration", "navigation")) {
            val t = text(b[role + "_time"])
            val old = stage(cur, role)?.field("cron").orEmpty()
            val time = t.ifEmpty { hhmmOf(old).ifEmpty { if (role == "enumeration") "02:00" else "05:00" } }
            out[role + "_cron"] = JsonPrimitive(cronOf(time, days ?: if (old.isNotEmpty()) daysOf(old) else null))
        }
        return JsonObject(out)
    }

    companion object {
        const val NS = "dreamfall"
        val ROLES = listOf("enumeration", "navigation", "implementation")

        class StageKind(val name: String, val actor: String, val icon: String, val color: String, val lanes: Map<String, String>)

        val STAGES = mapOf(
            "enumeration" to StageKind("Enumeration", "agent", "auto_awesome", "#8a62c3", mapOf("todo" to "Queue", "review" to "Released", "done" to "Transferred")),
            "navigation" to StageKind("Navigation", "agent", "balance", "#478dc5", mapOf("todo" to "Waiting for you", "review" to "Parked", "done" to "Picked")),
            "implementation" to StageKind("Implementation", "human", "lock_person", "#3e9970", mapOf("todo" to "Todo", "running" to "Running", "review" to "Review", "done" to "Done")),
        )
        const val MAX_EVENTS = 80 // per card record (kv values are at most 32 KiB)
        const val MAX_TEXT = 600 // rationale / note length kept in a record
        const val MAX_LOGIC = 4000
        const val MARKER = "dreamfall-source:" // the line in a copy's brief that names its source card (idempotent transfers)
        const val WAIT_EXIT = 75 // `run begin` of a later stage while the earlier stage's run still works
        val KEY = Regex("^[A-Za-z0-9_.-]{1,200}$")
        val PID = Regex("^df_[a-f0-9]{10}$")
        val CARD = Regex("^c_[A-Za-z0-9_-]{6,60}$")
        private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
        private val ZONE = Regex("^[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}$|^UTC$")
        private val SOURCE = Regex("$MARKER ([A-Za-z0-9_-]+)")
        private val SPACES = Regex("(?U)\\s+")
        private val NON_WORD = Regex("(?U)[^\\w]+")
        private val BULLET = Regex("^\\s*[-*]\\s*")
        private val CATEGORY = Regex("^\\s*\\[([^\\]]{1,60})\\]\\s*(.*)$")
        private val WS = Regex("\\s+")
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        val ALL_DAYS = listOf(1, 2, 3, 4, 5, 6, 0) // cron weekdays, Monday first (0 = Sunday)

        // The Navigation agent judges by this text; the person sets it per Dreamfall board (New board, Settings).
        val DEFAULT_NAVIGATION_LOGIC =
            """Promote an idea only when all of these hold:
- clear, concrete value (money, safety, comfort, time, joy), not filler;
- realistic with what exists (or one cheap addition); the ingredients it names are real (check where cheap);
- genuinely new: not already done in the project and not a duplicate of an idea that already passed
  (among near-duplicates in this batch promote only the best one);
- acceptable risk: nothing unsafe without a fallback.
Be selective: about one idea in four should pass. When in doubt, release."""

        val MAIL_HOWTO =
            "You may mail the person (this card has mail access): write a Markdown report to a file and run " +
                "`dieter mail send --subject \"<one line>\" --body-file <file>`. A ```vega-lite``` (inline JSON data) or " +
                "```mermaid``` fence in the body is shown as a chart in Dieter Mail."

        // Dieter's board instructions move a card to Review only once its pull request exists, so in a project without a
        // git remote a finished card stayed in Running (idle, "Running" on the page) for good.
        val FINISH_HOWTO =
            "When the work is committed and its checks pass, move this card to Review (`dieter card move <this card> " +
                "--lane review`). If the project has no git remote (`dieter workspace scm <this card>` says so), there is " +
                "no pull request to open: say so in your reply and move the card to Review anyway, do not leave it in Running."

        fun pipelineKey(pid: String) = "p.$pid"

        fun clip(s: String?, n: Int = MAX_TEXT): String {
            val t = s.orEmpty().replace(SPACES, " ").trim()
            return if (t.length <= n) t else t.substring(0, n - 1).trimEnd() + "…"
        }

        fun parseTs(s: String?): Double {
            val t = s.orEmpty().trim()
            if (t.isEmpty()) return 0.0
            return runCatching { OffsetDateTime.parse(t.replace("Z", "+00:00")).toInstant() }
                .recoverCatching { LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant() }
                .map { it.epochSecond + it.nano / 1e9 }
                .getOrDefault(0.0)
        }

        /** A kv entry (get, list, watch) -> (its value, null when deleted or no object; its revision). */
        fun decodeEntry(e: JsonElement?): Pair<JsonObject?, String> {
            val entry = e as? JsonObject ?: return null to ""
            val revision = entry.field("revision")
            if (entry["deleted"].truthy()) return null to revision
            val selected = b64json(entry.field("valueJson"))
            val versions = entry.objs("versions").map { b64json(it.field("valueJson")) }
            return mergeVersions(selected, versions) as? JsonObject to revision
        }

        private fun b64json(s: String): JsonElement? =
            runCatching { Base64.getDecoder().decode(s).takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it.decodeToString()) } }.getOrNull()?.takeUnless { it is JsonNull }

        /** Concurrent writes from two daemons leave causal siblings; the trail must not lose an event, so card records are
         * merged: events united by id, the newest verdict, the first transfer target. Other records keep the value the
         * store selected. */
        fun mergeVersions(selected: JsonElement?, versions: List<JsonElement?>): JsonElement? {
            val vals = versions.filterIsInstance<JsonObject>()
            if (selected !is JsonObject || vals.size < 2 || "events" !in selected) return selected
            val seen = mutableSetOf<JsonElement?>()
            val events = mutableListOf<JsonObject>()
            for (v in vals) for (e in v.objs("events")) if (seen.add(e["id"]?.takeUnless { it is JsonNull })) events += e
            val sorted = events.sortedWith(compareBy({ parseTs(it.field("at")) }, { it.field("id") }))
            var out = selected.updated("events" to sorted.takeLast(MAX_EVENTS))
            vals.mapNotNull { it.obj("verdict") }.maxByOrNull { parseTs(it.field("at")) }?.let { out = out.updated("verdict" to it) }
            for (k in listOf("transferred_to", "source", "category")) {
                if (!out[k].truthy()) out = out.updated(k to (vals.firstNotNullOfOrNull { v -> v[k].takeIf { it.truthy() } } ?: out[k] ?: JsonNull))
            }
            return out
        }

        /** Appends events, skipping one already there (same kind and same target or source: a rerun of the same step). */
        fun addEvents(rec: JsonObject, vararg events: JsonObject): JsonObject {
            fun sig(e: JsonObject) = e.field("kind") to e.field("to").ifEmpty { e.field("from") }.ifEmpty { e.field("run") }
            val have = rec.objs("events").map(::sig).toMutableSet()
            val out = (rec["events"] as? JsonArray).orEmpty().toMutableList()
            for (e in events) {
                val s = sig(e)
                if (e.field("kind") in setOf("created", "transferred", "picked") && s in have) continue
                out += e
                have += s
            }
            return rec.updated("events" to JsonArray(out))
        }

        /** "[Heating] Shutters as …" -> ("Heating", "Shutters as …"): the category travels in the title, so it shows in
         * every Dieter app and survives a copy; the record keeps it too. */
        fun splitCategory(title: String): Pair<String, String> {
            val m = CATEGORY.find(title) ?: return "" to title.trim()
            return m.groupValues[1].trim() to m.groupValues[2].trim()
        }

        /** The title for duplicate checks: no category, case, punctuation or extra spaces. */
        fun titleKey(title: String): String = splitCategory(title).second.lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

        /** source card id -> the first of [cards] whose brief names it (every marker a brief carries: a copy of a copy names both). */
        fun copies(cards: List<DfCard>): Map<String, String> {
            val out = linkedMapOf<String, String>()
            for (c in cards) for (m in SOURCE.findAll(c.prompt)) out.putIfAbsent(m.groupValues[1], c.id)
            return out
        }

        fun stages(p: JsonObject?): List<JsonObject> = p?.objs("stages").orEmpty()

        fun stage(p: JsonObject?, role: String) = stages(p).firstOrNull { it.field("role") == role }

        fun stageOfBoard(p: JsonObject?, boardId: String) = stages(p).firstOrNull { it.field("board_id") == boardId }

        fun stageIndex(p: JsonObject?, role: String) = stages(p).indexOfFirst { it.field("role") == role }

        /** A stage agent's own card (its schedule's run): not an idea. */
        fun isRunCard(p: JsonObject, c: DfCard): Boolean {
            val s = stageOfBoard(p, c.boardId) ?: return false
            val t = c.title.trim()
            return t.isNotEmpty() && (t == s.field("run_title") || t in s.strings("old_run_titles"))
        }

        fun cronOf(hhmm: String, days: List<Int>? = null): String {
            val m = TIME.matchEntire(hhmm.trim()) ?: throw DreamfallError("time must be HH:MM, not '$hhmm'")
            val on = if (days != null) ALL_DAYS.filter { it in days } else ALL_DAYS
            if (on.isEmpty()) throw DreamfallError("pick at least one weekday")
            val dow = if (on.size == 7) "*" else on.joinToString(",")
            return "${m.groupValues[2].toInt()} ${m.groupValues[1].toInt()} * * $dow"
        }

        /** The weekdays of a stage's cron (cron numbers, Monday first); every day if it has no weekday list. */
        fun daysOf(cron: String): List<Int> {
            val parts = cron.trim().split(WS)
            if (parts.size < 5 || !Regex("^[0-7](,[0-7])*$").matches(parts[4])) return ALL_DAYS
            val got = parts[4].split(",").map { it.toInt() % 7 }.toSet()
            return ALL_DAYS.filter { it in got }
        }

        fun hhmmOf(cron: String): String {
            val parts = cron.trim().split(WS)
            val h = parts.getOrNull(1)?.toIntOrNull()
            val m = parts.getOrNull(0)?.toIntOrNull()
            return if (h == null || m == null) "" else "%02d:%02d".format(h, m)
        }

        /** The pipeline's navigation logic; records from before the setting existed get the default (with their pass hint). */
        fun navigationLogic(p: JsonObject): String {
            val text = p.field("navigation_logic").trim()
            if (text.isNotEmpty()) return text
            val hint = p.field("pass_hint")
            return if (hint.isNotEmpty()) DEFAULT_NAVIGATION_LOGIC.replace("about one idea in four", hint) else DEFAULT_NAVIGATION_LOGIC
        }

        fun indent(text: String, prefix: String) = text.lines().joinToString("\n") { if (it.isNotBlank()) prefix + it else prefix.trimEnd() }

        fun categoriesText(p: JsonObject): String {
            val cats = p.strings("categories").filter { it.isNotBlank() }
            val lines = mutableListOf<String>()
            val file = p.field("categories_file")
            if (file.isNotEmpty()) {
                lines += "The categories are listed in the file `$file` of the project (read it first)."
                if (cats.isNotEmpty()) lines += "Also cover these categories:"
            }
            lines += cats.map { "- $it" }
            return lines.joinToString("\n")
        }

        fun enumerationPrompt(p: JsonObject): String {
            val s = stage(p, "enumeration")!!.field("name")
            val n = (p["per_category"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it != 0 } ?: 3
            val goal = p.field("goal").ifEmpty { "improvements of this project" }
            return """# Dreamfall · $s run (${p.field("name")})

You are the $s agent of the Dreamfall board "${p.field("name")}" (${p.field("id")}): a funnel in which your ideas are judged by
a second agent tonight, and only the person picks what gets built. Your job is the wide end of the funnel.

**What the ideas are about:** $goal

## Rules
- Ideas, not bug fixes or code fixes: new, genuine improvements a person would be glad to have.
- Work through the `dreamfall` command only (`dreamfall --help`). Do not create, move or start cards with `dieter card`
  yourself, do not touch the ${stage(p, "navigation")!!.field("name")} or ${stage(p, "implementation")!!.field("name")} boards, do not edit files and do not commit.

## Steps
1. `dreamfall run begin`
2. Learn the project as it is today: read its CLAUDE.md / README and skim the parts the ideas are about.
3. Categories:
${categoriesText(p)}
4. `dreamfall ideas` lists every idea this Dreamfall has ever had (all stages, released and transferred ones too). Do not
   repeat any of them in substance, not even reworded.
5. For every category write at least $n new ideas. For each idea write a brief to a file, in Markdown with the sections
   **Idea**, **Why it is valuable**, **Ingredients** (what exists already that it would use) and **Rough effort / risk**, then:
   `dreamfall add --category "<category>" --title "<short, concrete title>" --brief-file <file>`
   (`dreamfall add` refuses a title that already exists; then think of another idea.)
6. `dreamfall run end --note "<how many ideas in how many categories>"`
7. Report briefly: the number of ideas per category and the three you find most promising.
"""
        }

        fun navigationPrompt(p: JsonObject): String {
            val s = stage(p, "navigation")!!
            val up = stage(p, "enumeration")!!.field("name")
            val impl = stage(p, "implementation")!!
            val name = s.field("name")
            val goal = p.field("goal").ifEmpty { "improvements of this project" }
            val mail = p["mail"].truthy()
            val mailStep = if (!mail) "" else """
7. Mail the result to the person (always, also when nothing was promoted):
   `dieter mail send --subject "${p.field("name")}: <n> new ideas waiting (<YYYY-MM-DD>)" --body-file <file> --operation-id dreamfall-${p.field("id")}-<YYYY-MM-DD>`
   Body: one line with the counts, then one `### <title>` section per promoted idea with its reason and the Idea /
   Why it is valuable text of its brief, then the most notable releases with one-line reasons."""
            return """# Dreamfall · $name run (${p.field("name")})

You are the $name agent of the Dreamfall board "${p.field("name")}" (${p.field("id")}). Tonight the $up agent filled the
$up queue; you judge every idea in it. The strong ones go on to $name, where the person picks what gets built
(${impl.field("name")} is the person's stage: never create, move or start cards there).

**What the ideas are about:** $goal

## Rules
- Work through the `dreamfall` command only (`dreamfall --help`). Do not create, move or start cards with `dieter card`
  yourself, do not implement anything, do not edit files and do not commit.
- No idea may stay undecided: when you finish, the $up queue is empty.

## Steps
1. `dreamfall run begin`. If it reports that the $up run is still working, wait 5 minutes (`sleep 300`) and call
   it again. After 2 hours of waiting, end with `dreamfall run end --failed --note "$up did not finish"`.
2. Learn the project as it is today (CLAUDE.md / README, the parts the ideas touch), and see what already passed:
   `dreamfall ideas --stage ${s.field("role")} --stage ${impl.field("role")}`.
3. `dreamfall queue` prints every idea waiting in the $up queue with its brief.
4. Judge each idea by the navigation logic below. The person wrote it for this Dreamfall board: it decides, not
   your own taste. Whatever it says, never promote an idea that is already in $name or ${impl.field("name")} in substance.

${indent(navigationLogic(p), "   > ")}

5. For every idea exactly one of:
   `dreamfall promote <card> --score <1-10> --rationale "<one sentence why>"`
   `dreamfall release <card> --score <1-10> --rationale "<one sentence why not>"`
6. `dreamfall queue` must now be empty (ideas that arrived after your run began may stay; mention them).
   Then `dreamfall run end --note "<judged / promoted / released>"`.$mailStep
${if (mail) 8 else 7}. Report briefly: the counts, the promoted titles with their reasons, the two or three most notable releases.
"""
        }

        /** What a card picked for the person's stage carries besides the idea: the Dreamfall board's implementation
         * instructions and how to finish (appended to its brief) and, with the mail switch on, mail access (`--mail`,
         * fixed at creation). */
        fun implementationExtras(p: JsonObject): Pair<String, List<String>> {
            val text = p.field("implementation_instructions").trim()
            val mail = p["implementation_mail"].truthy()
            val parts = mutableListOf("## For every implementation on this Dreamfall board")
            if (text.isNotEmpty()) parts += text
            if (mail) parts += MAIL_HOWTO
            parts += FINISH_HOWTO
            return parts.joinToString("\n\n") to (if (mail) listOf("--mail") else emptyList())
        }

        private fun createdBy(actor: String, name: String, run: String) = buildJsonObject {
            put("actor", actor)
            put("name", name)
            if (run.isNotEmpty()) put("run", run)
        }

        /** A new record: version 1, [fields], an empty trail. */
        private fun record(vararg fields: Pair<String, Any?>) = JsonObject(mapOf("v" to JsonPrimitive(1)) + fields.associate { (k, v) -> k to json(v) } + ("events" to JsonArray(emptyList())))

        private fun String.paragraphs() = replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()

        /** Python's str() of a JSON value as the records hold them: "" for none. */
        private fun text(e: JsonElement?): String = when (e) {
            null, JsonNull -> ""
            is JsonPrimitive -> e.content
            else -> e.toString()
        }
    }
}

/** A Dreamfall failure: its message for the agent or the page, and the command's exit status. */
class DreamfallError(message: String, val code: Int = 1) : Exception(message)

/** A card as Dreamfall reads it, from `card show` (camelCase, under "card") or `card list` (snake_case). */
data class DfCard(
    val id: String,
    val title: String,
    val boardId: String,
    val lane: String,
    val projectId: String,
    val prompt: String,
    val provider: String,
    val model: String,
    val effort: String,
    val archived: Boolean,
    val started: Boolean,
    val checkoutId: String,
) {
    fun agent(key: String) = when (key) {
        "provider" -> provider
        "model" -> model
        else -> effort
    }

    companion object {
        fun of(json: JsonElement?): DfCard {
            val c = (json as? JsonObject)?.let { it.obj("card") ?: it } ?: JsonObject(emptyMap())
            fun g(vararg keys: String) = keys.firstNotNullOfOrNull { k -> c[k]?.takeUnless { it is JsonNull } }
            fun s(vararg keys: String) = (g(*keys) as? JsonPrimitive)?.content.orEmpty()
            return DfCard(
                id = s("id"), title = s("title"), boardId = s("board_id", "boardId"), lane = s("lane"), projectId = s("project_id", "projectId"),
                prompt = s("initial_prompt", "initialPrompt"), provider = s("provider"), model = s("model"), effort = s("effort"),
                archived = g("archived_at", "archivedAt", "archived").truthy(), started = g("initial_prompt_sent_at", "initialPromptSentAt").truthy(),
                checkoutId = s("checkout_id", "checkoutId"),
            )
        }
    }
}

// ---------------------------------------------------------------------- JSON records as Python's dicts read them
/** A field's text: a string or number as it is, "" when missing, null or a container. */
internal fun JsonObject.field(key: String): String = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.objs(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

internal fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }.orEmpty()

/** Python's truth of a JSON value: null, false, 0, "" and empty containers are false. */
internal fun JsonElement?.truthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> if (isString) content.isNotEmpty() else booleanOrNull ?: (doubleOrNull?.let { it != 0.0 } ?: true)
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
}

/** A copy with [pairs] set (an existing key keeps its place, as in a Python dict). */
internal fun JsonObject.updated(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>(this).apply { for ((k, v) in pairs) put(k, json(v)) })

private fun JsonObject.withStages(change: (JsonObject) -> JsonObject) = updated("stages" to Dreamfall.stages(this).map(change))

internal fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    is Iterable<*> -> JsonArray(v.map(::json))
    else -> JsonPrimitive(v.toString())
}

private inline fun quietly(block: () -> Unit) {
    try {
        block()
    } catch (_: DreamfallError) {
    }
}
