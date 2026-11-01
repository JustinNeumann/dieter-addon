package com.getdieter.web

import com.getdieter.addon.AddonClient
import com.getdieter.addon.core.AddonCore
import com.getdieter.addon.core.AddonSubscription
import com.getdieter.addon.core.Bridge
import com.getdieter.addon.core.BridgeResponse
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Update
import com.getdieter.core.runtime.localUtcOffsetSeconds
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The add-on's own features for the standalone demo (`?demo=standalone`, the page on GitHub Pages): the real
 * [AddonCore], running in the page over [DemoBridge] instead of in the web host. No server is involved.
 */
class DemoAddon(scope: CoroutineScope) : AddonClient {
    private val core = AddonCore(DemoBridge(), scope).also { it.start() }

    override suspend fun dispatch(command: Command): Result = core.dispatch(command)

    override fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): AddonSubscription =
        core.observe(slice, scope, receive)
}

/**
 * The operator's JSON API answered from sample data: the `Atlas ideas` Dreamfall board on `atlas-api` (ideas on all
 * three stages, five of them waiting for a pick, one picked on Implementation), a signed-in Claude account,
 * Antigravity quota windows, an enrolled gateway, settings and agent instructions. Decisions on ideas (Pick, Park,
 * Start, Promote, Release, Back to the queue, Review) change the sample like the operator would, and settings are kept,
 * until the page reloads; what needs a real add-on (sign-ins, clones, new boards, runs) answers that this is a demo.
 */
internal class DemoBridge(now: Instant = Clock.System.now()) : Bridge {
    /** Last night's 02:00 on this device: today's, or yesterday's before 06:00, when the night's runs are done. */
    private val night: Instant = run {
        val offset = localUtcOffsetSeconds(now)
        val local = now.epochSeconds + offset
        val today = Instant.fromEpochSeconds(local - local.mod(86_400L) - offset) + 2.hours
        if (now < today + 4.hours) today - 1.days else today
    }
    private val cards = mutableListOf<MutableMap<String, JsonElement>>()
    private val records = mutableMapOf<String, MutableMap<String, JsonElement>>()
    private val events = mutableMapOf<String, MutableList<JsonObject>>()
    private val runs = mutableListOf<JsonObject>()
    private val pipelineSettings =
        mutableMapOf<String, JsonElement>(
            "id" to json(PIPELINE),
            "name" to json("Atlas ideas"),
            "project_id" to json("p-addon"),
            "goal" to json("Make the Atlas API safer to integrate and cheaper to operate."),
            "categories" to json(listOf("Developer experience", "Reliability", "Billing", "Security")),
            "per_category" to json(4),
            "navigation_logic" to json(NAVIGATION_LOGIC),
            "implementation_instructions" to json(INSTRUCTIONS),
            "implementation_mail" to json(true),
            "timezone" to json("Europe/Berlin"),
            "enabled" to json(true),
            "mail" to json(true),
            "agent" to json(mapOf("provider" to "claude-code", "model" to "sonnet", "effort" to "high")),
        )
    private val settings =
        mutableMapOf(
            "title_model" to json("claude-haiku"),
            "title_models" to
                json(
                    listOf(
                        mapOf("id" to "claude-haiku", "name" to "Claude Haiku", "available" to true),
                        mapOf("id" to "ollama:qwen3", "name" to "Ollama · qwen3", "available" to true),
                    )
                ),
            "worktree_retention_days" to json(7),
            "palette" to json("dreamfall"),
            "board_order" to json(emptyList<String>()),
        )
    private var prompts =
        JsonObject(
            mapOf(
                "template" to json(EVERY_AGENT),
                "board_skill" to json(BOARD_SKILL),
                "chat_skill" to json(CHAT_SKILL),
                "variables" to json(listOf("project.name", "project.instructions_block", "labels.instructions_block", "board", "card_title", "branch")),
                "overrides" to json(emptyList<String>()),
            )
        )
    private var rev = 1L
    private var serial = 0
    private val state: MutableStateFlow<JsonObject>

