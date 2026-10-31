package com.getdieter.operator

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The live view of Dreamfall boards for the state (bridge/dreamfall_view.py until 0.6.431.21): every Dreamfall record
 * through one `dieter kv watch` process, and the cards of the stage boards. The project card lists stop at 200 cards
 * and a busy pipeline outgrows that in a week, so the board's polls list the project of a pipeline whole and hand it
 * over ([feed]): every lane of the stage boards, of Review and Done the newest ideas (moves append at the end of a
 * lane). Changed cards come one by one ([putCards]). The view makes no `card list` calls of its own: in
 * 0.6.429.42-.45 it listed every lane of every stage board every 10 s, and since the daemon reads all card files for
 * each list whatever its filters, that alone kept a Pi's daemon busy. Records: see [Dreamfall].
 */
class DreamfallView(private val onChange: () -> Unit = {}, private val onBoards: () -> Unit = {}) {
    private val lock = Any()
    /** key -> value (live) */
    private val records = HashMap<String, JsonObject>()
    /** the records of a watch snapshot that has not caught up yet */
    private var staging: HashMap<String, JsonObject>? = null
    @Volatile private var caughtUp = false
    /** stage board id -> lane -> its cards */
    private val cards = HashMap<String, HashMap<String, List<JsonObject>>>()

