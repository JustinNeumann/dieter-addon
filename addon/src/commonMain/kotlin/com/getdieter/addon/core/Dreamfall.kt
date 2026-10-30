package com.getdieter.addon.core

import com.getdieter.addon.v1.AgentOption
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.CreateDreamfall
import com.getdieter.addon.v1.DecideIdea
import com.getdieter.addon.v1.DreamfallFormSlice
import com.getdieter.addon.v1.DreamfallRun
import com.getdieter.addon.v1.DreamfallSettings
import com.getdieter.addon.v1.DreamfallSlice
import com.getdieter.addon.v1.DreamfallStage
import com.getdieter.addon.v1.DreamfallSummary
import com.getdieter.addon.v1.DreamfallsSlice
import com.getdieter.addon.v1.IdeaAction
import com.getdieter.addon.v1.IdeaCard
import com.getdieter.addon.v1.IdeaGroup
import com.getdieter.addon.v1.IdeaMeta
import com.getdieter.addon.v1.IdeaSlice
import com.getdieter.addon.v1.ModelOption
import com.getdieter.addon.v1.Option
import com.getdieter.addon.v1.ProvenanceStep
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.StageKind
import com.getdieter.addon.v1.Tone
import com.getdieter.addon.v1.TrailEvent
import com.getdieter.addon.v1.Update
import com.getdieter.addon.v1.Verdict
import com.getdieter.core.presentation.AgeUnit
import com.getdieter.core.presentation.Ages
import com.getdieter.core.runtime.Timestamps
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Dreamfall boards, the add-on's idea funnel: an Enumeration agent writes ideas every night, a
 * Navigation agent judges them, and the person picks what gets built on Implementation. The
 * add-on's operator (operator/Dreamfall.kt) keeps the records and carries out the actions; the
 * bridge streams the records and the cards of the stage boards. This part of [AddonCore] decides
 * what a board, an idea and the settings form show, and runs the person's decisions. It is called
 * on the core's dispatcher.
 */