    init {
        sample()
        state = MutableStateFlow(snapshot())
    }

    override suspend fun request(method: String, path: String, body: JsonObject?, user: String, timeout: Duration): BridgeResponse =
        if (method == "GET") read(path) else write(path, body ?: JsonObject(emptyMap()))

    override fun events(fields: List<String>): Flow<JsonObject> = state

    private fun read(path: String): BridgeResponse =
        when {
            path == "gateway" ->
                ok(
                    "enrolled" to true, "state" to "connected", "trust" to "trusted", "daemon_name" to "Home Assistant",
                    "name" to "homeassistant", "url" to "https://gateway.getdieter.com",
                )
            path == "ssh" ->
                ok(
                    "public_key" to "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDemoKeyOfTheDieterAddOnDemo0000000000000 dieter@homeassistant",
                    "fingerprint" to "SHA256:dEm0fInGeRpRiNtOfThEdIeTeRaDdOnDeMo0000000",
                )
            path == "settings" -> BridgeResponse(200, JsonObject(settings))
            path == "claude/login" -> ok("auth" to CLAUDE)
            path == "prompts" -> BridgeResponse(200, prompts)
            path.startsWith("dreamfall/card/") -> trail(path.removePrefix("dreamfall/card/"))
            else -> demoOnly()
        }

