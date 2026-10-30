package com.getdieter.operator

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The board's state: what Home Assistant (`/api/state`, `/api/ha`, its events) and the add-on core (`/api/events`)
 * read (bridge/dieter_bridge.py's State until 0.6.431.21). From the CLI: status, projects, boards, the cards of each
 * project and the chats, listed in parallel; the cards and chats `dieter watch changes` reports changed ([ChangeWatch])
 * are re-read one by one, everything is listed every minute (every [POLL_MS] while that stream is down). Machine,
 * quota, harnesses and machines come from a slow poll of their own. A change made here (move, rename, archive, a new
 * card) is shown at once ([patch], [add]) and kept until a poll that started after it has it from the daemon. The
 * Dreamfall records and stage boards are [dreamfall]'s, merged in by [snapshot].
 */
class Board(
    private val dieter: Dieter,
    /** DIETER_HOME: the daemon's runtime file names its id and whether it is enrolled */
    private val home: File,
    private val addon: Boolean,
    private val questions: Questions = Questions(),
    /** the boards' order as dragged on the page (Settings) */
    private val boardOrder: () -> List<String> = { emptyList() },
    /** a Home Assistant event: dieter_turn_finished, dieter_card_review (nothing outside the add-on) */
    private val haEvent: (String, JsonObject) -> Unit = { _, _ -> },
    private val usage: Usage? = null,
    private val claudeAuth: (() -> JsonObject)? = null,
) {
    val dreamfall = DreamfallView(onChange = ::touch, onBoards = { refresh() })
    val watch = ChangeWatch(this)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile var rev = 0L
        private set
    private var data = buildJsonObject { put("ok", false); put("error", "noch nicht geladen") }
    @Volatile private var slow = Slow()
    private val runtimeSeen = HashMap<String, String>()     // card id -> runtime, to detect finished turns
    private val seqSeen = HashMap<String, Long>()
    private var lastFinished: JsonObject? = null            // {id, kind, title, at, lane, project}
    private var lastReview: JsonObject? = null
    private val polling = ReentrantLock()                   // one fast poll at a time: they would only queue up in the daemon
    private val wake = Flag()                               // a change was reported: poll (after the rest, see loop)
    private val hurry = Flag()                              // someone waits for that poll (refresh(wait)): rest POLL_GAP_MS only
    private var wanted = 0L                                 // refresh() requests ...
    private var polled = 0L                                 // ... covered by a finished poll (one that started after them)
    private var wantFull = false                            // the next poll lists everything ...
    private var wantIds = mutableSetOf<String>()            // ... or re-reads only these cards and chats
    @Volatile private var base: Base? = null                // the lists of the polls
    @Volatile private var where = mapOf<String, Where>()    // card/chat id -> where the last poll saw it
    @Volatile private var lastFull = 0L
    @Volatile private var projectIds = listOf<String>()     // the last poll's projects: their lists start without waiting for `project list`
    private var polledLanes = mapOf<String, String>()       // card id -> lane as the last poll saw it (without patches), for dieter_card_review
    private val patches = HashMap<String, Pair<Map<String, JsonElement>, Long>>()   // changes made here a poll may not have seen yet
    private val added = HashMap<String, Pair<JsonObject, Long>>()                     // cards and chats created here, the same for new ones
    private val noted = HashMap<String, String>()
    private val pool = Executors.newFixedThreadPool(8, Thread.ofVirtual().name("board-", 0).factory())

    private class Slow(
        val machine: JsonElement = JsonNull,
        val quota: List<JsonObject> = emptyList(),
        val harnesses: List<JsonObject> = emptyList(),
        val machines: List<JsonObject> = emptyList(),
        val claudeAuth: JsonElement = JsonNull,
        val backends: JsonArray = JsonArray(emptyList()),
    )

    /** status, projects, boards, cards per project and chats as the last poll listed or re-read them */
    private class Base(val status: JsonObject, val projects: List<JsonObject>, val boards: List<JsonObject>, val cards: Map<String, List<JsonObject>>, val chats: List<JsonObject>)

    private class Where(val kind: String, val project: String, val owner: String)

    /** What a poll read: the lists, and which cards (shown), projects (listed, null: everything) and whether the chats it read from the daemon. */
    private class Read(val base: Base, val shown: Set<String>, val listed: Set<String>?, val chatsListed: Boolean)

    /**
     * Something changed: poll again soon. [ids] = the cards and chats that changed: the poll re-reads only these (`card
     * show`, ~0.05 s for the daemon); without them it lists everything (on a Pi with ~300 cards several CPU-seconds).
     * [wait] returns once a poll that started after this call has finished, so the change is in the next state.
     */
    fun refresh(wait: Boolean = false, timeoutMs: Long = 30_000, ids: Collection<String?>? = null) {
        val named = ids?.filterNotNull()?.filter { it.isNotEmpty() }?.toSet()?.ifEmpty { null }
        val want = lock.withLock {
            wanted++
            if (named == null) wantFull = true else wantIds.addAll(named)
            wanted
        }
        if (wait) hurry.set()
        wake.set()
        if (wait) lock.withLock { awaitWhile(timeoutMs) { polled < want } }
    }

    /** Shows a change made here (move, rename, archive) at once instead of after the next poll. */
    fun patch(cid: String, vararg fields: Pair<String, Any?>) {
        val now = System.nanoTime()
        val set = fields.associate { (k, v) -> k to json(v) }
        lock.withLock {
            patches[cid] = (patches[cid]?.first.orEmpty() + set) to now
            if (data["ok"].truthy()) {
                data = data.updated(*listOf("cards", "chats").map { k -> k to data.objs(k).map { c -> if (c.field("id") == cid) JsonObject(c + set) else c } }.toTypedArray())
                bump()
            }
        }
        refresh(ids = listOf(cid))
    }

    /**
     * Shows a card or chat created here at once: waiting for a poll that started after the create took 5-10 s (one poll
     * is ~5 s on a Pi 5 with three projects, and a running one had to finish first).
     */
    fun add(card: JsonObject) {
        val key = if (card.field("kind") == "chat") "chats" else "cards"
        lock.withLock {
            added[card.field("id")] = card to System.nanoTime()
            if (data["ok"].truthy()) {
                data = data.updated(key to listOf(card) + data.objs(key).filter { it.field("id") != card.field("id") })
                bump()
            }
        }
        refresh(ids = listOf(card.field("id")))
    }

    /** (rev, the state): the polls' state with the open questions and Dreamfall's view. */
    fun snapshot(): Pair<Long, JsonObject> {
        val (r, d) = lock.withLock { rev to data }
        return r to dreamfall.decorate(d.updated("questions" to questions.pending()["questions"]))
    }

    /** Something outside the polls changed (a question arrived or was answered, a Dreamfall record): push to the pages. */
    fun touch() = lock.withLock { bump() }

    /** Waits up to [timeoutMs] for a state newer than [since]; the rev then. */
    fun waitChange(since: Long, timeoutMs: Long): Long = lock.withLock {
        awaitWhile(timeoutMs) { rev == since }
        rev
    }

    /**
     * The live state as Server-Sent Events: "state" at once and whenever it changed, "ping" every 15 s otherwise.
     * [fields] (the add-on core's: questions, dreamfall, …) keeps only those keys, so a change costs a few kB instead of
     * the full state; `dreamfall_cards` is computed from it ([DreamfallView.stageCards]). A click comes as a burst of
     * changes (the move, the re-read cards, the Dreamfall records): ~10 in 1.5 s, so one push per [PUSH_GAP_MS].
     * Returns when the client has left (a send throws).
     */
    fun stream(fields: List<String>?, sse: Sse) {
        fun only(d: JsonObject) = JsonObject(fields.orEmpty().associateWith { k -> if (k == "dreamfall_cards") DreamfallView.stageCards(d) else d[k] ?: JsonNull })
        fun pick(rev: Long, d: JsonObject) = JsonObject(mapOf("rev" to JsonPrimitive(rev)) + if (fields != null) only(d) else d)
        fun ping() = sse.send("ping", buildJsonObject { put("t", epochSeconds()) })
        var (rev, data) = snapshot()
        sse.send("state", pick(rev, data))
        var sent = if (fields != null) only(data) else null
        var lastBeat = System.currentTimeMillis()
        var lastPush = lastBeat
        while (true) {
            if (waitChange(rev, 15_000) != rev) {
                (lastPush + PUSH_GAP_MS - System.currentTimeMillis()).takeIf { it > 0 }?.let(Thread::sleep)
                snapshot().let { rev = it.first; data = it.second }
                if (fields != null) {
                    val now = only(data)
                    if (now == sent) {   // nothing the subscriber asked for changed
                        if (System.currentTimeMillis() - lastBeat > 14_000) ping().also { lastBeat = System.currentTimeMillis() }
                        continue
                    }
                    sent = now
                }
                sse.send("state", pick(rev, data))
                lastPush = System.currentTimeMillis()
                lastBeat = lastPush
            } else if (System.currentTimeMillis() - lastBeat > 14_000) {
                ping()
                lastBeat = System.currentTimeMillis()
            }
        }
    }

    /** A new board order: applied to the current state at once (no CLI round trip) and pushed. */
    fun reorderBoards(order: List<String>) = lock.withLock {
        if (data.objs("boards").isNotEmpty()) {
            data = data.updated("boards" to orderBoards(data.objs("boards"), order))
            bump()
        }
    }

    private fun bump() {
        rev++
        changed.signalAll()
    }

    private fun awaitWhile(timeoutMs: Long, waiting: () -> Boolean) {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (waiting() && left > 0) left = changed.awaitNanos(left)
    }

    /** Logs a recurring poll error once per distinct message (unenrolled daemons have no quota or machines). */
    private fun note(what: String, error: Exception) {
        val message = error.message.orEmpty()
        if (synchronized(noted) { noted.put(what, message) != message }) Log.line("$what: $message")
    }

    private fun <T> Future<T>.result(): T =
        try {
            get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    /**
     * A project's cards. A project with Dreamfall stage boards is listed whole: the daemon reads every card file for
     * each `card list` whatever its filters, so the stage boards' lanes come from this list ([DreamfallView.feed])
     * instead of lists of their own; the state keeps [CARD_LIMIT] of them, as with --limit.
     */
    private fun cardList(pid: String): List<JsonObject> {
        val whole = pid in dreamfall.stageProjects()
        val cards = dieter.run("card", "list", "--project", pid, "--format", "json", "--limit", if (whole) "2000" else "$CARD_LIMIT", timeout = 30.seconds)
            .unwrap("cards").map { normCard(it) }
        if (whole) dreamfall.feed(pid, cards)
        return cards.take(CARD_LIMIT)
    }

    /**
     * status, projects, boards, cards and chats from the CLI, in parallel: one after the other they took ~7 s with three
     * projects on a Pi 5. `card list` without --project answers for one project only, so it runs per project; the last
     * poll's projects start at once, a project that is new since then once `project list` has answered. `chat list`
     * without --project lists every project's chats in ~0.4 s, where each --project call took ~2 s.
     */
    private fun fetchAll(): Base {
        val status = pool.submit<JsonElement?> { dieter.run("status", "--format", "json", timeout = 15.seconds) }
        val projectList = pool.submit<JsonElement?> { dieter.run("project", "list", "--format", "json", timeout = 15.seconds) }
        val boardList = pool.submit<JsonElement?> { dieter.run("board", "list", "--format", "json", timeout = 15.seconds) }
        val chatList = pool.submit<JsonElement?> { dieter.run("chat", "list", "--format", "json", timeout = 15.seconds) }
        val cardLists = projectIds.associateWith { pid -> pool.submit<List<JsonObject>> { cardList(pid) } }.toMutableMap()
        val projects = projectList.result().unwrap("projects")
        for (p in projects) cardLists.getOrPut(p.field("id")) { pool.submit<List<JsonObject>> { cardList(p.field("id")) } }
        val pids = projects.map { it.field("id") }.toSet()
        val base = Base(
            status = status.result() as? JsonObject ?: JsonObject(emptyMap()),
            projects = projects,
            boards = boardList.result().unwrap("boards"),
            cards = projects.associate { it.field("id") to cardLists.getValue(it.field("id")).result() },
            chats = chatList.result().unwrap("chats").map { normCard(it, "chat") }.filter { it.field("project_id") in pids },   // only the listed projects' chats
        )
        projectIds = projects.map { it.field("id") }
        return base
    }

    /**
     * Re-reads only the cards and chats that changed: this machine's one by one (`card show`, ~0.05 s for the daemon,
     * where every list costs it 1-3 CPU-seconds on a Pi), another machine's (`card show` refuses those) with their
     * project's list or the chat list. Null: list everything.
     */
    private fun fetchSome(ids: Set<String>): Read? {
        val base = base ?: return null
        val localId = localDaemon(home).first
        val show = mutableListOf<String>()
        val projects = mutableSetOf<String>()
        var chats = false
        for (cid in ids) {
            val w = where[cid]
            if (w != null && w.owner.isNotEmpty() && localId.isNotEmpty() && w.owner != localId) {
                if (w.kind == "chat") chats = true
                else if (w.project in base.cards) projects += w.project
                else return null
            } else show += cid
        }
        if (show.size > SHOW_MAX) return null
        val shownFutures = show.associateWith { cid -> pool.submit<JsonElement?> { dieter.run("card", "show", cid, timeout = 15.seconds) } }
        val lists = projects.associateWith { pid -> pool.submit<List<JsonObject>> { cardList(pid) } }
        val chatList = if (chats) pool.submit<JsonElement?> { dieter.run("chat", "list", "--format", "json", timeout = 15.seconds) } else null
        val shown = linkedMapOf<String, JsonObject?>()
        for ((cid, f) in shownFutures) {
            shown[cid] = try {
                (f.result() as? JsonObject)?.let { it.obj("card") ?: it }?.takeIf { it.isNotEmpty() }
            } catch (e: CliError) {
                if ("not found" !in e.message.orEmpty().lowercase()) return null   // e.g. a new card of another machine: its project is unknown
                null                                                               // deleted
            }
        }
        val cards = base.cards.toMutableMap().apply { for ((pid, f) in lists) put(pid, f.result()) }
        var chatRows = base.chats
        if (chatList != null) chatRows = chatList.result().unwrap("chats").map { normCard(it, "chat") }.filter { it.field("project_id") in cards }
        val gone = mutableListOf<String>()
        val fresh = mutableListOf<JsonObject>()
        for ((cid, raw) in shown) {
            val kind = if (raw != null && (raw.field("scope") == "chat" || (raw.field("boardId").isEmpty() && raw.field("board_id").isEmpty()))) "chat" else "card"
            val c = raw?.let { normCard(it, kind) }
            val known = where[cid]
            when {
                c == null || c["archived"].truthy() -> {
                    if (known?.kind == "chat") chatRows = putRow(chatRows, cid, null)
                    else if (known != null && known.project in cards) cards[known.project] = putRow(cards.getValue(known.project), cid, null)
                    gone += cid
                }
                c.field("project_id") !in cards -> return null   // a project the last `project list` did not have
                kind == "chat" -> chatRows = putRow(chatRows, cid, c)
                else -> {
                    cards[c.field("project_id")] = putRow(cards[c.field("project_id")].orEmpty(), cid, c)
                    fresh += c
                }
            }
        }
        dreamfall.putCards(fresh, gone)
        return Read(Base(base.status, base.projects, base.boards, cards, chatRows), shown.keys, projects, chats)
    }

    /** Tests: the polls' state without the questions and Dreamfall, the changes not yet confirmed, the cards created here. */
    internal fun raw(): JsonObject = lock.withLock { data }

    internal fun pendingPatches(): Set<String> = lock.withLock { patches.keys.toSet() }

    internal fun pendingAdded(): Set<String> = lock.withLock { added.keys.toSet() }

    /** Tests: the next poll re-reads exactly [ids]. */
    internal fun wantOnly(ids: Set<String>) = lock.withLock {
        wantFull = false
        wantIds = ids.toMutableSet()
    }

    /** Tests: the last full poll is [ms] older. */
    internal fun ageFullPoll(ms: Long) {
        lastFull -= ms
    }

    /** Whether the pending poll only re-reads cards and chats of this machine (`card show`, ~0.05 s each). */
    internal fun cheap(): Boolean {
        val ids = lock.withLock {
            if (wantFull || wantIds.isEmpty() || wantIds.size > SHOW_MAX || System.currentTimeMillis() - lastFull > IDLE_POLL_MS) return false
            wantIds.toList()
        }
        val localId = localDaemon(home).first
        return ids.all { val w = where[it]; w == null || w.owner.isEmpty() || localId.isEmpty() || w.owner == localId }
    }

    /**
     * One poll: what [refresh] asked for since the last one; everything when nothing was named (the timed poll) or the
     * last full poll is a minute old. Returns whether it listed (the expensive calls).
     */
    fun pollFast(): Boolean = polling.withLock {
        val (want, ids) = lock.withLock {
            val ids = wantIds.takeUnless { wantFull || it.isEmpty() || System.currentTimeMillis() - lastFull > IDLE_POLL_MS || base == null }?.toSet()
            wantFull = false
            wantIds = mutableSetOf()
            wanted to ids
        }
        try {
            poll(ids)
        } finally {
            lock.withLock {
                polled = maxOf(polled, want)
                changed.signalAll()
            }
        }
    }

    private fun poll(ids: Set<String>?): Boolean {
        val started = System.nanoTime()   // the patches' and new cards' clock
        val read = try {
            val some = ids?.let {
                try {
                    fetchSome(it)
                } catch (e: CliError) {
                    Log.line("poll: re-reading ${it.size} cards failed, listing everything: ${e.message}")
                    null
                }
            }
            some ?: Read(fetchAll(), emptySet(), null, true).also { lastFull = System.currentTimeMillis() }
        } catch (e: CliError) {
            lock.withLock {
                if (data["ok"].truthy() || data.field("error") != e.message) {
                    data = data.updated("ok" to false, "error" to e.message, "generated_at" to epochSeconds())
                    bump()
                }
            }
            return true
        }
        base = read.base
        val (status, projects, boards) = Triple(read.base.status, read.base.projects, read.base.boards)
        var cards = projects.flatMap { read.base.cards[it.field("id")].orEmpty() }
        var chats = read.base.chats
        where = cards.associate { it.field("id") to Where("card", it.field("project_id"), it.field("owner_daemon_id")) } +
            chats.associate { it.field("id") to Where("chat", it.field("project_id"), it.field("owner_daemon_id")) }
        val whereNow = where
        // whether this poll read the card from the daemon
        fun covered(cid: String): Boolean {
            val w = whereNow[cid]
            return read.listed == null || cid in read.shown || (w != null && (if (w.kind == "chat") read.chatsListed else w.project in read.listed))
        }
        // Checkouts and cards of a machine identity the account no longer knows (e.g. the add-on's daemon was removed and
        // re-enrolled) cannot be used: hide such checkouts, mark such cards as orphaned.
        val (localId, enrolled) = localDaemon(home)
        val slow = slow
        val known = if (enrolled) slow.machines.map { it.field("id") }.filter { it.isNotEmpty() }.toMutableSet().apply { if (localId.isNotEmpty()) add(localId) } else mutableSetOf()
        fun orphan(c: JsonObject) = c.updated("orphaned" to (known.isNotEmpty() && c.field("owner_daemon_id").let { it.isNotEmpty() && it !in known }))
        cards = cards.map(::orphan)
        chats = chats.map(::orphan)
        val projectName = projects.associate { it.field("id") to (it["name"] ?: JsonNull) }
        val now = epochSeconds()
        var finished: JsonObject? = null
        for (c in cards + chats) {
            val id = c.field("id")
            val prev = runtimeSeen[id]
            val seq = (c["response_seq"] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } }?.toLong()
            val prevSeq = seqSeen[id]
            // a turn ended: the card left "running", or (turns shorter than a poll) its response counter advanced
            if (c.field("runtime") != "running" && (prev == "running" || (seq != null && prevSeq != null && seq > prevSeq))) {
                finished = buildJsonObject {
                    put("id", id); put("kind", c.field("kind")); put("title", c["title"] ?: JsonNull); put("at", now)
                    put("lane", c["lane"] ?: JsonNull); put("project", projectName[c.field("project_id")] ?: JsonNull)
                }
            }
            runtimeSeen[id] = c.field("runtime")
            if (seq != null) seqSeen[id] = seq
        }
        val prevLanes = lock.withLock { if (data["ok"].truthy()) polledLanes else emptyMap() }
        var review: JsonObject? = null
        for (c in cards) {
            if (c.field("lane") == "review" && prevLanes.isNotEmpty() && prevLanes[c.field("id")].let { it != null && it != "review" }) {
                review = buildJsonObject {
                    put("id", c.field("id")); put("title", c["title"] ?: JsonNull); put("at", now); put("project", projectName[c.field("project_id")] ?: JsonNull)
                }
            }
        }
        val health = status.obj("health")
        val next = buildJsonObject {
            put("ok", true)
            put("error", null as String?)
            put("generated_at", now)
            put("daemon", buildJsonObject {
                put("health", health?.get("status") ?: JsonNull)
                put("version", health?.get("release_version") ?: JsonNull)
                put("route", status["route"] ?: JsonNull)
                put("counts", JsonObject(listOf("projects", "boards", "cards", "chats").associateWith { status[it] ?: JsonNull }))
            })
            putJsonArray("projects") {
                for (p in projects) add(buildJsonObject {
                    put("id", p["id"] ?: JsonNull); put("name", p["name"] ?: JsonNull); put("path", p["path"] ?: JsonNull); put("boards", p["boardCount"] ?: JsonNull)
                    putJsonArray("checkouts") {
                        for (c in p.objs("checkouts")) {
                            val daemonId = c.field("daemonId").ifEmpty { c.field("daemon_id") }
                            if (known.isNotEmpty() && daemonId !in known) continue
                            add(buildJsonObject {
                                put("id", c["id"] ?: JsonNull); put("name", c["name"] ?: JsonNull); put("path", c["path"] ?: JsonNull)
                                put("daemon_id", (c["daemonId"]?.takeIf { it.truthy() } ?: c["daemon_id"]) ?: JsonNull)
                            })
                        }
                    }
                })
            }
            put("boards", JsonArray(boards.map(::normBoard)))
            put("cards", JsonArray(cards))
            put("chats", JsonArray(chats))
            put("addon", addon)
            put("machine", slow.machine)
            put("quota", JsonArray(slow.quota))
            put("harnesses", JsonArray(slow.harnesses))
            put("machines", JsonArray(slow.machines))
            put("claude_auth", slow.claudeAuth)
            put("backends", slow.backends)
        }
        finished?.let { haEvent("dieter_turn_finished", it) }
        review?.let { haEvent("dieter_card_review", it) }
        lock.withLock {
            finished?.let { lastFinished = it }
            review?.let { lastReview = it }
            polledLanes = cards.associate { it.field("id") to it.field("lane") }
            val rows = linkedMapOf("cards" to cards.toMutableList(), "chats" to chats.toMutableList())
            for ((cid, entry) in added.entries.toList()) {
                val (card, at) = entry
                if (at < started && covered(cid)) {   // this poll asked the daemon after the create
                    added.remove(cid)
                    continue
                }
                val list = rows.getValue(if (card.field("kind") == "card") "cards" else "chats")
                if (list.none { it.field("id") == cid }) list.add(0, card)
            }
            for ((cid, entry) in patches.entries.toList()) {
                val (fields, at) = entry
                if (at < started && covered(cid)) {   // this poll asked the daemon after the change
                    patches.remove(cid)
                    continue
                }
                for (list in rows.values) list.replaceAll { if (it.field("id") == cid) JsonObject(it + fields) else it }
            }
            val state = next.updated(
                "cards" to rows.getValue("cards"),
                "chats" to rows.getValue("chats"),
                "last_finished" to lastFinished,
                "last_review" to lastReview,
                "boards" to orderBoards(next.objs("boards"), boardOrder()),   // under the lock: a reorder during this poll is not undone
            )
            val different = state - "generated_at" != data - "generated_at"
            data = state
            if (different) bump()
        }
        return read.listed == null || read.listed.isNotEmpty() || read.chatsListed
    }

    /** Machine, quota, machines and harnesses: on a thread of their own, so a slow quota probe (up to a minute) does not hold up the board. */
    fun pollSlow() {
        val machine = try {
            normMachine(dieter.run("machine", "info", timeout = 20.seconds))
        } catch (e: CliError) {
            note("machine", e)
            buildJsonObject { put("error", e.message) }
        }
        var quota = try {
            Usage.normQuota(dieter.run("quota", "list", "--format", "json", timeout = 20.seconds))
        } catch (e: CliError) {
            note("quota", e)
            emptyList()
        }
        usage?.let { u ->
            try {
                quota = quota + u.local(quota.map { it.field("provider") }.toSet())
            } catch (e: Exception) {
                note("quota-local", e)
            }
        }
        var machines = try {
            dieter.run("machine", "list", "--format", "json", timeout = 20.seconds).unwrap("daemons").map(::normDaemon)
        } catch (e: CliError) {
            note("machines", e)
            emptyList()
        }
        var harnesses = try {
            dieter.run("harness", "list", "--format", "json", timeout = 15.seconds).unwrap("harnesses").map(::normHarness)
        } catch (e: CliError) {
            note("harness", e)
            emptyList()
        }
        harnesses = Usage.agyModelQuota(harnesses, quota)
        if (machines.isEmpty() && machine is JsonObject && !machine["error"].truthy()) {
            // unenrolled daemon (no gateway): `machine list` is empty, so list this machine from `machine info`
            machines = listOf(buildJsonObject {
                put("id", "local")
                put("name", if (addon) JsonPrimitive("Home Assistant") else (machine["hostname"]?.takeIf { it.truthy() } ?: JsonPrimitive("local")))
                put("online", true)
                put("last_seen_at", machine["collected_at"] ?: JsonNull)
                put("version", machine["daemon_version"] ?: JsonNull)
                put("route", "local")
            })
        }
        val auth = if (addon) claudeAuth?.invoke() ?: JsonNull else JsonNull
        val backends =
            if (addon) usage?.backends((auth as? JsonObject)?.get("signed_in").truthy()) ?: JsonArray(emptyList())
            else JsonArray(quota.map { buildJsonObject { put("id", it["provider"] ?: JsonNull); put("name", it["provider"] ?: JsonNull) } })
        val facts = Slow(machine, quota, harnesses, machines, auth, backends)
        lock.withLock {
            slow = facts
            if (data["ok"].truthy()) {
                data = data.updated(
                    "machine" to facts.machine, "quota" to facts.quota, "harnesses" to facts.harnesses,
                    "machines" to facts.machines, "claude_auth" to facts.claudeAuth, "backends" to facts.backends,
                )
                bump()
            }
        }
    }

    /**
     * Fast polls: after a reported change ([refresh]), else every minute while the change stream runs and every [POLL_MS]
     * without it. A poll starts [POLL_GAP_MS] after the previous one at the earliest (a burst of changes is one poll), and
     * after a poll that listed, [POLL_REST] times as long as that one took, unless all it has to do is re-read this
     * machine's cards or someone waits for it. On a Pi with ~300 cards every list costs the daemon 1-3 CPU-seconds, and
     * with 0.6.429.41-.45 a full poll after every change frame kept 2-4 of its cores busy.
     */
    fun loop() {
        Thread.ofVirtual().name("board-slow").start(::slowLoop)
        var last = 0L
        var rest = POLL_GAP_MS
        while (true) {
            wake.await(last + (if (watch.live) IDLE_POLL_MS else POLL_MS) - System.currentTimeMillis())
            if (!hurry.isSet() && !cheap()) hurry.await(last + rest - System.currentTimeMillis())
            (last + POLL_GAP_MS - System.currentTimeMillis()).takeIf { it > 0 }?.let(Thread::sleep)
            wake.clear()
            hurry.clear()
            val began = System.currentTimeMillis()
            val listed = try {
                pollFast()
            } catch (e: Exception) {
                Log.line("poll: $e")
                true
            }
            last = System.currentTimeMillis()
            rest = if (listed) maxOf(POLL_GAP_MS, ((last - began) * POLL_REST).toLong()) else POLL_GAP_MS
        }
    }

    private fun slowLoop() {
        while (true) {
            try {
                pollSlow()
            } catch (e: Exception) {
                Log.line("slow poll: $e")
            }
            // the first `harness list` / `machine info` after a daemon start often time out: retry quickly while the
            // catalog or the machine list is empty (else Machines shows "No machines" for a whole slow round)
            val s = slow
            Thread.sleep(if (s.harnesses.isNotEmpty() && s.machines.isNotEmpty()) SLOW_POLL_MS else 10_000)
        }
    }

    /** Python's threading.Event: set, cleared, waited for. */
    private class Flag {
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private var set = false

        fun set() = lock.withLock {
            set = true
            condition.signalAll()
        }

        fun clear() = lock.withLock { set = false }

        fun isSet() = lock.withLock { set }

        fun await(ms: Long) = lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(ms)
            while (!set && left > 0) left = condition.awaitNanos(left)
        }
    }

    companion object {
        private fun env(key: String, default: Double) = System.getenv(key)?.toDoubleOrNull() ?: default
        val POLL_MS = (env("BRIDGE_POLL_S", 8.0) * 1000).toLong()            // board poll while the change stream is down
        val IDLE_POLL_MS = (env("BRIDGE_IDLE_POLL_S", 60.0) * 1000).toLong() // full poll (safety net) while `dieter watch changes` runs
        val POLL_GAP_MS = (env("BRIDGE_POLL_GAP_S", 1.0) * 1000).toLong()    // rest between two polls (a burst of changes is one poll)
        val POLL_REST = env("BRIDGE_POLL_REST", 4.0)                         // after a poll that listed: rest this many times as long as it took
        val PUSH_GAP_MS = (env("BRIDGE_PUSH_GAP_S", 0.3) * 1000).toLong()    // the events stream: at most one state push per this long
        val SLOW_POLL_MS = (env("BRIDGE_SLOW_POLL_S", 60.0) * 1000).toLong()
        const val SHOW_MAX = 24        // changed cards re-read one by one (`card show`); more, and the poll lists everything
        const val CARD_LIMIT = 200     // cards per project in the state (the daemon sorts by lane name, then position)
        val LANES = listOf("todo", "running", "review", "done")

        /** Until `dieter status` reports the daemon healthy (the operator starts and supervises it); logs its release. */
        fun waitHealthy(dieter: Dieter, timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                try {
                    val health = (dieter.run("status", "--format", "json", timeout = 10.seconds) as? JsonObject)?.obj("health")
                    if (health?.field("status") == "ok") {
                        Log.line("daemon: healthy, release ${health.field("release_version")}")
                        return true
                    }
                } catch (_: CliError) {
                }
                Thread.sleep(2000)
            }
            Log.line("daemon: not healthy after ${timeoutMs / 1000} s")
            return false
        }

        /** Now in seconds since the epoch with milliseconds, written as a plain number (Python's time.time()). */
        fun epochSeconds(): JsonPrimitive = JsonPrimitive(java.math.BigDecimal.valueOf(System.currentTimeMillis(), 3))

        /** (daemon id, enrolled) from the store's runtime file; ("", false) if unknown. An unenrolled daemon owns every record it holds. */
        fun localDaemon(home: File): Pair<String, Boolean> =
            runCatching {
                val d = Json.parseToJsonElement(home.resolve("runtime/daemon.json").readText()) as JsonObject
                d.field("daemonId") to d["enrolled"].truthy()
            }.getOrDefault("" to false)

        /** [rows] with the row of [cid] replaced by [new] in place (appended if missing, removed if [new] is null). */
        fun putRow(rows: List<JsonObject>, cid: String, new: JsonObject?): List<JsonObject> {
            if (new == null) return rows.filter { it.field("id") != cid }
            return if (rows.any { it.field("id") == cid }) rows.map { if (it.field("id") == cid) new else it } else rows + new
        }

        /** Boards in the order dragged on the page; boards not in it (new ones) follow in the daemon's order, so a new board shows up last in its project. */
        fun orderBoards(boards: List<JsonObject>, order: List<String>): List<JsonObject> {
            val rank = order.withIndex().associate { (i, id) -> id to i }
            return boards.withIndex().sortedWith(compareBy({ rank[it.value.field("id")] ?: rank.size }, { it.index })).map { it.value }
        }

        /** The first of [keys] the card has (not null): `card list` answers snake_case, `card show` and create camelCase. */
        private fun JsonObject.pick(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { k -> this[k]?.takeUnless { it is JsonNull } }

        /** `card list` sends numbers, `card show` strings: digits become a number, anything else stays. */
        private fun seq(v: JsonElement?): JsonElement {
            val text = (v as? JsonPrimitive)?.contentOrNull
            return if (text != null && text.isNotEmpty() && text.all { it.isDigit() }) JsonPrimitive(text.toLong()) else v ?: JsonNull
        }

        /** snake_case (list) and camelCase (show, create) variants -> one shape. */
        fun normCard(c: JsonObject, kind: String = "card"): JsonObject {
            val usage = c.pick("token_usage", "tokenUsage") as? JsonObject
            return buildJsonObject {
                put("kind", kind)
                put("id", c["id"] ?: JsonNull)
                put("title", c["title"]?.takeIf { it.truthy() } ?: JsonPrimitive("(ohne Titel)"))
                put("lane", if (kind == "card") c["lane"] ?: JsonNull else JsonNull)
                put("project_id", c.pick("project_id", "projectId") ?: JsonNull)
                put("board_id", c.pick("board_id", "boardId") ?: JsonNull)
                put("provider", c["provider"] ?: JsonNull)
                put("model", c["model"] ?: JsonNull)
                put("effort", c["effort"] ?: JsonNull)
                put("runtime", c["runtime"]?.takeIf { it.truthy() } ?: JsonPrimitive("idle"))
                put("workspace_mode", c.pick("workspace_mode", "workspaceMode") ?: JsonNull)
                put("branch", c.pick("worktree_branch", "worktreeBranch", "branch") ?: JsonNull)
                put("initial_prompt", c.pick("initial_prompt", "initialPrompt") ?: JsonNull)
                put("last_activity_at", c.pick("last_activity_at", "lastActivityAt") ?: JsonNull)
                put("phase_changed_at", c.pick("phase_changed_at", "phaseChangedAt") ?: JsonNull)
                put("created_at", c.pick("created_at", "createdAt") ?: JsonNull)
                put("updated_at", c.pick("updated_at", "updatedAt") ?: JsonNull)
                put("archived", c.pick("archived", "archivedAt", "archived_at").truthy())
                put("owner_daemon_id", c.pick("owner_daemon_id", "ownerDaemonId") ?: JsonNull)
                put("summary", c["summary"]?.takeIf { it.truthy() } ?: JsonPrimitive(""))
                put("initial_prompt_sent", c.pick("initial_prompt_sent_at", "initialPromptSentAt").truthy())
                put("response_seq", seq(c.pick("response_seq", "responseSeq")))
                put("seen_response_seq", seq(c.pick("seen_response_seq", "seenResponseSeq")))
                put("label_ids", c.pick("label_ids", "labelIds")?.takeIf { it.truthy() } ?: JsonArray(emptyList()))
                // `card list` has snake_case keys here (input_tokens), `card show` camelCase
                put("tokens", if (usage.isNullOrEmpty()) JsonNull else buildJsonObject {
                    for ((k, snake) in listOf("inputTokens" to "input_tokens", "outputTokens" to "output_tokens", "totalTokens" to "total_tokens")) {
                        put(k, listOf(usage[k], usage[snake]).firstOrNull { it.truthy() }?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L)
                    }
                })
            }
        }

        private fun normBoard(b: JsonObject) = buildJsonObject {
            put("id", b["id"] ?: JsonNull)
            put("name", b["name"] ?: JsonNull)
            put("project_id", b.pick("project_id", "projectId")?.takeIf { it.truthy() } ?: b["projectId"] ?: JsonNull)
            put("workflow", b["workflow"] ?: JsonNull)
            putJsonArray("lanes") { b.objs("lanes").forEach { add(it["id"] ?: JsonNull) } }
            put("publish", listOf(b["remotePublishMode"], b["remote_publish_mode"]).firstOrNull { it.truthy() } ?: JsonPrimitive("manual"))
        }

        private fun normDaemon(d: JsonObject) = buildJsonObject {
            put("id", d["id"] ?: JsonNull)
            put("name", d["name"] ?: JsonNull)
            put("online", d["online"].truthy())
            put("last_seen_at", d["lastSeenAt"] ?: JsonNull)
            put("version", d["releaseVersion"] ?: JsonNull)
            val networks = d.objs("directCandidates").map { it.field("network") }.toSortedSet() - setOf("", "loopback")
            put("route", networks.joinToString(", ").ifEmpty { "relay" })
        }

        /** effort levels are declared per harness (effort.options) and only sometimes per model */
        private fun normHarness(x: JsonObject): JsonObject {
            val efforts = x.obj("effort")?.objs("options").orEmpty().mapNotNull { o -> o["id"]?.takeIf { it.truthy() } }
            return buildJsonObject {
                put("id", x["id"] ?: JsonNull)
                put("name", x["name"] ?: JsonNull)
                put("default_model", x["defaultModel"] ?: JsonNull)
                put("efforts", JsonArray(efforts))
                putJsonArray("models") {
                    for (m in x.objs("models")) add(buildJsonObject {
                        put("id", m["id"] ?: JsonNull)
                        put("name", m["name"] ?: JsonNull)
                        put("efforts", m["efforts"]?.takeIf { it.truthy() } ?: JsonArray(efforts))
                    })
                }
            }
        }

        fun normMachine(m: JsonElement?): JsonElement {
            if (m !is JsonObject) return JsonNull
            fun long(v: JsonElement?) = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
            val gpu = m.obj("gpu")?.objs("devices")?.firstOrNull()?.let { d ->
                buildJsonObject {
                    put("name", d["name"] ?: JsonNull)
                    put("util", d["utilizationPercent"] ?: JsonNull)
                    put("mem_used", long(d["memoryUsedBytes"]))
                    put("mem_total", long(d["memoryTotalBytes"]))
                    put("temp", d["temperatureCelsius"] ?: JsonNull)
                    put("power", d["powerWatts"] ?: JsonNull)
                }
            }
            return buildJsonObject {
                put("hostname", m["hostname"] ?: JsonNull)
                put("os", "${m.pyStr("osName")} ${m.pyStr("osVersion")}")
                put("cpu", m["processor"] ?: JsonNull)
                put("cpu_percent", m["cpuUsagePercent"] ?: JsonNull)
                put("load1", m["load1"] ?: JsonNull)
                put("cores", m["logicalCpuCount"] ?: JsonNull)
                put("mem_used", long(m["memoryUsedBytes"]))
                put("mem_total", long(m["memoryTotalBytes"]))
                put("disk_free", long(m["diskFreeBytes"]))
                put("disk_total", long(m["diskTotalBytes"]))
                put("uptime_s", long(m["uptimeSeconds"]))
                put("gpu", gpu ?: JsonNull)
                put("daemon_version", m.obj("daemonBuild")?.get("releaseVersion") ?: JsonNull)
                put("collected_at", m["collectedAt"] ?: JsonNull)
            }
        }

        /** Python's f-string of a value: None for a missing one. */
        private fun JsonObject.pyStr(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: "None"
    }
}