internal class Dreamfall(
    private val bridge: Bridge,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    /** The UTC offset of a time zone (an IANA name; empty for the machine's) at an instant, for "today 02:00". */
    private val offsetSeconds: (Instant, String) -> Int,
    /** The time zone a new Dreamfall board starts with (an IANA name). */
    private val timezone: () -> String,
) {
    /** Bumped on every change; every observation computes its slice again. */
    private val version = MutableStateFlow(0L)
    private var loaded = false
    /** The bridge's state revision of the last stream value. */
    private var rev = 0L
    private var pipelines: List<JsonObject> = emptyList()
    private var records: Map<String, JsonObject> = emptyMap()
    private var runs: Map<String, List<JsonObject>> = emptyMap()
    private var ideaCounts: Map<String, Int> = emptyMap()
    private var defaultLogic = ""
    /** The bridge has read every record since its start. */
    private var ready = true
    private var cards: Map<String, JsonObject> = emptyMap()
    private var projects: List<JsonObject> = emptyList()
    private var boards: List<JsonObject> = emptyList()
    private var harnesses: List<JsonObject> = emptyList()
    /** Cards with a decision under way, and what they say meanwhile. */
    private val busy = mutableMapOf<String, String>()
    private val details = mutableMapOf<String, Detail>()

    private class Detail(val want: String, val json: JsonObject? = null, val error: String = "", val loading: Boolean = false)

    /** Takes the bridge's stream: the Dreamfall records, the stage boards' cards, projects, boards, agents. */
    fun ingest(state: JsonObject) {
        val df = state["dreamfall"] as? JsonObject
        pipelines = df.objects("pipelines")
        records = df?.get("records").obj()?.mapNotNull { (k, v) -> v.obj()?.let { k to it } }?.toMap().orEmpty()
        runs = df?.get("runs").obj()?.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { it.obj() }.orEmpty() }.orEmpty()
        ideaCounts = df?.get("ideas").obj()?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }.orEmpty()
        defaultLogic = df?.get("defaults").obj().str("navigation_logic")
        ready = df == null || df.str("ready") != "false"
        cards = state.objects("dreamfall_cards").associateBy { it.str("id") }
        projects = state.objects("projects")
        boards = state.objects("boards")
        harnesses = state.objects("harnesses")
        rev = state.str("rev").toLongOrNull() ?: rev
        loaded = true
        version.value++
    }

    /** Ages ("3h") move on while nobody changes anything. */
    private val minutes: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60.seconds)
        }
    }

    fun updates(slice: Slice, scope: String): Flow<Update>? =
        when (slice) {
            Slice.SLICE_DREAMFALLS -> combine(version, minutes) { _, _ -> Update(dreamfalls = list()) }
            Slice.SLICE_DREAMFALL -> combine(version, minutes) { _, _ -> Update(dreamfall = board(scope)) }
            Slice.SLICE_IDEA -> combine(version, minutes) { _, _ -> Update(idea = idea(scope)) }
            Slice.SLICE_DREAMFALL_FORM -> combine(version, minutes) { _, _ -> Update(dreamfall_form = form(scope)) }
            else -> null
        }

    /** Runs a Dreamfall command; null for the core's other commands. */
    suspend fun execute(command: Command, user: String): Result? {
        command.create_dreamfall?.let { return create(it, user) }
        command.update_dreamfall?.let {
            val p = pipeline(it.dreamfall_id)
            AddonRules.dreamfallProblem(it.settings ?: DreamfallSettings())?.let { invalid(it) }
            bridge.write("dreamfall/${p.str("id")}/update", settingsJson(it.settings!!))
            return Result("Dreamfall settings saved.")
        }
        command.pause_dreamfall?.let {
            val p = pipeline(it.dreamfall_id)
            bridge.write("dreamfall/${p.str("id")}/update", buildJsonObject { put("enabled", !it.paused) })
            return Result(if (it.paused) "Schedules paused." else "Schedules resumed.")
        }
        command.remove_dreamfall?.let {
            val pid = pipeline(it.dreamfall_id).str("id")
            bridge.write("dreamfall/$pid/delete")
            until { pipelines.none { p -> p.str("id") == pid } }
            return Result("Dreamfall removed; its boards stay.")
        }
        command.run_dreamfall_stage?.let {
            val p = pipeline(it.dreamfall_id)
            val stage = p.objects("stages").firstOrNull { s -> kindOf(s.str("role")) == it.stage }
            if (stage == null || stage.str("actor") != "agent" || stage.str("schedule_id").isEmpty())
                invalid("That stage has no agent schedule.")
            bridge.write("dreamfall/${p.str("id")}/run", buildJsonObject { put("stage", stage.str("role")) })
            return Result("${stage.str("name")} run started.")
        }
        command.decide_idea?.let { return decide(it, user) }
        return null
    }

    // ---- commands

    private fun pipeline(id: String): JsonObject =
        pipelines.firstOrNull { it.str("id") == id } ?: invalid("This Dreamfall board is no longer available.")

    private suspend fun create(command: CreateDreamfall, user: String): Result {
        val settings = command.settings ?: DreamfallSettings()
        if (projects.none { it.str("id") == command.project_id }) invalid("Choose the project for the Dreamfall board.")
        AddonRules.dreamfallProblem(settings)?.let { invalid(it) }
        val body =
            JsonObject(
                settingsJson(settings) +
                    buildJsonObject {
                        put("project", command.project_id)
                        putJsonObject("boards") { command.boards.forEach { (role, board) -> if (board.isNotEmpty()) put(role, board) } }
                    }
            )
        val created = bridge.write("dreamfall", body, user)
        val id = created.str("id")
        until { pipelines.any { it.str("id") == id } }
        return Result("Dreamfall board ${settings.name.trim()} created.", created_id = id)
    }

    private suspend fun decide(command: DecideIdea, user: String): Result {
        val id = command.card_id
        val card = cards[id] ?: invalid("This card is not on a Dreamfall board.")
        val (p, s) = stageOfBoard(card.str("board_id"))
        if (p == null || s == null) invalid("This card is not on a Dreamfall board.")
        val next = next(p, s.str("role"))
        val action =
            when (command.decision) {
                DecideIdea.Decision.DECISION_PICK -> "pick"
                DecideIdea.Decision.DECISION_PARK -> "park"
                DecideIdea.Decision.DECISION_PROMOTE -> "promote"
                DecideIdea.Decision.DECISION_RELEASE -> "release"
                DecideIdea.Decision.DECISION_RESTORE -> "restore"
                DecideIdea.Decision.DECISION_START -> "start"
                DecideIdea.Decision.DECISION_REVIEW -> "review"
                else -> invalid("Choose what happens to the idea.")
            }
        if (id in busy) return Result()
        busy[id] = busyLabel(command.decision, command.start)
        version.value++
        try {
            if (action == "review") {
                bridge.write("card/$id/move", buildJsonObject { put("lane", "review") }, user)
                until { cards[id]?.str("lane") == "review" }
                return Result("Moved to Review.")
            }
            val done =
                bridge.write(
                    "dreamfall/${p.str("id")}/act",
                    buildJsonObject {
                        put("action", action)
                        put("card", id)
                        put("note", command.note.trim())
                        put("workspace", if (command.worktree) "worktree" else "project")
                        put("start", command.start)
                    },
                    user,
                )
            // the state that shows the change: the card stays busy until it is there (a repeated pick changes nothing)
            val shown = done.str("rev").toLongOrNull() ?: 0L
            if (done.str("created") != "false") until { rev >= shown }
            return Result(
                when (command.decision) {
                    DecideIdea.Decision.DECISION_PICK ->
                        if (done.str("created") == "false") "Already picked."
                        else "Picked: ${if (command.start) "started" else "a Todo draft"} on ${next?.str("name") ?: "Implementation"}."
                    DecideIdea.Decision.DECISION_PROMOTE -> "Promoted."
                    DecideIdea.Decision.DECISION_RELEASE -> "Released."
                    DecideIdea.Decision.DECISION_PARK -> "Parked."
                    DecideIdea.Decision.DECISION_RESTORE -> "Back in the queue."
                    else -> ""
                }
            )
        } finally {
            busy.remove(id)
            version.value++
        }
    }

    /** Waits (a while) until the stream shows what a command did. */
    private suspend fun until(shown: () -> Boolean) {
        withTimeoutOrNull(SHOWN_LIMIT) { version.first { shown() } }
    }

    private fun settingsJson(s: DreamfallSettings): JsonObject = buildJsonObject {
        put("name", s.name.trim())
        put("goal", s.goal.trim())
        put("categories", s.categories)
        put("categories_file", s.categories_file.trim())
        put("per_category", s.per_category)
        put("navigation_logic", s.navigation_logic)
        put("implementation_instructions", s.implementation_instructions)
        put("implementation_mail", s.implementation_mail)
        put("enumeration_time", s.enumeration_time.trim())
        put("navigation_time", s.navigation_time.trim())
        putJsonArray("days") { s.days.sorted().forEach { add(JsonPrimitive(it)) } }
        put("timezone", s.timezone.trim())
        put("enabled", s.enabled)
        put("mail", s.mail)
        putJsonObject("agent") {
            put("provider", s.provider)
            put("model", s.model)
            put("effort", s.effort)
        }
    }

    // ---- the boards

    private fun stageOfBoard(boardId: String?): Pair<JsonObject?, JsonObject?> {
        for (p in pipelines) p.objects("stages").firstOrNull { it.str("board_id") == boardId }?.let { return p to it }
        return null to null
    }

    private fun next(p: JsonObject, role: String): JsonObject? {
        val stages = p.objects("stages")
        val i = stages.indexOfFirst { it.str("role") == role }
        return stages.getOrNull(i + 1)
    }

    private fun previous(p: JsonObject, s: JsonObject): JsonObject? {
        val stages = p.objects("stages")
        return stages.getOrNull(stages.indexOfFirst { it.str("board_id") == s.str("board_id") } - 1)
    }

    private fun isRun(s: JsonObject?, c: JsonObject): Boolean {
        val t = c.str("title").trim()
        return s != null && t.isNotEmpty() && (t == s.str("run_title") || t in s.strings("old_run_titles"))
    }

    private inner class Stage(val p: JsonObject, val s: JsonObject) {
        val role = s.str("role")
        val name = s.str("name")
        val human = s.str("actor") == "human"
        private val here = cards.values.filter { it.str("board_id") == s.str("board_id") && !it.bool("archived") }
        val ideas = here.filterNot { isRun(s, it) }
        val runCard = here.filter { isRun(s, it) }.maxByOrNull { at(it, "created_at") }
        val lastRun = runs[p.str("id")].orEmpty().filter { it.str("stage") == role }.lastOrNull()

        fun lane(vararg lanes: String) = ideas.filter { it.str("lane") in lanes }

        val queue = (if (human) lane("todo", "running", "review") else lane("todo", "running")).sortedByDescending { at(it, "created_at") }
    }

    private fun list(): DreamfallsSlice {
        if (!loaded) return DreamfallsSlice(loading = true)
        return DreamfallsSlice(
            loading = !ready && pipelines.isEmpty(),
            dreamfalls =
                pipelines.map { p ->
                    DreamfallSummary(
                        id = p.str("id"),
                        name = p.str("name"),
                        detail =
                            listOfNotNull(
                                    projectName(p.str("project_id")).ifEmpty { null },
                                    counts(ideaTotal(p), "idea"),
                                    if (p.str("enabled") == "false") "paused" else null,
                                )
                                .joinToString(" · "),
                        waiting = waiting(p),
                        project_id = p.str("project_id"),
                        board_ids = p.objects("stages").map { it.str("board_id") },
                        ideas = ideaTotal(p),
                    )
                },
        )
    }

    /** Ideas waiting for the person: the queue of the stage before the person's. */
    private fun waiting(p: JsonObject): Int {
        val s = p.objects("stages").firstOrNull { it.str("role") == "navigation" } ?: p.objects("stages").getOrNull(1) ?: return 0
        return Stage(p, s).lane("todo", "running").size
    }

    /** Every idea once (the card it started as), plus cards on the stage boards without a record. */
    private fun ideaTotal(p: JsonObject): Int {
        val stages = p.objects("stages").associateBy { it.str("board_id") }
        val unrecorded =
            cards.values.count { c ->
                val s = stages[c.str("board_id")]
                s != null && !c.bool("archived") && c.str("id") !in records && !isRun(s, c)
            }
        return (ideaCounts[p.str("id")] ?: 0) + unrecorded
    }

    private fun board(scope: String): DreamfallSlice {
        if (!loaded) return DreamfallSlice(loading = true)
        val p =
            pipelines.firstOrNull { it.str("id") == scope }
                ?: pipelines.firstOrNull { p -> p.objects("stages").any { it.str("board_id") == scope } }
                ?: return if (!ready) DreamfallSlice(loading = true) else DreamfallSlice(missing = true, error = "This Dreamfall board is no longer available.")
        val stages = p.objects("stages").map { Stage(p, it) }
        val paused = p.str("enabled") == "false"
        return DreamfallSlice(
            id = p.str("id"),
            name = p.str("name"),
            subtitle = listOf(projectName(p.str("project_id")), "Dreamfall", counts(ideaTotal(p), "idea")).filter { it.isNotEmpty() }.joinToString(" · "),
            project_id = p.str("project_id"),
            paused = paused,
            stages = stages.mapIndexed { i, st -> stage(st, stages.getOrNull(i - 1), paused) },
            first = kindOf((stages.firstOrNull { it.role == "navigation" } ?: stages.firstOrNull())?.role.orEmpty()),
        )
    }

    private fun stage(st: Stage, before: Stage?, paused: Boolean): DreamfallStage {
        val p = st.p
        val time = hhmm(st.s.str("cron"))
        val schedule = if (time.isNotEmpty()) "${whenLabel(st.s.str("cron"))} $time" else "on demand"
        val running = if (st.human) st.lane("running") else emptyList()
        val finished = running.count(::finished)
        return DreamfallStage(
            kind = kindOf(st.role),
            name = st.name,
            board_id = st.s.str("board_id"),
            human = st.human,
            waiting = st.queue.size,
            actor =
                if (st.human) "You decide · agents never create or move cards here"
                else
                    "Agent · $schedule${if (paused) " (paused)" else ""} · " +
                        if (st.role == "enumeration") "writes new ideas" else "judges ${before?.name ?: "the queue"}",
            funnel =
                if (st.human)
                    "You · human gate" +
                        (if (running.size > finished) " · ${running.size - finished} running" else "") +
                        (if (finished > 0) " · $finished finished" else "")
                else "Agent · $schedule${if (paused) " · paused" else ""}",
            passes =
                when {
                    before == null -> ""
                    st.human -> "you"
                    else -> {
                        val verdicts = before.ideas.mapNotNull { records[it.str("id")]?.get("verdict").obj().str("kind").takeIf { k -> k == "promote" || k == "release" } }
                        if (verdicts.isEmpty()) "" else "${verdicts.count { it == "promote" }} of ${verdicts.size}"
                    }
                },
            run = if (st.human) null else runLine(st, paused),
            gate =
                if (!st.human) ""
                else "Pick an idea in ${before?.name ?: "the stage before"} to copy it here as a Todo draft. Start it when you are ready.",
            gate_extra =
                if (!st.human) ""
                else
                    listOfNotNull(
                            if (p.str("implementation_instructions").isNotEmpty()) "your instructions" else null,
                            if (p.bool("implementation_mail")) "mail access" else null,
                        )
                        .takeIf { it.isNotEmpty() }
                        ?.let { "Picked cards get ${it.joinToString(" and ")}" }
                        .orEmpty(),
            groups = groups(st),
            empty =
                when {
                    st.queue.isNotEmpty() -> ""
                    st.human -> "Nothing picked yet."
                    st.role == "enumeration" -> "No ideas waiting."
                    else -> "Nothing waiting for you."
                },
            can_run = !st.human && st.s.str("schedule_id").isNotEmpty(),
            can_add = st.human || st.role == "enumeration",
        )
    }

    private fun groups(st: Stage): List<IdeaGroup> {
        val out = mutableListOf<IdeaGroup>()
        fun folded(kind: IdeaGroup.Kind, title: String, list: List<JsonObject>) {
            if (list.isEmpty()) return
            out +=
                IdeaGroup(
                    kind = kind,
                    title = title,
                    collapsible = true,
                    count = if (list.size > TAIL) "newest $TAIL of ${list.size}" else list.size.toString(),
                    cards = list.take(TAIL).map { card(st, it) },
                )
        }
        if (st.human) {
            for ((lane, title) in listOf("todo" to "Todo", "running" to "Running", "review" to "Review")) {
                val list = st.lane(lane).sortedByDescending { at(it, "updated_at") }
                if (list.isNotEmpty())
                    out += IdeaGroup(IdeaGroup.Kind.KIND_LANE, title, false, list.size.toString(), list.map { card(st, it) })
            }
            folded(IdeaGroup.Kind.KIND_DONE, "Done", st.lane("done").sortedByDescending { at(it, "phase_changed_at") })
        } else {
            if (st.queue.isNotEmpty()) out += IdeaGroup(IdeaGroup.Kind.KIND_QUEUE, "", false, st.queue.size.toString(), st.queue.map { card(st, it) })
            val enumeration = st.role == "enumeration"
            folded(
                if (enumeration) IdeaGroup.Kind.KIND_TRANSFERRED else IdeaGroup.Kind.KIND_PICKED,
                laneLabel(st.role, "done"),
                st.lane("done").sortedByDescending { at(it, "phase_changed_at") },
            )
            folded(
                if (enumeration) IdeaGroup.Kind.KIND_RELEASED else IdeaGroup.Kind.KIND_PARKED,
                laneLabel(st.role, "review"),
                st.lane("review").sortedByDescending { at(it, "phase_changed_at") },
            )
        }
        return out
    }

    private fun runLine(st: Stage, paused: Boolean): DreamfallRun {
        val runCard = st.runCard
        var r = st.lastRun
        // a newer run card whose run has not begun yet
        if (runCard != null && r != null && r.str("card") != runCard.str("id") && at(runCard, "created_at") > at(r, "started_at")) r = null
        val c = if (runCard != null && (r == null || r.str("card") == runCard.str("id"))) runCard else r?.let { cards[it.str("card")] }
        val time = hhmm(st.s.str("cron"))
        if (r == null && c == null)
            return DreamfallRun(
                DreamfallRun.Status.STATUS_NONE,
                "No run yet" + (if (time.isNotEmpty()) " · next at $time" else "") + (if (paused) " · schedules paused" else ""),
            )
        val live = c != null && active(c.str("runtime"))
        val n = r?.get("counts").obj()
        val what =
            if (st.role == "enumeration") n.int("created")?.let { "$it new ${if (it == 1) "idea" else "ideas"}" }.orEmpty()
            else {
                val promoted = n.int("promoted")
                val released = n.int("released")
                if (promoted != null || released != null) "${(promoted ?: 0) + (released ?: 0)} judged, ${promoted ?: 0} promoted" else ""
            }
        val status = r?.str("status").orEmpty()
        val time2 =
            if (r != null) {
                val end = Timestamps.parse(r.str("finished_at"))
                whenText(r.str("started_at"), st.p) + (if (end != null) " – ${clockTime(end, st.p)}" else "")
            } else whenText(c?.str("created_at").orEmpty(), st.p)
        val state =
            when {
                live -> "working"
                status == "failed" -> "failed"
                status == "running" -> "not finished"
                else -> ""
            }
        return DreamfallRun(
            status =
                when {
                    live -> DreamfallRun.Status.STATUS_WORKING
                    status == "failed" -> DreamfallRun.Status.STATUS_FAILED
                    status == "done" -> DreamfallRun.Status.STATUS_DONE
                    else -> DreamfallRun.Status.STATUS_WAITING
                },
            text = listOf("Run · $time2", state, what).filter { it.isNotEmpty() }.joinToString(" · "),
            card_id = c?.str("id").orEmpty(),
            // the agent's own note explains a run that failed or did not finish; a good run's says what the line says
            note = if (!live && (status == "failed" || status == "running")) r?.str("note").orEmpty() else "",
        )
    }

    private fun card(st: Stage, c: JsonObject): IdeaCard {
        val id = c.str("id")
        val rec = records[id]
        val (category, title) = split(c)
        val p = st.p
        val metas = mutableListOf<IdeaMeta>()
        val source = rec.str("source")
        if (source.isNotEmpty()) {
            val from = p.objects("stages").firstOrNull { it.str("role") == records[source].str("stage") } ?: previous(p, st.s)
            metas += IdeaMeta(IdeaMeta.Kind.KIND_FROM, "from ${from?.str("name") ?: "earlier stage"}")
        }
        if (rec.str("transferred_to").isNotEmpty()) metas += IdeaMeta(IdeaMeta.Kind.KIND_TO, "to ${next(p, st.role)?.str("name") ?: "next stage"}")
        val by = rec?.get("created_by").obj()
        if (by != null && source.isEmpty())
            metas +=
                if (by.str("actor") == "agent") {
                    val run = by.str("run")
                    val started = runs[p.str("id")].orEmpty().firstOrNull { it.str("card") == run }?.str("started_at").orEmpty()
                    IdeaMeta(
                        IdeaMeta.Kind.KIND_AGENT,
                        if (run.isNotEmpty()) "Run ${whenText(started, p).ifEmpty { run.take(10) }}" else by.str("name").ifEmpty { "Agent" },
                    )
                } else IdeaMeta(IdeaMeta.Kind.KIND_PERSON, "Added by ${by.str("name").ifEmpty { "you" }}")
        if (st.human && source.isNotEmpty() && by?.str("actor") == "human")
            metas += IdeaMeta(IdeaMeta.Kind.KIND_PICKED_BY, "Picked by ${by.str("name").ifEmpty { "you" }}")
        if (st.human && rec == null) metas += IdeaMeta(IdeaMeta.Kind.KIND_OUTSIDE, "Added outside Dreamfall")
        if (st.human) metas += IdeaMeta(IdeaMeta.Kind.KIND_WORKSPACE, c.str("branch").ifEmpty { if (c.str("workspace_mode") == "worktree") "Worktree" else "Project" })

        var status: String
        var tone = Tone.TONE_NEUTRAL
        var live = false
        var detail = ""
        var action: IdeaAction? = null
        val lane = c.str("lane")
        if (st.human) {
            val runtime = c.str("runtime")
            val done = finished(c)
            tone = if (done) Tone.TONE_WARNING else runtimeTone(runtime)
            live = runtimeState(runtime) == "active" || runtimeState(runtime) == "stopping"
            status =
                when {
                    runtimeTone(runtime) != Tone.TONE_NEUTRAL || runtimeBadge(runtime) != null -> runtimeLabel(runtime)
                    done -> "Finished · still in Running"
                    else -> laneLabel("implementation", lane)
                }
            detail = listOfNotNull(listOf(c.str("provider").ifEmpty { "agent" }, c.str("model")).filter { it.isNotEmpty() }.joinToString(" · "), tokens(c)).joinToString(" · ")
            action =
                when {
                    lane == "todo" && !c.bool("initial_prompt_sent") -> decision(DecideIdea.Decision.DECISION_START, "Start", primary = true)
                    done -> decision(DecideIdea.Decision.DECISION_REVIEW, "Review")
                    else -> null
                }
        } else if (lane == "todo" || lane == "running") {
            val next = next(p, st.role)
            if (next?.str("actor") == "human") {
                status = "Waiting for you"
                action = decision(DecideIdea.Decision.DECISION_PICK, "Pick", next = next, title = title)
            } else status = "Waiting for ${next?.str("name") ?: "the next stage"}" + hhmm(next?.str("cron").orEmpty()).let { if (it.isEmpty()) "" else " · $it" }
        } else status = "${laneLabel(st.role, lane)} · ${age(c)}"
        return IdeaCard(
            id = id,
            category = category,
            title = title,
            age = age(c),
            excerpt = excerpt(c),
            verdict = verdict(rec),
            metas = metas,
            status = status,
            status_tone = tone,
            live = live,
            detail = detail,
            action = action,
            busy = busy[id].orEmpty(),
            opens_conversation = st.human,
        )
    }

    private fun decision(
        decision: DecideIdea.Decision,
        label: String,
        primary: Boolean = false,
        next: JsonObject? = null,
        title: String = "",
    ): IdeaAction {
        val ask =
            when (decision) {
                DecideIdea.Decision.DECISION_PICK -> "Pick for ${next?.str("name") ?: "Implementation"}" to title
                DecideIdea.Decision.DECISION_PROMOTE -> "Promote" to "$title. Copy it to the next stage; the original leaves the queue."
                DecideIdea.Decision.DECISION_RELEASE -> "Release" to "$title. It leaves the queue and stays on the board as released."
                DecideIdea.Decision.DECISION_PARK -> "Park" to "$title. It leaves your queue for later; you can pick it any time."
                DecideIdea.Decision.DECISION_RESTORE -> "Back to the queue" to "$title. It waits in its stage's queue again."
                else -> null
            }
        return IdeaAction(
            decision = decision,
            label = label,
            primary = primary,
            asks = ask != null,
            ask_title = ask?.first.orEmpty(),
            ask_text = ask?.second.orEmpty(),
            busy = busyLabel(decision, false),
        )
    }

    private fun verdict(rec: JsonObject?): Verdict? {
        val v = rec?.get("verdict").obj() ?: rec?.get("source_verdict").obj() ?: return null
        val kind = v.str("kind").ifEmpty { return null }
        val good = kind == "promote" || kind == "pick"
        val score = v.str("score")
        return Verdict(
            kind =
                when (kind) {
                    "pick" -> Verdict.Kind.KIND_PICK
                    "release" -> Verdict.Kind.KIND_RELEASE
                    "park" -> Verdict.Kind.KIND_PARK
                    else -> Verdict.Kind.KIND_PROMOTE
                },
            good = good,
            label = mapOf("promote" to "Promoted", "pick" to "Picked", "release" to "Released", "park" to "Parked")[kind] ?: kind,
            text = listOf(if (score.isNotEmpty()) "$score/10" else "", v.str("rationale")).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    // ---- an idea

    private fun idea(cardId: String): IdeaSlice {
        if (!loaded) return IdeaSlice(loading = true, card_id = cardId)
        val c = cards[cardId]
        val (p, s) = stageOfBoard(c?.str("board_id"))
        if (c == null || p == null || s == null)
            return if (!ready) IdeaSlice(loading = true, card_id = cardId)
            else IdeaSlice(missing = true, card_id = cardId, error = "This idea is no longer on a Dreamfall board.")
        val rec = records[cardId]
        val lane = c.str("lane")
        loadDetail(cardId, "${rec.str("u")}:${rec.str("events")}:$lane")
        val detail = details[cardId]
        val d = detail?.json
        val st = Stage(p, s)
        val next = next(p, st.role)
        val (category, title) = split(c)
        val actions = mutableListOf<IdeaAction>()
        if (!st.human && (lane == "todo" || lane == "running")) {
            if (next?.str("actor") == "human")
                actions += listOf(decision(DecideIdea.Decision.DECISION_PICK, "Pick", true, next, title), decision(DecideIdea.Decision.DECISION_PARK, "Park", title = title))
            else
                actions += listOf(decision(DecideIdea.Decision.DECISION_PROMOTE, "Promote", title = title), decision(DecideIdea.Decision.DECISION_RELEASE, "Release", title = title))
        }
        if (!st.human && lane == "review") {
            actions += decision(DecideIdea.Decision.DECISION_RESTORE, "Back to the queue", title = title)
            actions +=
                if (next?.str("actor") == "human") decision(DecideIdea.Decision.DECISION_PICK, "Pick anyway", next = next, title = title)
                else decision(DecideIdea.Decision.DECISION_PROMOTE, "Promote anyway", title = title)
        }
        if (st.human && lane == "todo" && !c.bool("initial_prompt_sent")) actions += decision(DecideIdea.Decision.DECISION_START, "Start", primary = true)
        val stageName = { role: String -> p.objects("stages").firstOrNull { it.str("role") == role }?.str("name").orEmpty() }
        val provenance =
            d.objects("chain").mapIndexed { i, n ->
                val stage = p.objects("stages").firstOrNull { it.str("role") == n.str("stage") }
                val by = n["created_by"].obj()
                val verb = if (i == 0) (if (by.str("actor") == "agent") "Found" else "Added") else if (stage?.str("actor") == "human") "Picked" else "Promoted"
                val how =
                    if (by != null)
                        "$verb by ${by.str("name").ifEmpty { if (by.str("actor") == "agent") "agent" else "you" }}" +
                            whenText(n.str("created_at"), p).let { if (it.isEmpty()) "" else " · $it" }
                    else if (n.bool("known")) ""
                    else "not on the page"
                val v = n["verdict"].obj().str("kind")
                val verdictText = mapOf("promote" to "promoted", "pick" to "picked", "release" to "released", "park" to "parked")[v] ?: v
                ProvenanceStep(
                    card_id = n.str("card"),
                    stage = kindOf(n.str("stage")),
                    title = "${stageName(n.str("stage")).ifEmpty { "Card" }} · ${if (n.bool("self")) "this card" else n.str("card").take(10)}",
                    detail = how + if (verdictText.isNotEmpty()) " · $verdictText" else "",
                    current = n.bool("self"),
                )
            }
        val trail =
            if (d == null) emptyList()
            else
                d.objects("events").map { e -> trailEvent(e, p, stageName) } +
                    TrailEvent(
                        kind = TrailEvent.Kind.KIND_NOW,
                        title = "Now: ${laneLabel(st.role, lane)} on ${st.name}",
                        detail =
                            if (runtimeState(c.str("runtime")) != "idle") runtimeLabel(c.str("runtime"))
                            else age(c).let { if (it == "now") "changed just now" else if (it.isNotEmpty()) "changed $it ago" else "" },
                    )
        return IdeaSlice(
            card_id = cardId,
            dreamfall_id = p.str("id"),
            title = title,
            subtitle = listOf(projectName(p.str("project_id")), p.str("name"), st.name).filter { it.isNotEmpty() }.joinToString(" · "),
            stage = kindOf(st.role),
            stage_label = "${st.name} · ${laneLabel(st.role, lane)}",
            category = category,
            actions = actions,
            busy = busy[cardId].orEmpty(),
            verdict = verdict(rec),
            brief = (d.str("brief").ifEmpty { c.str("initial_prompt") }).replace(COMMENT, "").trim(),
            provenance = provenance,
            trail = trail,
            trail_loading = d == null && detail?.error.isNullOrEmpty(),
            error = detail?.error.orEmpty(),
        )
    }

    private fun trailEvent(e: JsonObject, p: JsonObject, stageName: (String) -> String): TrailEvent {
        val kind = e.str("kind")
        val human = e.str("actor") == "human"
        val to = if (e.str("stage").isNotEmpty() && (kind == "transferred" || kind == "picked")) stageName(e.str("stage")) else ""
        val here = stageName(e.str("card_stage").ifEmpty { e.str("stage") })
        val title =
            when (kind) {
                "created" -> if (human) "Added to $here" else "Created on $here"
                "judged" -> "Judged: ${e.str("verdict")}"
                "released" -> "Released"
                "parked" -> "Parked"
                "transferred" -> "Transferred to $to, source released"
                "picked" -> if (to.isNotEmpty()) "Picked for $to" else "Picked"
                "restored" -> "Back in the queue"
                "started" -> "Started"
                else -> kind
            }
        val reason = e.str("rationale").ifEmpty { e.str("note") }
        val score = e.str("score")
        return TrailEvent(
            kind =
                when (kind) {
                    "created" -> TrailEvent.Kind.KIND_CREATED
                    "judged" -> TrailEvent.Kind.KIND_JUDGED
                    "released" -> TrailEvent.Kind.KIND_RELEASED
                    "parked" -> TrailEvent.Kind.KIND_PARKED
                    "transferred" -> TrailEvent.Kind.KIND_TRANSFERRED
                    "picked" -> TrailEvent.Kind.KIND_PICKED
                    "restored" -> TrailEvent.Kind.KIND_RESTORED
                    "started" -> TrailEvent.Kind.KIND_STARTED
                    else -> TrailEvent.Kind.KIND_OTHER
                },
            human = human,
            title = title,
            detail =
                listOf(
                        e.str("name").ifEmpty { if (human) "You" else "Agent" },
                        e.str("run").let { if (it.isEmpty()) "" else "run ${it.take(10)}" },
                        whenText(e.str("at"), p),
                    )
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
            quote = if (reason.isEmpty()) "" else reason + if (score.isNotEmpty()) " Score $score/10." else "",
        )
    }

    /** Reads an idea's trail and provenance when its record or lane changed ([want]). */
    private fun loadDetail(cardId: String, want: String) {
        val have = details[cardId]
        if (have != null && (have.loading || have.want == want)) return
        details[cardId] = Detail(want, have?.json, loading = true)
        scope.launch {
            details[cardId] =
                try {
                    Detail(want, bridge.read("dreamfall/card/$cardId"))
                } catch (cancelled: CancellationException) {
                    details.remove(cardId)
                    throw cancelled
                } catch (error: Exception) {
                    Detail(want, have?.json, error = error.message ?: "The trail could not be read.")
                }
            version.value++
        }
    }

    // ---- the form

    /** [target] is "new:<project>" or a board's ID, then "@<zone>": the viewer's time zone, the default for a new board. */
    private fun form(target: String): DreamfallFormSlice {
        if (!loaded) return DreamfallFormSlice(loading = true)
        val scope = target.substringBefore('@')
        val zone = target.substringAfter('@', "").takeIf(AddonRules::isTimezone) ?: timezone()
        val agents =
            harnesses.map { h ->
                AgentOption(
                    id = h.str("id"),
                    label = h.str("name").ifEmpty { h.str("id") },
                    models =
                        h.objects("models").map { m ->
                            ModelOption(m.str("id"), m.str("name").ifEmpty { m.str("id") }, m.strings("efforts").map { Option(it, it.replaceFirstChar(Char::uppercaseChar)) })
                        },
                    default_model = h.str("default_model"),
                )
            }
        if (!scope.startsWith("new:")) {
            val p = pipelines.firstOrNull { it.str("id") == scope } ?: return DreamfallFormSlice(error = "This Dreamfall board is no longer available.")
            val enumeration = p.objects("stages").firstOrNull { it.str("role") == "enumeration" }?.str("cron").orEmpty()
            val navigation = p.objects("stages").firstOrNull { it.str("role") == "navigation" }?.str("cron").orEmpty()
            val agent = p["agent"].obj()
            return DreamfallFormSlice(
                edit = true,
                dreamfall_id = p.str("id"),
                title = "Dreamfall settings",
                project_id = p.str("project_id"),
                project_name = projectName(p.str("project_id")),
                agents = agents,
                settings =
                    DreamfallSettings(
                        name = p.str("name"),
                        goal = p.str("goal"),
                        categories = p.strings("categories").joinToString("\n"),
                        categories_file = p.str("categories_file"),
                        per_category = p.int("per_category") ?: 3,
                        navigation_logic = p.str("navigation_logic").ifEmpty { defaultLogic },
                        implementation_instructions = p.str("implementation_instructions"),
                        implementation_mail = p.bool("implementation_mail"),
                        enumeration_time = hhmm(enumeration).ifEmpty { "02:00" },
                        navigation_time = hhmm(navigation).ifEmpty { "05:00" },
                        days = days(enumeration),
                        timezone = p.str("timezone").ifEmpty { zone },
                        enabled = p.str("enabled") != "false",
                        mail = p.bool("mail"),
                        provider = agent.str("provider"),
                        model = agent.str("model"),
                        effort = agent.str("effort"),
                    ),
            )
        }
        val projectId = scope.removePrefix("new:")
        val project = projects.firstOrNull { it.str("id") == projectId }
        val taken = pipelines.flatMap { p -> p.objects("stages").map { it.str("board_id") } }.toSet()
        val agent = agents.firstOrNull { it.id == "claude-code" } ?: agents.firstOrNull()
        return DreamfallFormSlice(
            title = "New Dreamfall board",
            project_id = project?.str("id").orEmpty(),
            project_name = project?.str("name").orEmpty(),
            projects = projects.map { Option(it.str("id"), it.str("name")) },
            agents = agents,
            boards =
                boards
                    .filter { it.str("project_id") == projectId && it.str("id") !in taken && "review" in it.strings("lanes") }
                    .map { Option(it.str("id"), it.str("name")) },
            settings =
                DreamfallSettings(
                    name = "Dreamfall",
                    per_category = 3,
                    navigation_logic = defaultLogic,
                    enumeration_time = "02:00",
                    navigation_time = "05:00",
                    days = (0..6).toList(),
                    timezone = zone,
                    enabled = true,
                    provider = agent?.id.orEmpty(),
                    model = agent?.default_model?.ifEmpty { null } ?: agent?.models?.firstOrNull()?.id.orEmpty(),
                ),
        )
    }

    // ---- labels

    private fun projectName(id: String) = projects.firstOrNull { it.str("id") == id }?.str("name").orEmpty()

    private fun split(c: JsonObject): Pair<String, String> {
        val t = c.str("title").ifEmpty { "Untitled card" }
        val m = CATEGORY.find(t)
        val category = records[c.str("id")].str("category").ifEmpty { m?.groupValues?.get(1)?.trim().orEmpty() }
        return category to (if (m != null) m.groupValues[2].trim().ifEmpty { t } else t)
    }

    /** The card's summary, or the start of its brief without headings, labels and provenance footers. */
    private fun excerpt(c: JsonObject): String {
        c.str("summary").ifEmpty { null }?.let { return it }
        val body = c.str("initial_prompt").split(FOOTER).first()
        return body
            .split("\n")
            .map { line ->
                line.replace(HEADING, "").replace(BOLD, "").replace(BULLET, "").replace(COMMENT, "").trim()
            }
            .filter { it.isNotEmpty() && !RULE.matches(it) }
            .joinToString(" ")
            .take(240)
    }

    private fun age(c: JsonObject): String {
        val at = maxOf(at(c, "updated_at"), at(c, "last_activity_at", fallback = false))
        if (at == 0L) return ""
        val span = Ages.span(clock() - Instant.fromEpochMilliseconds(at), weeks = true) ?: return "now"
        return if (span.unit == AgeUnit.MINUTES) "${span.count}min" else span.compact
    }

    private fun tokens(c: JsonObject): String? {
        val total = c["tokens"].obj().int("totalTokens") ?: return null
        if (total <= 0) return null
        val text =
            when {
                total >= 1_000_000 -> "${(total / 100_000) / 10.0}M"
                total >= 100_000 -> "${(total + 500) / 1000}k"
                total >= 1_000 -> "${((total + 50) / 100) / 10.0}k"
                else -> total.toString()
            }
        return "$text tokens"
    }

    /** "today 02:04", "yesterday 23:10", "27 Oct 05:00" in the time zone of [p]'s schedules. */
    private fun whenText(iso: String, p: JsonObject): String {
        val at = Timestamps.parse(iso) ?: return ""
        val now = clock()
        val zone = p.str("timezone")
        val day = { i: Instant -> (i.epochSeconds + offsetSeconds(i, zone)).floorDiv(86_400L) }
        val diff = day(now) - day(at)
        return when (diff) {
            0L -> "today ${clockTime(at, p)}"
            1L -> "yesterday ${clockTime(at, p)}"
            else -> {
                val (_, month, dayOfMonth) = civil(day(at))
                "$dayOfMonth ${MONTHS[month - 1]} ${clockTime(at, p)}"
            }
        }
    }

    private fun clockTime(at: Instant, p: JsonObject): String {
        val seconds = (at.epochSeconds + offsetSeconds(at, p.str("timezone"))).mod(86_400L)
        return "${(seconds / 3600).toString().padStart(2, '0')}:${(seconds % 3600 / 60).toString().padStart(2, '0')}"
    }

    /** Milliseconds of the time at [key], or of `updated_at` when it has none and [fallback]; 0 when unknown. */
    private fun at(o: JsonObject, key: String, fallback: Boolean = true): Long =
        Timestamps.parse(o.str(key).ifEmpty { if (fallback) o.str("updated_at") else "" })?.toEpochMilliseconds() ?: 0L

    private fun finished(c: JsonObject) =
        c.str("lane") == "running" && runtimeState(c.str("runtime")) == "idle" && c.bool("initial_prompt_sent") && c.str("last_activity_at").isNotEmpty()

    private fun busyLabel(decision: DecideIdea.Decision, start: Boolean): String =
        when (decision) {
            DecideIdea.Decision.DECISION_PICK -> if (start) "Picking, starting…" else "Picking…"
            DecideIdea.Decision.DECISION_PROMOTE -> "Promoting…"
            DecideIdea.Decision.DECISION_RELEASE -> "Releasing…"
            DecideIdea.Decision.DECISION_PARK -> "Parking…"
            DecideIdea.Decision.DECISION_RESTORE -> "Restoring…"
            DecideIdea.Decision.DECISION_START -> "Starting…"
            DecideIdea.Decision.DECISION_REVIEW -> "Moving…"
            else -> ""
        }

    companion object {
        /** Finished groups show their newest cards. */
        const val TAIL = 40
        private val SHOWN_LIMIT = 15.seconds
        private val CATEGORY = Regex("""^\s*\[([^\]]{1,60})\]\s*(.*)$""")
        private val FOOTER = Regex("""\n-{3,}\s*\n(?=\s*(?:Found by |\*\*(?:Promoted|Picked) from ))""")
        private val HEADING = Regex("""^#+\s*""")
        private val BOLD = Regex("""\*\*([^*]+)\*\*:?""")
        private val BULLET = Regex("""^[-*]\s+""")
        private val COMMENT = Regex("""<!--.*?-->""")
        private val RULE = Regex("""^-{3,}$""")
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val LANES =
            mapOf(
                "enumeration" to mapOf("todo" to "Queue", "running" to "Queue", "review" to "Released", "done" to "Transferred"),
                "navigation" to mapOf("todo" to "Waiting for you", "running" to "Waiting for you", "review" to "Parked", "done" to "Picked"),
                "implementation" to mapOf("todo" to "Todo", "running" to "Running", "review" to "Review", "done" to "Done"),
            )
        private val DAY_NAMES = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")

        fun laneLabel(role: String, lane: String): String = LANES[role]?.get(lane) ?: lane

        fun kindOf(role: String): StageKind =
            when (role) {
                "enumeration" -> StageKind.STAGE_ENUMERATION
                "navigation" -> StageKind.STAGE_NAVIGATION
                "implementation" -> StageKind.STAGE_IMPLEMENTATION
                else -> StageKind.STAGE_UNSPECIFIED
            }

        /** The weekdays of a cron line (0 = Sunday), all for `*`. */
        fun days(cron: String): List<Int> {
            val w = cron.trim().split(Regex("\\s+")).getOrNull(4) ?: "*"
            if (!Regex("^[0-7](,[0-7])*$").matches(w)) return (0..6).toList()
            val set = w.split(",").map { it.toInt() % 7 }.toSet()
            return (0..6).filter { it in set }
        }

        /** "daily", or the days: "Mon Wed Fri". */
        fun whenLabel(cron: String): String {
            val d = days(cron)
            return if (d.size == 7) "daily" else DAY_NAMES.filter { it.first in d }.joinToString(" ") { it.second }
        }

        /** "02:00" of a cron line "0 2 * * *". */
        fun hhmm(cron: String): String {
            val p = cron.trim().split(Regex("\\s+"))
            if (p.size < 2 || p[0].toIntOrNull() == null || p[1].toIntOrNull() == null) return ""
            return "${p[1].padStart(2, '0')}:${p[0].padStart(2, '0')}"
        }

        fun counts(n: Int, noun: String) = "$n ${if (n == 1) noun else noun + "s"}"

        /** Year, month (1-12) and day of [epochDay] (days since 1970-01-01). */
        fun civil(epochDay: Long): Triple<Long, Int, Int> {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
        }

        private fun runtimeState(r: String): String {
            val v = r.trim().lowercase()
            return when {
                v == "cancelling" -> "stopping"
                v in setOf("starting", "running", "working", "streaming", "active") -> "active"
                v in setOf("waiting", "waiting_for_user", "needs_input") -> "needs"
                v in setOf("failed", "error") -> "failed"
                else -> "idle"
            }
        }

        private fun active(r: String) = runtimeState(r) == "active" || runtimeState(r) == "stopping"

        private fun runtimeBadge(r: String): String? {
            val v = r.trim().lowercase()
            return when {
                v == "starting" -> "Starting…"
                runtimeState(v) == "stopping" -> "Stopping…"
                runtimeState(v) == "active" -> "Running"
                else -> null
            }
        }

        private fun runtimeTone(r: String): Tone {
            val v = r.trim().lowercase()
            return when (runtimeState(v)) {
                "active", "stopping" -> Tone.TONE_ACTIVE
                "needs" -> Tone.TONE_WARNING
                "failed" -> Tone.TONE_DANGER
                else ->
                    when (v) {
                        "completed", "done" -> Tone.TONE_SUCCESS
                        "cancelled", "canceled" -> Tone.TONE_DANGER
                        else -> Tone.TONE_NEUTRAL
                    }
            }
        }

        private fun runtimeLabel(r: String): String {
            runtimeBadge(r)?.let { return it }
            return when (runtimeState(r)) {
                "needs" -> "Waiting for you"
                "failed" -> "Failed"
                else -> r.trim().replace('_', ' ').lowercase().replaceFirstChar(Char::uppercaseChar).ifEmpty { "Idle" }
            }
        }
    }
}

internal fun JsonObject?.strings(key: String): List<String> =
    ((this?.get(key)) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