    private fun write(path: String, body: JsonObject): BridgeResponse =
        when {
            path == "settings" -> {
                settings.putAll(body)
                BridgeResponse(200, JsonObject(settings))
            }
            path == "prompts" -> {
                if ("reset" in body) {
                    val defaults = mapOf("template" to json(EVERY_AGENT), "board_skill" to json(BOARD_SKILL), "chat_skill" to json(CHAT_SKILL))
                    val resetKeys = (body["reset"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    prompts = JsonObject(prompts + resetKeys.mapNotNull { k -> defaults[k]?.let { k to it } })
                } else {
                    prompts = JsonObject(prompts + body)
                }
                BridgeResponse(200, prompts)
            }
            path == "boards/order" -> {
                settings["board_order"] = body["ids"] ?: json(emptyList<String>())
                ok()
            }
            path == "dreamfall/$PIPELINE/update" -> {
                for ((k, v) in body) {
                    if (k == "categories" && v is JsonPrimitive) {
                        pipelineSettings["categories"] = json(v.contentOrNull.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() })
                    } else {
                        pipelineSettings[k] = v
                    }
                }
                publish()
                ok("rev" to rev.toString())
            }
            path.startsWith("dreamfall/") && path.endsWith("/act") -> act(body)
            path.startsWith("card/") && path.endsWith("/move") -> {
                val id = path.removePrefix("card/").removeSuffix("/move")
                card(id)?.let { moveTo(it, body.str("lane"), "runtime" to "idle") }
                publish()
                ok()
            }
            else -> demoOnly()
        }

    // ---- decisions

    private fun act(body: JsonObject): BridgeResponse {
        val id = body.str("card")
        val card = card(id) ?: return BridgeResponse(404, obj("error" to "This idea is no longer on the board."))
        val (category, title) = split(card.str("title"))
        var created = true
        when (body.str("action")) {
            "pick" -> {
                if (records[id].str("transferred_to").isNotEmpty()) created = false
                else {
                    val start = body.str("start") == "true"
                    val worktree = body.str("workspace") != "project"
                    val note = body.str("note")
                    val to = "df-i${++serial}"
                    moveTo(card, "done")
                    records.getValue(id)["transferred_to"] = json(to)
                    if (note.isNotEmpty()) records.getValue(id)["verdict"] = obj("kind" to "pick", "rationale" to note)
                    event(id, "picked", "actor" to "human", "stage" to "implementation", "note" to note)
                    val pickedNote = "**Picked from Navigation** by you" + if (note.isNotEmpty()) ": $note" else "."
                    idea(
                        to, IMPLEMENTATION, category, title, if (start) "running" else "todo", clock(),
                        card.str("initial_prompt").substringBefore("\n---") + "\n\n---\n$pickedNote\n\n$INSTRUCTIONS",
                        record =
                            mapOf(
                                "source" to id,
                                "created_by" to mapOf("actor" to "human"),
                                "source_verdict" to if (note.isNotEmpty()) obj("kind" to "pick", "rationale" to note) else null,
                            ),
                        extra =
                            mapOf(
                                "runtime" to if (start) "working" else "idle", "initial_prompt_sent" to start,
                                "provider" to "claude-code", "model" to "opus", "workspace_mode" to if (worktree) "worktree" else "project",
                                "branch" to if (worktree) "dreamfall/" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32) else "",
                            ),
                    )
                    if (start) event(to, "started", "actor" to "human")
                }
            }
            "park" -> {
                moveTo(card, "review")
                records.getValue(id)["verdict"] = obj("kind" to "park", "rationale" to body.str("note"))
                event(id, "parked", "actor" to "human", "note" to body.str("note"))
            }
            "restore" -> {
                moveTo(card, "todo")
                event(id, "restored", "actor" to "human")
            }
            "release" -> {
                moveTo(card, "review")
                records.getValue(id)["verdict"] = obj("kind" to "release", "rationale" to body.str("note"))
                event(id, "released", "actor" to "human", "note" to body.str("note"))
            }
            "promote" -> {
                if (records[id].str("transferred_to").isNotEmpty()) created = false
                else {
                    val to = "df-n${++serial}"
                    val note = body.str("note")
                    val verdict = obj("kind" to "promote", "rationale" to note)
                    moveTo(card, "done")
                    records.getValue(id)["verdict"] = verdict
                    records.getValue(id)["transferred_to"] = json(to)
                    event(id, "transferred", "actor" to "human", "stage" to "navigation")
                    idea(
                        to, NAVIGATION, category, title, "todo", clock(),
                        card.str("initial_prompt") + "\n\n---\n**Promoted from Enumeration** by you" +
                            (if (note.isNotEmpty()) ": $note" else "") + "\n\n<!-- dreamfall-source $id -->",
                        record = mapOf("source" to id, "created_by" to mapOf("actor" to "human"), "source_verdict" to verdict),
                    )
                }
            }
            "start" -> {
                moveTo(card, "running", "runtime" to "working", "initial_prompt_sent" to true)
                event(id, "started", "actor" to "human")
            }
            else -> return demoOnly()
        }
        publish()
        return ok("rev" to rev.toString(), "created" to created.toString())
    }

    private fun moveTo(card: MutableMap<String, JsonElement>, lane: String, vararg also: Pair<String, Any?>) {
        val at = clock()
        card["lane"] = json(lane)
        card["updated_at"] = json(at)
        card["phase_changed_at"] = json(at)
        for ((k, v) in also) card[k] = json(v)
        records[card.str("id")]?.let { it["u"] = json((it.str("u").toIntOrNull() ?: 0) + 1) }
    }

    private fun publish() {
        rev++
        state.value = snapshot()
    }

    /** The demo's clock: a moment after the page opened, so new events come after the sample's. */
    private fun clock(): Instant = Clock.System.now()

    // ---- an idea's trail and provenance

    private fun trail(id: String): BridgeResponse {
        val card = card(id) ?: return BridgeResponse(404, obj("error" to "No such card."))
        // back to where the idea started, then forward through any copies
        val chain = mutableListOf(id)
        while (records[chain.first()].str("source").isNotEmpty()) chain.add(0, records[chain.first()].str("source"))
        while (records[chain.last()].str("transferred_to").isNotEmpty()) chain.add(records[chain.last()].str("transferred_to"))
        return ok(
            "card" to JsonObject(card),
            "record" to JsonObject(records[id].orEmpty()),
            "brief" to card.str("initial_prompt"),
            "chain" to
                chain.map { c ->
                    val r = records[c]
                    obj(
                        "card" to c, "stage" to r.str("stage"), "created_by" to r?.get("created_by"),
                        "created_at" to card(c)?.get("created_at"), "verdict" to r?.get("verdict"), "known" to true, "self" to (c == id),
                    )
                },
            "events" to chain.flatMap { events[it].orEmpty() },
        )
    }