    /** `dieter kv watch` for good, again after it ends (the daemon restarted). */
    fun watch(bin: String) {
        var backoff = 2L
        while (true) {
            try {
                val process = ProcessBuilder(bin, "kv", "watch", "--namespace", Dreamfall.NS)
                    .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
                val err = StringBuilder()
                val errReader = Thread.ofVirtual().start { err.append(process.errorStream.bufferedReader().readText().take(300)) }
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val frame = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                    frame(frame)
                    backoff = 2
                }
                val rc = process.waitFor()
                errReader.join()
                Log.line("dreamfall: kv watch ended ($rc) ${err.toString().trim()}")
            } catch (e: Exception) {
                Log.line("dreamfall: kv watch: $e")
            }
            Thread.sleep(backoff * 1000)
            // at most 10 s: until the watch is back, pages show no Dreamfall boards (after an update: the daemon's start)
            backoff = minOf(backoff * 2, 10)
        }
    }

    /** One frame of the watch: a snapshot (reset … caughtUp) or changed entries. */
    internal fun frame(frame: JsonObject) {
        var changed = false
        val live: Boolean
        val boardsChanged: Boolean
        synchronized(lock) {
            val boards = stageBoards()
            if (frame["reset"].truthy()) staging = HashMap()
            val target = staging ?: records
            for (e in frame.objs("entries")) {
                val key = e.field("key").ifEmpty { null } ?: continue
                val value = Dreamfall.decodeEntry(e).first
                if (value == null) changed = target.remove(key) != null || changed
                else if (target[key] != value) {
                    target[key] = value
                    changed = true
                }
            }
            val snapshot = staging
            if (frame["caughtUp"].truthy() && snapshot != null) {
                changed = changed || snapshot != records || !caughtUp
                records.clear()
                records.putAll(snapshot)
                staging = null
                caughtUp = true
            }
            live = staging == null
            boardsChanged = live && stageBoards() != boards
        }
        if (changed && live) onChange()
        if (boardsChanged) onBoards()   // a pipeline appeared (also: the snapshot arrived after the first poll) or went
    }

    fun pipelines(): List<JsonObject> = synchronized(lock) { pipelinesLocked() }

    internal fun record(key: String): JsonObject? = synchronized(lock) { records[key] }

    /** Tests: the records as a caught-up watch delivered them, the cards of a stage board's lane. */
    internal fun seed(found: Map<String, JsonObject>) = synchronized(lock) {
        records.clear()
        records.putAll(found)
        caughtUp = true
    }

    internal fun seedLane(boardId: String, lane: String, list: List<JsonObject>) = synchronized(lock) { cards.getOrPut(boardId) { HashMap() }[lane] = list }

    /** The held cards of a stage board (every lane), or null when the board has not been read yet. */
    internal fun cardsOf(boardId: String): List<JsonObject>? = synchronized(lock) { cards[boardId]?.takeIf { it.isNotEmpty() }?.values?.flatten() }

    internal fun lane(boardId: String, lane: String): List<JsonObject>? = synchronized(lock) { cards[boardId]?.get(lane) }

    internal fun heldBoards(): Set<String> = synchronized(lock) { cards.keys.toSet() }

    /** A write made here (an action of the person), shown before the watch delivers it; null removes it. */
    fun putLocal(key: String, value: JsonObject?) {
        synchronized(lock) { if (value == null) records.remove(key) else records[key] = value }
        onChange()
    }

    private fun pipelinesLocked() = records.filter { (k, v) -> k.startsWith("p.") && v.field("id").isNotEmpty() }.values.toList()

    private fun stageBoards(): Set<String> =
        pipelinesLocked().flatMap { p -> p.objs("stages").map { it.field("board_id") } }.filter { it.isNotEmpty() }.toSet()

    /** The projects with stage boards: the board's polls list them whole and feed their cards here. */
    fun stageProjects(): Set<String> = synchronized(lock) { pipelinesLocked().map { it.field("project_id") }.filter { it.isNotEmpty() }.toSet() }

    /** Every card of a project as `card list` returned it (whole, see [stageProjects]): the lanes of its stage boards are taken from it. */
    fun feed(projectId: String, all: List<JsonObject>): Boolean {
        var changed = false
        synchronized(lock) {
            val stages = pipelinesLocked().filter { it.field("project_id") == projectId }
                .flatMap { p -> p.objs("stages").map { it.field("board_id") } }.filter { it.isNotEmpty() }.toSet()
            val lanes = stages.associateWith { LANES.associateWith { mutableListOf<JsonObject>() } }
            for (c in all) lanes[c.field("board_id")]?.get(c.field("lane"))?.add(c)
            for ((bid, byLane) in lanes) {
                for ((lane, found) in byLane) {
                    val list =
                        if (lane in setOf("review", "done"))
                            found.sortedBy { Dreamfall.parseTs(it.field("phase_changed_at").ifEmpty { it.field("updated_at") }) }.takeLast(LANE_TAIL + 20)   // run cards sit there too
                        else found.toList()
                    val held = cards.getOrPut(bid) { HashMap() }
                    if (held[lane] != list) changed = true
                    held[lane] = list
                }
            }
            val known = stageBoards()
            if (cards.keys.removeIf { it !in known }) changed = true   // boards that are no longer stages
        }
        if (changed) onChange()
        return changed
    }

    /** Cards re-read (`card show`) after a change: on a stage board they replace their old copy (in whichever lane it was), [gone] ones (archived, deleted) leave. */
    fun putCards(changed: List<JsonObject> = emptyList(), gone: List<String> = emptyList()): Boolean {
        val (mine, left) = synchronized(lock) {
            val boards = stageBoards()
            changed.filter { it.field("board_id") in boards } to gone.filter { id -> cards.values.any { lanes -> lanes.values.any { list -> list.any { it.field("id") == id } } } }
        }
        var any = if (mine.isNotEmpty()) applyLocal(cards = mine) else false
        if (left.isNotEmpty()) {
            synchronized(lock) { for (byLane in cards.values) for ((lane, list) in byLane) byLane[lane] = list.filter { it.field("id") !in left } }
            onChange()
            any = true
        }
        return any
    }

    /**
     * {board: {source card: its copy}} for the stage boards held (every marker a brief carries: a copy of a copy names
     * both), so a transfer finds a copy made earlier without listing the board.
     */
    fun knownCopies(): Map<String, Map<String, String>> = synchronized(lock) {
        cards.filterValues { it.isNotEmpty() }.mapValues { (_, lanes) ->
            val copies = linkedMapOf<String, String>()
            for (list in lanes.values) for (c in list) {
                for (m in MARKER.findAll(c.field("initial_prompt").ifEmpty { c.field("prompt") })) copies.putIfAbsent(m.groupValues[1], c.field("id"))
            }
            copies
        }
    }

    /**
     * A change made here, shown before the boards are read again: [lanes] = card id -> its lane now (the card moves
     * between the lane lists of its board), [cards] = new or re-read cards (replace or add). The re-read of the moved
     * cards confirms it; this way the person's action is in the next state in milliseconds.
     */
    fun applyLocal(lanes: Map<String, String> = emptyMap(), cards: List<JsonObject> = emptyList()): Boolean {
        var changed = false
        synchronized(lock) {
            for (c in cards) {
                val (bid, lane) = c.field("board_id") to c.field("lane")
                if (bid.isEmpty() || lane.isEmpty()) continue
                val byLane = this.cards.getOrPut(bid) { HashMap() }
                val prev = byLane.values.flatten().firstOrNull { it.field("id") == c.field("id") }
                if (prev == c) continue
                for ((l, list) in byLane) byLane[l] = list.filter { it.field("id") != c.field("id") }
                byLane[lane] = byLane[lane].orEmpty() + c
                changed = true
            }
            for ((cid, lane) in lanes) {
                for (byLane in this.cards.values) {
                    val (from, hit) = byLane.firstNotNullOfOrNull { (l, list) -> list.firstOrNull { it.field("id") == cid }?.let { l to it } } ?: continue
                    if (from == lane) continue
                    byLane[from] = byLane.getValue(from).filter { it.field("id") != cid }
                    val now = nowIso()
                    byLane[lane] = byLane[lane].orEmpty().filter { it.field("id") != cid } + hit.updated("lane" to lane, "phase_changed_at" to now, "updated_at" to now)
                    changed = true
                }
            }
        }
        if (changed) onChange()
        return changed
    }

    /**
     * The state plus "dreamfall": pipelines, compact records of the cards on the page, latest runs; the stage boards'
     * cards are merged into the state's cards (the newer copy wins).
     */
    fun decorate(data: JsonObject): JsonObject {
        if (!data["ok"].truthy()) return data
        val (pipelines, extra, records) = synchronized(lock) {
            val pipelines = pipelinesLocked()
            val stageBoards = pipelines.flatMap { p -> p.objs("stages").map { it.field("board_id") } }.toSet()
            Triple(pipelines, stageBoards.flatMap { cards[it]?.values?.flatten().orEmpty() }, HashMap(records))
        }
        val byId = linkedMapOf<String, JsonObject>()
        for (c in data.objs("cards")) byId[c.field("id")] = c
        for (c in extra) {
            val have = byId[c.field("id")]
            if (have == null || Dreamfall.parseTs(c.field("updated_at")) > Dreamfall.parseTs(have.field("updated_at")))
                byId[c.field("id")] = c.updated("orphaned" to (have?.get("orphaned")?.truthy() ?: false))
        }
        fun verdict(v: JsonObject?) = v?.let { JsonObject(VERDICT_KEYS.associateWith { k -> it[k] ?: JsonNull }) } ?: JsonNull
        val compact = buildJsonObject {
            for (cid in byId.keys) {
                val r = records["c.$cid"] ?: continue
                val source = r.field("source").ifEmpty { null }?.let { records["c.$it"] }
                val events = r.objs("events")
                put(cid, buildJsonObject {
                    put("source_verdict", verdict(source?.obj("verdict")))
                    put("stage", r["stage"] ?: JsonNull)
                    put("category", r.field("category"))
                    put("source", r["source"] ?: JsonNull)
                    put("transferred_to", r["transferred_to"] ?: JsonNull)
                    put("created_by", r["created_by"] ?: JsonNull)
                    put("verdict", verdict(r.obj("verdict")))
                    put("events", events.size)
                    put("u", events.lastOrNull()?.get("at") ?: JsonNull)
                })
            }
        }
        val runs = records.filter { (k, r) -> k.startsWith("r.") && r.field("pipeline").isNotEmpty() }.values
            .groupBy { it.field("pipeline") }
            .mapValues { (_, list) -> JsonArray(list.sortedBy { Dreamfall.parseTs(it.field("started_at")) }.takeLast(12)) }
        // one idea, however many stages it reached: count the cards an idea started as (no source), not its copies
        val ideas = records.filter { (k, r) -> k.startsWith("c.") && r.field("pipeline").isNotEmpty() && !r["source"].truthy() }.values
            .groupingBy { it.field("pipeline") }.eachCount()
        return data.updated(
            "cards" to byId.values.toList(),
            "dreamfall" to buildJsonObject {
                put("pipelines", JsonArray(pipelines.sortedBy { it.field("created_at") }))
                put("records", compact)
                put("runs", JsonObject(runs))
                put("ready", caughtUp)
                put("ideas", JsonObject(ideas.mapValues { JsonPrimitive(it.value) }))
                put("defaults", buildJsonObject { put("navigation_logic", Dreamfall.DEFAULT_NAVIGATION_LOGIC) })
            },
        )
    }

    /** The full trail of a card and its provenance chain (source cards back to the first stage, copies forward). */
    fun detail(cid: String, all: List<JsonObject>): JsonObject {
        val records = synchronized(lock) { HashMap(records) }
        val byId = all.associateBy { it.field("id") }
        fun node(x: String): JsonObject {
            val r = records["c.$x"] ?: JsonObject(emptyMap())
            val c = byId[x]
            return buildJsonObject {
                put("card", x)
                put("title", c?.field("title").orEmpty())
                put("lane", c?.get("lane") ?: JsonNull)
                put("board_id", c?.get("board_id") ?: JsonNull)
                put("stage", r["stage"] ?: JsonNull)
                put("created_by", r["created_by"] ?: JsonNull)
                put("created_at", r["created_at"]?.takeIf { it.truthy() } ?: c?.get("created_at") ?: JsonNull)
                put("verdict", r["verdict"] ?: JsonNull)
                put("known", c != null)
            }
        }
        val rec = records["c.$cid"]
        val chain = ArrayDeque<JsonObject>()
        val seen = mutableSetOf(cid)
        var x = rec?.field("source").orEmpty()
        while (x.isNotEmpty() && x !in seen && chain.size < 10) {
            seen += x
            chain.addFirst(node(x))
            x = records["c.$x"]?.field("source").orEmpty()
        }
        chain.addLast(node(cid).updated("self" to true))
        x = rec?.field("transferred_to").orEmpty()
        while (x.isNotEmpty() && x !in seen && chain.size < 12) {
            seen += x
            chain.addLast(node(x))
            x = records["c.$x"]?.field("transferred_to").orEmpty()
        }
        val events = chain.flatMap { n ->
            records["c.${n.field("card")}"]?.objs("events").orEmpty().map { it.updated("card" to n.field("card"), "card_stage" to (n["stage"] ?: JsonNull)) }
        }
            // one copy's "created" and its source's "transferred" describe the same step: keep the source's
            .filterNot { it.field("kind") == "created" && it.field("from").isNotEmpty() && it.field("from") in seen }
            .sortedBy { Dreamfall.parseTs(it.field("at")) }
        return buildJsonObject {
            put("card", cid)
            put("record", rec ?: JsonNull)
            put("chain", JsonArray(chain.toList()))
            put("events", JsonArray(events))
            put("brief", byId[cid]?.get("initial_prompt") ?: JsonNull)
        }
    }

    companion object {
        /** newest released or transferred ideas per stage shown on the page */
        const val LANE_TAIL = 40
        val LANES = listOf("todo", "running", "review", "done")
        private val MARKER = Regex("${Dreamfall.MARKER} ([A-Za-z0-9_-]+)")
        private val VERDICT_KEYS = listOf("kind", "rationale", "score", "actor", "name", "at", "run")

        fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

        /**
         * The open cards on Dreamfall stage boards (ideas and run cards) with the start of their brief: what the add-on
         * core draws Dreamfall boards from, without the rest of the cards (the events stream's `dreamfall_cards`).
         */
        fun stageCards(data: JsonObject): JsonElement {
            val boards = data.obj("dreamfall")?.objs("pipelines").orEmpty().flatMap { p -> p.objs("stages").map { it.field("board_id") } }.toSet()
            return JsonArray(
                data.objs("cards").filter { it.field("board_id") in boards && !it["archived"].truthy() }
                    .map { it.updated("initial_prompt" to it.field("initial_prompt").take(1200)) },
            )
        }
    }
}
