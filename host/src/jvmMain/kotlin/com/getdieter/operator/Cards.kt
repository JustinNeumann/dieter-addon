package com.getdieter.operator

import java.io.File
import java.util.Base64
import kotlin.time.Duration
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
 * Home Assistant's side of the add-on and the cards' actions (bridge/dieter_bridge.py until 0.6.431.21): its sensor's
 * summary (`/api/ha`), the rest_commands' card creation, follow-up, move and focus, a card's conversation, the
 * schedules of a project, and what the page's add-on core asks of a card (move, a Dreamfall card's trail). Every
 * change shows in [board] at once. homeassistant/packages/dieter.yaml and the e2e contract tests pin the answers.
 */
class Cards(
    private val dieter: Dieter,
    private val board: Board,
    private val dreamfall: Dreamfall,
    private val titles: Titles,
    /** Settings name Ollama and it can be asked: a card created without a title keeps its first line until Ollama names it */
    private val ollamaTitles: () -> Boolean,
    /** DIETER_HOME: the uploads to attach, the worktrees of the checkout roots */
    private val home: File,
    private val addon: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var focus: Pair<String, Long>? = null   // card the next page load should open (set by HA before a push)

    // ------------------------------------------------------------------ actions

    /** {project, board, title, prompt, provider, model, effort, workspace, branch?, base?, publish?, vault?, mail?, home_assistant?, start?, attachments?}. */
    fun create(kind: String, b: JsonObject, user: String = "you"): JsonObject {
        for (k in listOf("project", "prompt")) if (!b[k].truthy()) throw BadRequest("$k required")
        val prompt = b.field("prompt")
        val start = if ("start" in b) b["start"].truthy() else true
        val args = mutableListOf(kind, "create", "--project", b.field("project"), "--workspace", b.field("workspace").ifEmpty { "project" }, "--format", "json", "--prompt", prompt)
        if (kind == "card") {
            if (!b["board"].truthy()) throw BadRequest("board required")
            args += listOf("--board", b.field("board"), "--lane", if (start) "running" else "todo")
        }
        val title = b.field("title").trim()
        val ollama = title.isEmpty() && ollamaTitles()
        if (title.isNotEmpty() || ollama) args += listOf("--title", title.ifEmpty { Titles.firstLine(prompt) }) else args += "--auto-title"
        for (k in listOf("provider", "model", "effort", "branch", "checkout")) if (b[k].truthy()) args += listOf("--$k", b.field(k))
        if (b.field("workspace") == "worktree" && b["base"].truthy()) args += listOf("--base-branch", b.field("base"))
        if (b["publish"].truthy()) args += listOf("--remote-publish", publishMode(b.field("publish")))
        for (k in listOf("vault", "mail")) if (b[k].truthy()) args += "--$k=true"   // fixed at creation: the vault and mail gates of the agent turns
        if (b["home_assistant"].truthy()) args += "--home-assistant=true"           // and the agent relay's (Home Assistant access)
        val files = uploads(b["attachments"])
        files.forEach { args += listOf("--attach", it.path) }
        val out = try {
            dieter.run(args, 120.seconds)
        } finally {
            forget(files)
        }
        val c = (out as? JsonObject)?.let { it.obj("card") ?: it } ?: JsonObject(emptyMap())
        val cid = c.field("id")
        var card = Board.normCard(c, kind)
        if (kind == "card" && cid.isNotEmpty()) dreamfallCreated(cid, b, user)
        if (ollama && cid.isNotEmpty()) {
            try {   // renamed once Ollama answers
                titles.nameLater(buildJsonObject { put("kind", kind); put("id", cid); put("prompt", prompt); put("provisional", Titles.firstLine(prompt)) })
            } catch (e: Exception) {
                Log.line("title: $cid ${e.message}")
            }
        }
        if (cid.isNotEmpty()) {
            if ((kind == "chat" || start) && card.field("runtime") == "idle") card = card.updated("runtime" to "running")   // the turn is starting; the next poll has the daemon's runtime
            board.add(card)                                                                                                // the page opens the new card from the state
        } else board.refresh(wait = true)                                                                                  // without an id: a full poll
        return buildJsonObject {
            put("ok", true)
            put("id", cid.ifEmpty { null })
            put("card", card)
        }
    }

    /** {message, model?, effort?, attachments?: [upload id]}: a follow-up. */
    fun send(kind: String, cid: String, b: JsonObject): JsonObject {
        val files = uploads(b["attachments"])
        var message = b.field("message").trim()
        if (message.isEmpty() && files.isNotEmpty()) message = if (files.size == 1) "Please look at the attached file." else "Please look at the attached files."
        if (message.isEmpty()) {
            forget(files)
            throw BadRequest("message required")
        }
        val args = mutableListOf(kind, "send", "--message", message)
        for (k in listOf("model", "effort")) if (b[k].truthy()) args += listOf("--$k", b.field(k))
        files.forEach { args += listOf("--attach", it.path) }
        val out = try {
            text(args + cid, 120.seconds)
        } finally {
            forget(files)
        }
        board.refresh(ids = listOf(cid))
        return done(out)
    }

    fun move(cid: String, b: JsonObject): JsonObject {
        val lane = b.field("lane")
        if (b["lane"] !is JsonPrimitive || lane !in Board.LANES) throw BadRequest("lane must be one of " + Board.LANES.joinToString(", "))
        val out = text(listOf("card", "move", "--lane", lane, cid), 30.seconds)
        board.patch(cid, "lane" to lane)
        return done(out)
    }

    fun start(cid: String): JsonObject {
        val out = text(listOf("card", "start", cid), 60.seconds)
        board.patch(cid, "lane" to "running")
        return done(out)
    }

    /** {title?, prompt?}: a chat is only renamed. */
    fun update(kind: String, cid: String, b: JsonObject): JsonObject {
        val title = b.field("title").trim()
        val prompt = (b["prompt"] as? JsonPrimitive)?.contentOrNull
        if (title.isNotEmpty()) titles.cancel(cid)
        if (title.isEmpty() && prompt == null) throw BadRequest("title or prompt required")
        val args =
            if (kind == "chat") {
                if (prompt != null || title.isEmpty()) throw BadRequest("a chat can only be renamed")
                listOf("chat", "rename", "--title", title, cid)
            } else {
                val a = mutableListOf("card", "update")
                if (title.isNotEmpty()) a += listOf("--title", title)
                if (prompt != null) {
                    if (prompt.isBlank()) throw BadRequest("task must not be empty")
                    a += listOf("--prompt", prompt.trim())
                }
                a + cid
            }
        val out = text(args, 30.seconds)
        if (title.isNotEmpty()) board.patch(cid, "title" to title) else board.refresh(ids = listOf(cid))
        return done(out)
    }

    /** cancel (stop the running turn) or archive. */
    fun stop(kind: String, cid: String, action: String): JsonObject {
        val out = text(listOf(kind, action, cid), 60.seconds)
        if (action == "archive") board.patch(cid, "archived" to true) else board.refresh(ids = listOf(cid))
        return done(out)
    }

    /** {seq}: the displayed reply is seen. */
    fun read(cid: String, b: JsonObject): JsonObject {
        val seq = b.field("seq").trim()
        if (seq.isEmpty() || !seq.all { it.isDigit() }) throw BadRequest("seq required")
        val out = text(listOf("card", "read", "--response-seq", seq, cid), 30.seconds)
        return buildJsonObject { put("ok", true); put("output", out.trim().take(200)) }
    }

    /** The conversation (last 200 messages) and the card's detail. */
    fun conversation(kind: String, cid: String): JsonObject {
        val snap = dieter.run(kind, "poll", "--last", "200", cid, timeout = 30.seconds) as? JsonObject ?: JsonObject(emptyMap())
        return normConversation(snap.obj("snapshot") ?: snap, home)
    }

    /** {schedules}: a project's (or every) schedule, enabled ones first. */
    fun schedules(projectId: String): JsonObject {
        if (projectId.isNotEmpty() && !projectId.startsWith("p_")) throw CliError("bad project")
        val out = dieter.run(listOf("schedule", "list", "--page-size", "100", "--format", "json") + (if (projectId.isNotEmpty()) listOf("--project", projectId) else emptyList()), 30.seconds)
        val items = out.unwrap("schedules").map(::normSchedule).sortedWith(compareBy({ !it["enabled"].truthy() }, { it.field("name").lowercase() }))
        return buildJsonObject { put("schedules", JsonArray(items)) }
    }

    /** The card a push opens: kept for the next page load ([FOCUS_TTL_MS]). */
    fun setFocus(b: JsonObject): JsonObject {
        val id = b.field("id").trim()
        if (!id.startsWith("c_")) throw BadRequest("id required")
        focus = id to now()
        return buildJsonObject { put("ok", true) }
    }

    /** {id|null}; [consume] forgets it. */
    fun focus(consume: Boolean): JsonObject {
        val f = focus?.takeIf { now() - it.second < FOCUS_TTL_MS }?.first
        if (consume) focus = null
        return buildJsonObject { put("id", f) }
    }

    /** The compact status of Home Assistant's sensor.dieter. */
    fun haSummary(): JsonObject {
        val (rev, d) = board.snapshot()
        val cards = d.objs("cards").filter { !it["archived"].truthy() }
        val lanes = Board.LANES.associateWith { l -> cards.filter { it.field("lane") == l } }
        val running = (cards + d.objs("chats")).filter { it.field("runtime") == "running" }
        val quota = buildJsonObject {
            for (a in d.objs("quota")) {
                val w = a.objs("windows").associateBy { it.field("id") }
                put(a.field("provider"), buildJsonObject {
                    put("plan", a["plan"] ?: JsonNull)
                    put("five_hour_used", w["five_hour"]?.get("used") ?: JsonNull)
                    put("week_used", w["seven_day"]?.get("used") ?: JsonNull)
                    put("five_hour_resets_at", w["five_hour"]?.get("resets_at") ?: JsonNull)
                    put("week_resets_at", w["seven_day"]?.get("resets_at") ?: JsonNull)
                    put("available", a["available"] ?: JsonNull)
                })
            }
        }
        val m = d.obj("machine")
        return buildJsonObject {
            put("ok", d["ok"]?.truthy() ?: false)
            put("error", d["error"] ?: JsonNull)
            put("daemon", d["daemon"] ?: JsonNull)
            put("rev", rev)
            put("lanes", JsonObject(lanes.mapValues { JsonPrimitive(it.value.size) }))
            put("running", running.size)
            put("needs_you", lanes.getValue("review").size)
            put("running_titles", JsonArray(running.take(5).map { it["title"] ?: JsonNull }))
            put("review_titles", JsonArray(lanes.getValue("review").take(5).map { it["title"] ?: JsonNull }))
            put("quota", quota)
            put("machine", if (m.isNullOrEmpty()) JsonNull else JsonObject(listOf("cpu_percent", "mem_used", "mem_total", "gpu", "uptime_s").associateWith { m[it] ?: JsonNull }))
            put("last_finished", d["last_finished"] ?: JsonNull)
            put("last_review", d["last_review"] ?: JsonNull)
            put("projects", JsonArray(d.objs("projects").map { it["name"] ?: JsonNull }))
            put("questions", (d["questions"] as? JsonArray)?.size ?: 0)
            put("addon", addon)
        }
    }

    // ------------------------------------------------------------------ Dreamfall

    /** A card's trail and provenance chain; chain cards the state does not hold are read once. */
    fun dreamfallDetail(cid: String): JsonObject {
        val d = board.dreamfall.detail(cid, board.snapshot().second.objs("cards"))
        var brief = d["brief"]
        val chain = d.objs("chain").map { n ->
            if (n["known"].truthy() || !Dreamfall.CARD.matches(n.field("card"))) return@map n
            try {
                val c = dreamfall.cardShow(n.field("card"))
                if (n["self"].truthy() && (brief == null || brief is JsonNull)) brief = JsonPrimitive(c.prompt)
                n.updated("title" to c.title, "lane" to if (c.archived) "archived" else c.lane, "board_id" to c.boardId)
            } catch (_: DreamfallError) {
                n
            }
        }
        return d.updated("chain" to chain, "brief" to brief)
    }

    /**
     * A person's decision on a Dreamfall board ([Dreamfall.act]) shown from what it reported, not after the boards are
     * listed again: the records it wrote go to the live view, the new copy is read once (`card show`, fast), the moved
     * cards change lane in the view and in the state, and are re-read in the background. Listing them first kept a pick
     * off the page for 5-10 s on a Pi. The answer the page gets names the state that shows the change (rev) instead of
     * the records, lanes and boards.
     */
    fun applyDecision(answer: JsonObject): JsonObject {
        answer.obj("records")?.forEach { (key, value) -> board.dreamfall.putLocal(key, value as? JsonObject) }
        val lanes = answer.obj("lanes")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val id = answer.field("id")
        val copy = id.takeIf { it.isNotEmpty() }?.let {
            try {
                (dieter.run("card", "show", it, timeout = 20.seconds) as? JsonObject)?.let { c -> Board.normCard(c.obj("card") ?: c) }
            } catch (e: CliError) {
                Log.line("dreamfall: card show $it: ${e.message}")
                null
            }
        }
        val moved = lanes.filterKeys { it != id || copy == null }
        board.dreamfall.applyLocal(moved, listOfNotNull(copy))
        for ((cid, lane) in moved) board.patch(cid, "lane" to lane)
        board.refresh(ids = lanes.keys + id)
        return JsonObject(answer - setOf("records", "lanes", "boards") + ("rev" to JsonPrimitive(board.rev)))
    }

    /**
     * A card the person adds to a Dreamfall stage board gets a record (created by the person), so the board can tell it
     * from a card that arrived some other way (e.g. an agent's `dieter card create`).
     */
    private fun dreamfallCreated(cid: String, b: JsonObject, user: String) {
        val boardId = b.field("board")
        val p = board.dreamfall.pipelines().firstOrNull { Dreamfall.stageOfBoard(it, boardId) != null } ?: return
        val stage = Dreamfall.stageOfBoard(p, boardId) ?: return
        if (stage.field("actor") == "agent" && (if ("start" in b) b["start"].truthy() else true)) return   // an agent turn started by hand on an agent stage is a run, not an idea
        try {
            board.dreamfall.putLocal("c.$cid", dreamfall.recordCreated(p, cid, boardId, b.field("title"), "human", user))
        } catch (e: Exception) {
            Log.line("dreamfall: record of $cid: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ helpers

    /** `dieter ARGS`'s output as text (a follow-up's or a move's few lines). */
    private fun text(args: List<String>, timeout: Duration): String =
        when (val out = dieter.run(args, timeout)) {
            null -> ""
            is JsonPrimitive -> out.contentOrNull.orEmpty()
            else -> out.toString()
        }

    private fun done(out: String) = buildJsonObject { put("ok", true); put("output", out.trim().take(500)) }

    /** Upload ids from the page -> their files for --attach (four at most). */
    private fun uploads(ids: JsonElement?): List<File> {
        val list = (ids as? JsonArray).orEmpty().filter { it.truthy() }.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        if (list.size > 4) throw BadRequest("at most four attachments")
        return list.map { id ->
            if (!UPLOAD_ID.matches(id)) throw BadRequest("invalid attachment id")
            File(home, "uploads/$id").listFiles()?.firstOrNull() ?: throw BadRequest("attachment expired, add it again")
        }
    }

    private fun forget(files: List<File>) = files.forEach { it.parentFile.deleteRecursively() }

    companion object {
        const val FOCUS_TTL_MS = 900_000L
        val PUBLISH_MODES = listOf("manual", "pull_request", "push_base")   // Dieter's remote publish modes of a board or card
        private val UPLOAD_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val DIAG_NOISE = Regex("\\s*HarnessAgent: `sandbox` is deprecated\\. Supply `sandboxSession` to createSession\\(\\) instead\\.\\s*")
        private val DIAG_LINES = Regex("(?m)^(\\[harness:[^\\]]*\\].*|AGY_ERROR:.*|\\s+at .*)$")
        private val TRAILING_ERROR = Regex("(?s)\\s*error: (.+?)\\s*$")
        private val BLANK_LINES = Regex("\\n{2,}")
        private val IMAGE_HINT = Regex("\"type\"\\s*:\\s*\"image\"|\"isImage\"\\s*:\\s*true|data:image/|\"(?:base64|data)\"\\s*:\\s*\"(?:iVBORw0KGgo|/9j/|R0lGOD|UklGR)")

        fun publishMode(v: String): String = v.trim().takeIf { it in PUBLISH_MODES } ?: throw BadRequest("publish must be manual, pull_request or push_base")

        /**
         * Failure texts carry the worker's stderr: drops the AI SDK deprecation warning, the host's [harness:…] stack
         * lines, agy's AGY_ERROR JSON and the duplicated 'error: <message>' echo.
         */
        fun cleanText(text: String?): String {
            if (text.isNullOrEmpty() || listOf("HarnessAgent:", "[harness:", "AGY_ERROR:", "error: ").none { it in text }) return text.orEmpty()
            var out = DIAG_NOISE.replace(text, " ").replace(DIAG_LINES, "")
            TRAILING_ERROR.find(out)?.let { m -> if (m.groupValues[1].trim().take(60) in out.substring(0, m.range.first)) out = out.substring(0, m.range.first) }
            return BLANK_LINES.replace(out, "\n").trim()
        }

        private fun b64json(s: String): JsonObject =
            runCatching { Json.parseToJsonElement(Base64.getDecoder().decode(s).decodeToString().ifEmpty { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

        /** `card poll`/`card watch` snapshot -> {status, last_seq, messages[], card, …}. */
        fun normConversation(snapshot: JsonObject, home: File): JsonObject {
            val conv = snapshot.obj("conversation") ?: JsonObject(emptyMap())
            val messages = conv.objs("messages").map { m ->
                val meta = b64json(m.field("metadataJson"))
                buildJsonObject {
                    put("id", m["id"] ?: JsonNull)
                    put("role", m["role"] ?: JsonNull)
                    put("created_at", meta["createdAt"] ?: JsonNull)
                    put("usage", meta.obj("usage")?.get("totalTokens") ?: JsonNull)
                    putJsonArray("parts") { m.objs("parts").forEach { add(slimPart(it)) } }
                }
            }
            val detail = snapshot.obj("detail") ?: JsonObject(emptyMap())
            val card = detail.obj("card")?.takeIf { it.isNotEmpty() } ?: detail.obj("chat") ?: JsonObject(emptyMap())
            fun first(vararg keys: String): JsonElement = keys.firstNotNullOfOrNull { k -> conv[k]?.takeIf { it.truthy() } } ?: JsonArray(emptyList())
            return buildJsonObject {
                put("status", conv["status"] ?: JsonNull)
                put("last_seq", conv["lastSeq"] ?: JsonNull)
                put("updated_at", conv["updatedAt"] ?: JsonNull)
                put("messages", JsonArray(messages))
                put("page", snapshot["page"] ?: JsonNull)
                put("task_plans", first("taskPlans", "task_plans"))
                put("queue", first("queue"))
                put("pending_tools", first("pendingTools", "pending_tools"))
                put("subagents", first("subagents"))
                put("card", if (card.isEmpty()) JsonNull else Board.normCard(card, if ("lane" in card) "card" else "chat"))
                put("project", detail.obj("project")?.get("name") ?: JsonNull)
                put("board", detail.obj("board")?.get("name") ?: JsonNull)
                put("roots", JsonArray(checkoutRoots(detail, home).map(::JsonPrimitive)))
            }
        }

        private fun slimPart(p: JsonObject): JsonObject {
            val type = p.field("type")
            if (type == "text") return buildJsonObject {
                put("type", "text")
                put("text", cleanText(p.field("text")))
                put("state", p["state"] ?: JsonNull)
            }
            val slim = LinkedHashMap<String, JsonElement>()
            for ((k, v) in p) {
                val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    k == "url" && text != null && text.startsWith("data:") -> {
                        // attachments: small images stay inline for the page's preview, larger ones show a pill
                        slim[k] = JsonPrimitive(if (text.startsWith("data:image/") && text.length <= 600_000) text else "")
                        slim["size"] = JsonPrimitive(text.length * 3 / 4)
                    }
                    text != null -> slim[k] = JsonPrimitive(text.take(2000))
                    v is JsonPrimitive -> slim[k] = v
                    else -> slim[k] = JsonPrimitive(v.toString().take(2000))
                }
            }
            slim["type"] = JsonPrimitive(type.ifEmpty { "other" })
            val preview = listOf(p["outputPreview"], p["output_preview"]).firstOrNull { it.truthy() }?.let { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()
            if ((p["hasOutput"].truthy() || p["has_output"].truthy()) && IMAGE_HINT.containsMatchIn(preview)) slim["image"] = JsonPrimitive(true)
            return JsonObject(slim)
        }

        /**
         * Absolute directories of the card's checkout (worktree first): the page turns an absolute image path the agent
         * writes in its reply into the path relative to the checkout that `dieter file` wants.
         */
        fun checkoutRoots(detail: JsonObject, home: File): List<String> {
            val card = detail.obj("card")?.takeIf { it.isNotEmpty() } ?: detail.obj("chat") ?: JsonObject(emptyMap())
            val project = detail.obj("project") ?: JsonObject(emptyMap())
            val roots = mutableListOf<String>()
            val mode = card.field("workspaceMode").ifEmpty { card.obj("workspace")?.field("mode").orEmpty() }
            if (mode == "worktree" && card.field("id").isNotEmpty())
                roots += File(home, "worktrees/${card.field("projectId").ifEmpty { project.field("id") }}/${card.field("id")}").path
            for (c in project.objs("checkouts")) if (c.field("path").isNotEmpty() && c.field("id") == card.field("checkoutId")) roots += c.field("path")
            if (project.field("path").isNotEmpty()) roots += project.field("path")
            return roots.distinct().map { it.trimEnd('/') }.filter { it.isNotEmpty() }
        }

        /** One schedule as the page shows it; the CLI answers camelCase (list, create) or snake_case (show). */
        fun normSchedule(s: JsonObject): JsonObject {
            fun pick(vararg keys: String): JsonElement = keys.firstNotNullOfOrNull { k -> s[k]?.takeUnless { it is JsonNull } } ?: JsonNull
            fun text(vararg keys: String, default: String = "") = pick(*keys).takeIf { it.truthy() } ?: JsonPrimitive(default)
            return buildJsonObject {
                put("id", s["id"] ?: JsonNull)
                put("project_id", pick("projectId", "project_id"))
                put("board_id", pick("boardId", "board_id"))
                put("name", text("name"))
                put("cron", text("cron"))
                put("timezone", text("timezone", default = "UTC"))
                put("enabled", s["enabled"].truthy())
                put("action", text("action", default = "draft"))
                put("title", text("titleTemplate", "title_template"))
                put("prompt", text("promptTemplate", "prompt_template"))
                put("provider", text("provider"))
                put("model", text("model"))
                put("effort", text("effort"))
                put("workspace", text("workspaceMode", "workspace_mode"))
                put("next_runs", pick("nextRuns", "next_runs").takeIf { it.truthy() } ?: JsonArray(emptyList()))
                put("next_run_at", pick("nextRunAt", "next_run_at"))
                put("last_run_at", pick("lastRunAt", "last_run_at"))
                put("created_at", pick("createdAt", "created_at"))
                put("open_card", text("openCardPolicy", "open_card_policy"))
                put("vault", pick("vaultAccess", "vault_access").truthy())
                put("mail", pick("mailAccess", "mail_access").truthy())
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