    private fun event(card: String, kind: String, vararg fields: Pair<String, Any?>, at: Instant = clock()) {
        val e = obj("kind" to kind, "card_stage" to records[card].str("stage"), "at" to at, *fields)
        events.getOrPut(card) { mutableListOf() } += e
        records[card]?.let { it["events"] = json(events.getValue(card).size) }
    }

    // ---- the sample: Atlas ideas after last night's Enumeration (02:00) and Navigation (05:00) runs

    private fun sample() {
        nightRun("df-run-e1", ENUMERATION, night, 7.minutes, "counts" to mapOf("created" to 16))
        nightRun("df-run-n1", NAVIGATION, night + 3.hours, 8.minutes, "counts" to mapOf("promoted" to 6, "released" to 6))

        // Six ideas promoted by Navigation (five waiting for a pick, one already picked onto Implementation)
        found(
            "df-e01", "df-run-e1", night + 1.minutes, "Developer experience", "Idempotency keys on every POST",
            "Clients retry safely after a timeout: the same key returns the first response for 24 hours.",
            promote = 9 to "Prevents double charges and duplicate webhooks; small, well understood change.", to = "df-n01",
        )
        found(
            "df-e02", "df-run-e1", night + 1.minutes + 20.seconds, "Developer experience", "Webhook event replay from the dashboard",
            "Re-send any event from the last 30 days to a chosen endpoint when a consumer was down.",
            promote = 8 to "Support asks for it every week; the delivery log already has the data.", to = "df-n02",
        )
        found(
            "df-e03", "df-run-e1", night + 1.minutes + 40.seconds, "Reliability", "Retry budget for outgoing webhooks",
            "Cap retries per endpoint so one dead consumer cannot fill the delivery queue.",
            promote = 8 to "Removes the one failure mode that filled the queue in March.", to = "df-n03",
        )
        found(
            "df-e04", "df-run-e1", night + 2.minutes, "Billing", "Usage alerts at 80% and 100% of the plan",
            "E-mail and webhook when a key approaches its monthly quota.",
            promote = 7 to "Cheap, and it turns surprise bills into planned upgrades.", to = "df-n04",
        )
        found(
            "df-e05", "df-run-e1", night + 2.minutes + 20.seconds, "Security", "Scoped API keys per endpoint group",
            "Read-only search keys for browsers, write keys for servers.",
            promote = 9 to "Blocks the biggest blast radius today: one leaked key reads and writes everything.", to = "df-n05",
        )
        found(
            "df-e06", "df-run-e1", night + 2.minutes + 40.seconds, "Security", "Signed webhook timestamps with a 5-minute window",
            "Rejects replayed deliveries even when the body signature is valid.",
            promote = 7 to "A few lines in the signer and a documented tolerance.", to = "df-n06",
        )

        promoted("df-n01", "df-e01", "df-run-n1", night + 3.hours + 1.minutes)
        promoted("df-n02", "df-e02", "df-run-n1", night + 3.hours + 2.minutes)
        promoted("df-n03", "df-e03", "df-run-n1", night + 3.hours + 3.minutes)
        promoted("df-n04", "df-e04", "df-run-n1", night + 3.hours + 4.minutes)
        promoted("df-n05", "df-e05", "df-run-n1", night + 3.hours + 5.minutes, lane = "done", changed = night + 5.hours + 15.minutes, to = "df-i01", pickNote = "Start with the read-only search key.")
        promoted("df-n06", "df-e06", "df-run-n1", night + 3.hours + 6.minutes)

        picked(
            "df-i01", "df-n05", night + 5.hours + 15.minutes, lane = "todo", changed = night + 5.hours + 15.minutes,
            note = "Start with the read-only search key.",
            extra = mapOf("provider" to "claude-code", "model" to "opus", "branch" to "dreamfall/scoped-api-keys", "initial_prompt_sent" to false, "workspace_mode" to "worktree"),
        )

        // Six ideas released by Navigation with rationales
        found(
            "df-e07", "df-run-e1", night + 3.minutes, "Developer experience", "A /v2/search playground in the docs",
            "Try queries against a sandbox index without an API key; shows the request as curl.",
            release = 4 to "Nice, but the docs team owns it and it needs a sandbox index first.",
        )
        found(
            "df-e08", "df-run-e1", night + 3.minutes + 20.seconds, "Developer experience", "Typed SDK for Go and TypeScript from the OpenAPI file",
            "Generated on release, so every endpoint ships with a client the same day.",
            release = 5 to "Worth doing, but a quarter of work, not a night's.",
        )
        found(
            "df-e09", "df-run-e1", night + 3.minutes + 40.seconds, "Reliability", "Shadow traffic for the new search index",
            "Mirror 5% of /v2/search to the next index and diff the results before the cut-over.",
            release = 3 to "Needs infrastructure we do not have yet.",
        )
        found(
            "df-e10", "df-run-e1", night + 4.minutes, "Billing", "Prorated plan changes mid-cycle",
            "Upgrades take effect at once with a credit for the unused days.",
            release = 5 to "Billing provider handles proration already.",
        )
        found(
            "df-e11", "df-run-e1", night + 4.minutes + 20.seconds, "Billing", "Invoice PDF with the per-endpoint breakdown",
            "Finance teams see which endpoints drive the bill.",
            release = 4 to "Finance has not asked; revisit with the billing rewrite.",
        )
        found(
            "df-e12", "df-run-e1", night + 4.minutes + 40.seconds, "Billing", "Tax ID validation at checkout",
            "Check VAT numbers against VIES before the first invoice.",
            release = 3 to "Depends on the provider migration.",
        )

        // Four ideas in Enumeration's queue waiting for tonight's Navigation run
        unjudged(
            "df-e13", "df-run-e1", night + 5.minutes, "Reliability", "Graceful drain on deploys",
            "Stop accepting work, finish in-flight requests, then exit: no more 502s during rollouts.",
        )
        unjudged(
            "df-e14", "df-run-e1", night + 5.minutes + 20.seconds, "Reliability", "Per-tenant concurrency limits",
            "A noisy tenant gets queued instead of slowing everyone else down.",
        )
        unjudged(
            "df-e15", "df-run-e1", night + 5.minutes + 40.seconds, "Security", "Audit log stream to the customer's SIEM",
            "Push key creation, rotation and failed-auth events as JSON lines.",
        )
        unjudged(
            "df-e16", "df-run-e1", night + 6.minutes, "Security", "Automatic key rotation reminders",
            "Nudge owners every 90 days and offer a one-click overlap period.",
        )
    }

    private fun nightRun(id: String, board: String, start: Instant, took: Duration, vararg fields: Pair<String, Any?>) {
        val stage = stageOf(board)
        runs += obj("card" to id, "stage" to stage, "status" to "done", "started_at" to start, "finished_at" to start + took, *fields)
        cards +=
            mutableMapOf(
                "id" to json(id), "title" to json(if (stage == "enumeration") "Enumeration run" else "Navigation run"),
                "lane" to json("done"), "board_id" to json(board), "runtime" to json("idle"),
                "created_at" to json(start), "updated_at" to json(start + took), "phase_changed_at" to json(start + took),
            )
    }

    /** An idea an Enumeration run wrote and Navigation judged (promoted to [to], or released). */
    private fun found(
        id: String, run: String, at: Instant, category: String, title: String, why: String,
        promote: Pair<Int, String>? = null, release: Pair<Int, String>? = null, to: String = "",
    ) {
        val verdict = promote?.let { "promote" to it } ?: release?.let { "release" to it }
        val judged = at + 3.hours
        idea(
            id, ENUMERATION, category, title, if (promote != null) "done" else "review", at,
            "**Idea:** $title.\n\n**Why it is valuable:** $why\n\n---\nFound by the Enumeration agent of the Dreamfall board Atlas ideas, run `$run` (claude-code · sonnet).",
            record =
                mapOf(
                    "created_by" to mapOf("actor" to "agent", "name" to "Enumeration agent", "run" to run),
                    "verdict" to verdict?.let { (kind, v) -> mapOf("kind" to kind, "rationale" to v.second, "score" to v.first) },
                    "transferred_to" to to.ifEmpty { null },
                ),
            changed = judged,
        )
        event(id, "created", "actor" to "agent", "name" to "Enumeration agent", "run" to run, "stage" to "enumeration", at = at)
        if (verdict != null)
            event(
                id, "judged", "actor" to "agent", "name" to "Navigation agent", "run" to "df-run-n1", "verdict" to verdict.first,
                "rationale" to verdict.second.second, "score" to verdict.second.first, at = judged,
            )
        if (promote != null) event(id, "transferred", "actor" to "agent", "name" to "Navigation agent", "run" to "df-run-n1", "stage" to "navigation", at = judged)
        else event(id, "released", "actor" to "agent", "name" to "Navigation agent", "run" to "df-run-n1", at = judged)
    }

    /** An idea an Enumeration run wrote that is still waiting in Enumeration's queue for Navigation. */
    private fun unjudged(id: String, run: String, at: Instant, category: String, title: String, why: String) {
        idea(
            id, ENUMERATION, category, title, "todo", at,
            "**Idea:** $title.\n\n**Why it is valuable:** $why\n\n---\nFound by the Enumeration agent of the Dreamfall board Atlas ideas, run `$run` (claude-code · sonnet).",
            record = mapOf("created_by" to mapOf("actor" to "agent", "name" to "Enumeration agent", "run" to run)),
        )
        event(id, "created", "actor" to "agent", "name" to "Enumeration agent", "run" to run, "stage" to "enumeration", at = at)
    }

    /** Navigation's copy of [source], waiting for a pick unless it is in [lane]. */
    private fun promoted(
        id: String, source: String, run: String, at: Instant, lane: String = "todo",
        changed: Instant = at, to: String = "", pickNote: String = "",
    ) {
        val from = card(source)!!
        val (category, title) = split(from.str("title"))
        val verdict = records[source]?.get("verdict") as JsonObject
        val ownVerdict = if (to.isNotEmpty() && pickNote.isNotEmpty()) obj("kind" to "pick", "rationale" to pickNote) else null
        idea(
            id, NAVIGATION, category, title, lane, at,
            from.str("initial_prompt") +
                "\n\n---\n**Promoted from Enumeration** by Navigation agent: ${verdict.str("rationale")}\n\n<!-- dreamfall-source $source -->",
            record =
                mapOf(
                    "source" to source, "created_by" to mapOf("actor" to "agent", "name" to "Navigation agent", "run" to run),
                    "source_verdict" to verdict, "verdict" to ownVerdict, "transferred_to" to to.ifEmpty { null },
                ),
            changed = changed,
        )
        if (to.isNotEmpty()) event(id, "picked", "actor" to "human", "stage" to "implementation", "note" to pickNote, at = changed)
    }

    /** The Implementation card you made of [source]. */
    private fun picked(id: String, source: String, at: Instant, lane: String, changed: Instant, note: String = "", extra: Map<String, Any?>) {
        val from = card(source)!!
        val (category, title) = split(from.str("title"))
        val pickedNote = "**Picked from Navigation** by you" + if (note.isNotEmpty()) ": $note" else "."
        idea(
            id, IMPLEMENTATION, category, title, lane, at,
            from.str("initial_prompt").substringBefore("\n---") + "\n\n---\n$pickedNote\n\n$INSTRUCTIONS",
            record =
                mapOf(
                    "source" to source,
                    "created_by" to mapOf("actor" to "human"),
                    "source_verdict" to if (note.isNotEmpty()) obj("kind" to "pick", "rationale" to note) else records[source]?.get("source_verdict"),
                ),
            extra = extra + mapOf("last_activity_at" to changed),
            changed = changed,
        )
    }

    private fun idea(
        id: String, board: String, category: String, title: String, lane: String, at: Instant, brief: String,
        record: Map<String, Any?>, extra: Map<String, Any?> = emptyMap(), changed: Instant = at,
    ) {
        cards +=
            mutableMapOf(
                "id" to json(id), "title" to json("[$category] $title"), "lane" to json(lane), "board_id" to json(board),
                "initial_prompt" to json(brief), "created_at" to json(at), "updated_at" to json(changed),
                "phase_changed_at" to json(changed),
            )
                .apply { putAll(extra.filterValues { it != null }.mapValues { json(it.value) }) }
        records[id] =
            (mapOf("stage" to json(stageOf(board)), "category" to json(category), "u" to json(1), "events" to json(0)) +
                    record.filterValues { it != null }.mapValues { json(it.value) })
                .toMutableMap()
    }

    private fun snapshot(): JsonObject =
        obj(
            "rev" to rev,
            "projects" to PROJECTS,
            "boards" to
                listOf(ENUMERATION to "Enumeration", NAVIGATION to "Navigation", IMPLEMENTATION to "Implementation").map { (id, name) ->
                    mapOf("id" to id, "name" to name, "project_id" to "p-addon", "lanes" to listOf("todo", "running", "review", "done"))
                },
            "harnesses" to HARNESSES,
            "backends" to listOf(mapOf("id" to "antigravity")),
            "quota" to
                listOf(
                    mapOf(
                        "provider" to "antigravity",
                        "windows" to
                            listOf(
                                mapOf("group" to "Gemini", "label" to "Gemini · 5h", "remaining" to 84, "resets_at" to (clock() + 3.hours).toString(), "models" to listOf("Gemini 3 Pro", "Gemini 3 Flash")),
                                mapOf("group" to "Gemini", "label" to "Gemini · Week", "remaining" to 72, "resets_at" to (clock() + 4.days).toString(), "models" to listOf("Gemini 3 Pro", "Gemini 3 Flash")),
                                mapOf("group" to "Claude", "label" to "Claude · 5h", "remaining" to 91, "resets_at" to (clock() + 2.hours).toString(), "models" to listOf("Claude Opus 4.6", "Claude Sonnet 4.6")),
                            ),
                    )
                ),
            "questions" to emptyList<String>(),
            "claude_auth" to CLAUDE,
            "dreamfall" to
                mapOf(
                    "ready" to true,
                    "defaults" to mapOf("navigation_logic" to NAVIGATION_LOGIC),
                    // each idea once, by the card it started as
                    "ideas" to mapOf(PIPELINE to records.values.count { it.str("source").isEmpty() && it.str("stage") == "enumeration" }),
                    "pipelines" to listOf(pipeline()),
                    "records" to records.mapValues { JsonObject(it.value) },
                    "runs" to mapOf(PIPELINE to runs.sortedBy { it.str("started_at") }), // oldest first, like the operator's
                ),
            "dreamfall_cards" to cards.map { JsonObject(it) },
        )

    private fun pipeline(): JsonObject =
        JsonObject(
            pipelineSettings +
                mapOf(
                    "stages" to
                        json(
                            listOf(
                                mapOf("role" to "enumeration", "name" to "Enumeration", "board_id" to ENUMERATION, "actor" to "agent", "cron" to "0 2 * * *", "run_title" to "Enumeration run", "schedule_id" to "sch-df-e"),
                                mapOf("role" to "navigation", "name" to "Navigation", "board_id" to NAVIGATION, "actor" to "agent", "cron" to "0 5 * * *", "run_title" to "Navigation run", "schedule_id" to "sch-df-n"),
                                mapOf("role" to "implementation", "name" to "Implementation", "board_id" to IMPLEMENTATION, "actor" to "human", "cron" to "", "run_title" to "", "schedule_id" to ""),
                            )
                        )
                )
        )

    private fun card(id: String) = cards.firstOrNull { it.str("id") == id }

    private fun stageOf(board: String) =
        when (board) {
            ENUMERATION -> "enumeration"
            NAVIGATION -> "navigation"
            IMPLEMENTATION -> "implementation"
            else -> ""
        }

    private fun split(title: String): Pair<String, String> =
        Regex("""^\[([^\]]+)]\s*(.*)$""").find(title)?.let { it.groupValues[1] to it.groupValues[2] } ?: ("" to title)

    private fun demoOnly() =
        BridgeResponse(409, obj("error" to "This is the demo: it has no add-on behind it. Install the add-on to do this."))

    private companion object {
        const val PIPELINE = "df-atlas"
        const val ENUMERATION = "b-df-enumeration"
        const val NAVIGATION = "b-df-navigation"
        const val IMPLEMENTATION = "b-df-implementation"
        const val NAVIGATION_LOGIC = "Promote what prevents data loss, double charges or tenant blast radius within a night's work. Release what needs new infrastructure or belongs to another team."
        const val INSTRUCTIONS = "Add an integration test under `internal/` and open a pull request when the suite passes."
        const val EVERY_AGENT =
            "You are working inside Dieter on project {{project.name}}.\n\n{{project.instructions_block}}\n{{labels.instructions_block}}\n\nKeep the card in Running while work or verification is incomplete; say exactly what blocks you."
        const val BOARD_SKILL =
            "Work in the card's worktree. When the change is committed and checked, open a pull request and move the card to Review."
        const val CHAT_SKILL = "Answer in the chat. Change files only when asked to."
        val CLAUDE = mapOf("signed_in" to true, "method" to "login", "plan" to "Max")
        val PROJECTS =
            listOf(
                mapOf("id" to "p-addon", "name" to "atlas-api"),
                mapOf("id" to "p-home", "name" to "storefront"),
                mapOf("id" to "p-dieter", "name" to "pocket"),
            )
        val HARNESSES =
            listOf(
                mapOf(
                    "id" to "claude-code", "name" to "Claude Code", "default_model" to "sonnet",
                    "models" to
                        listOf(
                            mapOf("id" to "opus", "name" to "Opus", "efforts" to listOf("low", "medium", "high")),
                            mapOf("id" to "sonnet", "name" to "Sonnet", "efforts" to listOf("low", "medium", "high")),
                        ),
                ),
                mapOf(
                    "id" to "codex", "name" to "Codex", "default_model" to "gpt-5.5",
                    "models" to listOf(mapOf("id" to "gpt-5.5", "name" to "GPT-5.5", "efforts" to listOf("low", "medium", "high"))),
                ),
            )
    }
}

private fun ok(vararg fields: Pair<String, Any?>) = BridgeResponse(200, obj(*fields))

private fun obj(vararg fields: Pair<String, Any?>): JsonObject =
    JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to json(v) })

private fun json(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Instant -> JsonPrimitive(value.toString())
        is Map<*, *> -> JsonObject(value.entries.filter { it.value != null }.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonArray(value.map { json(it) })
        else -> JsonPrimitive(value.toString())
    }

private fun Map<String, JsonElement>?.str(key: String): String = ((this?.get(key)) as? JsonPrimitive)?.contentOrNull.orEmpty()
