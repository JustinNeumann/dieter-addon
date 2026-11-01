package com.getdieter.web

import com.getdieter.api.v1.Board
import com.getdieter.api.v1.Card
import com.getdieter.api.v1.Checkout
import com.getdieter.api.v1.Conversation
import com.getdieter.api.v1.EffortOption
import com.getdieter.api.v1.HarnessSelection
import com.getdieter.api.v1.Label
import com.getdieter.api.v1.MessagePart
import com.getdieter.api.v1.Project
import com.getdieter.api.v1.PullRequestSummary
import com.getdieter.api.v1.Subagent
import com.getdieter.api.v1.TaskPlan
import com.getdieter.api.v1.TaskPlanItem
import com.getdieter.api.v1.TaskPlanPhase
import com.getdieter.api.v1.TokenUsage
import com.getdieter.api.v1.ToolOutput
import com.getdieter.api.v1.UiMessage
import com.getdieter.api.v1.WorkspaceSummary
import com.getdieter.client.v1.*
import com.getdieter.core.admin.BoardRetirement
import com.getdieter.core.board.Lanes
import com.getdieter.core.composition.Titles
import com.getdieter.core.runtime.CoreException
import com.getdieter.core.runtime.FailureKind
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import okio.ByteString.Companion.encodeUtf8

/** Demo slices, shaped like what the core publishes for a small two-machine account. */
class DemoData {
    private val now = Clock.System.now()

    private fun at(minutesAgo: Int) = (now - minutesAgo.minutes).toString()

    private fun millis(minutesAgo: Int) = (now - minutesAgo.minutes).toEpochMilliseconds()

    private val desktop = "daemon-desktop"
    private val homeAssistant = "daemon-ha"

    private val ui = Label(id = "l-ui", name = "UI", color = "#6366F1")
    private val api = Label(id = "l-api", name = "API", color = "#3B82F6")
    private val perf = Label(id = "l-perf", name = "Performance", color = "#F59E0B")
    private val security = Label(id = "l-sec", name = "Security", color = "#EF4444")
    private val docs = Label(id = "l-docs", name = "Docs", color = "#8B5CF6")
    private val bug = Label(id = "l-bug", name = "Bug", color = "#EC4899")
    private val checkout = Label(id = "l-checkout", name = "Checkout", color = "#10B981")
    private val sync = Label(id = "l-sync", name = "Sync", color = "#0EA5E9")

    private fun checkouts(project: String, vararg machines: String) =
        // absolute, as daemons report them: links in an agent's mail resolve against this
        machines.map { Checkout(id = "$project-$it", project_id = project, daemon_id = it, name = project, path = "/home/demo/$project") }

    private val projects =
        listOf(
            Project(id = "p-addon", name = "atlas-api", path = "~/atlas-api", board_count = 1, card_count = 5, chat_count = 1, base_branch = "main", checkouts = checkouts("atlas-api", "daemon-desktop", "daemon-ha")),
            Project(id = "p-home", name = "storefront", path = "~/storefront", board_count = 1, card_count = 3, chat_count = 1, base_branch = "main", checkouts = checkouts("storefront", "daemon-ha")),
            Project(id = "p-dieter", name = "pocket", path = "~/pocket", board_count = 3, card_count = 2, base_branch = "main", checkouts = checkouts("pocket", "daemon-desktop")),
        )

    private val boards =
        listOf(
            Board(id = "b-addon", project_id = "p-addon", name = "Roadmap", lanes = Lanes.DEFAULT, labels = listOf(api, perf, security, docs, bug)),
            Board(id = "b-home", project_id = "p-home", name = "Sprint 42", lanes = Lanes.DEFAULT, labels = listOf(checkout, ui)),
            Board(id = "b-dieter", project_id = "p-dieter", name = "Release 2.4", lanes = Lanes.DEFAULT, labels = listOf(sync, ui)),
            Board(id = "b-releases", project_id = "p-dieter", name = "Releases", lanes = Lanes.DEFAULT),
            Board(id = "b-site", project_id = "p-dieter", name = "Website", lanes = Lanes.DEFAULT),
        )

    private fun card(
        id: String,
        board: String,
        lane: String,
        title: String,
        summary: String = "",
        machine: String = desktop,
        provider: String = "claude",
        model: String = "opus",
        minutesAgo: Int = 5,
        labels: List<Label> = emptyList(),
        branch: String = "",
        changed: Int = 0,
        additions: Int = 0,
        deletions: Int = 0,
        tokens: Long = 0,
        pr: Int = 0,
        started: Boolean = true,
    ): Card {
        val project = boards.first { it.id == board }.project_id
        return Card(
            id = id,
            scope = "board",
            project_id = project,
            board_id = board,
            lane = lane,
            title = title,
            summary = summary,
            initial_prompt = title,
            initial_prompt_sent_at = if (started) at(minutesAgo + 30) else "",
            provider = provider,
            model = model,
            owner_daemon_id = machine,
            created_at = at(minutesAgo + 60),
            updated_at = at(minutesAgo),
            last_activity_at = at(minutesAgo),
            label_ids = labels.map { it.id },
            workspace_mode = if (branch.isEmpty()) "" else "worktree",
            workspace =
                if (branch.isEmpty()) null
                else WorkspaceSummary(mode = "worktree", state = "clean", branch = branch, base_branch = "main", changed_files = changed, additions = additions, deletions = deletions, ahead = if (changed > 0) 2 else 0),
            token_usage = if (tokens > 0) TokenUsage(total_tokens = tokens, reported_messages = 12) else null,
            pull_request = if (pr > 0) PullRequestSummary(provider = "github", number = pr, state = "open", checks_state = "success") else null,
        )
    }

    private val cards =
        mutableListOf(
            card("c-web", "b-addon", "running", "Add per-key rate limiting to the public API", "Running the race tests across the limiter and the HTTP middleware.", minutesAgo = 1, labels = listOf(api, perf), branch = "api/rate-limits", changed = 4, additions = 128, deletions = 12, tokens = 182_400),
            card("c-e2e", "b-addon", "running", "Fix flaky TestWebhookRetry under -race", "Found the race in Retrier.schedule. Waiting for approval to run the 200x race reproducer.", machine = homeAssistant, provider = "antigravity", model = "gemini-3-pro", minutesAgo = 3, labels = listOf(bug), branch = "fix/webhook-retry-race", tokens = 41_900),
            card("c-dreamfall", "b-addon", "review", "Return RFC 9457 problem details from every handler", "Every handler now returns application/problem+json on errors. 31 tests pass.", provider = "codex", model = "gpt-5.5", minutesAgo = 26, labels = listOf(api), branch = "api/problem-details", pr = 14, tokens = 61_400),
            card("c-deeplink", "b-addon", "todo", "Rotate webhook signing secrets without downtime", "Support two active signing secrets per endpoint so customers can rotate without dropping deliveries.", machine = homeAssistant, minutesAgo = 190, labels = listOf(security), started = false),
            card("c-node", "b-addon", "done", "Add structured request logging with slog", "One JSON log line per request with the request ID, key and latency.", model = "sonnet", minutesAgo = 1440, labels = listOf(api), branch = "api/slog-logging", tokens = 22_100),
            card("c-boiler", "b-home", "running", "Redesign the checkout summary", "Grouping line items by shipment and running the Playwright checkout flow.", machine = homeAssistant, provider = "codex", model = "gpt-5.5", minutesAgo = 2, labels = listOf(checkout, ui), branch = "checkout/summary", changed = 2, additions = 84, deletions = 19, tokens = 64_800),
            card("c-battery", "b-home", "review", "Lazy-load product images below the fold", "Only the first row gets priority now. LCP dropped from 2.8 s to 1.6 s on the category page.", machine = homeAssistant, model = "sonnet", minutesAgo = 48, labels = listOf(ui), branch = "perf/lazy-images", changed = 3, additions = 42, deletions = 8, tokens = 21_400, pr = 42),
            card("c-dhl", "b-home", "todo", "Add Apple Pay to checkout", "Offer Apple Pay through the payment request API when the browser supports it.", minutesAgo = 600, labels = listOf(checkout), started = false),
            card("c-agy", "b-dieter", "review", "Offline drafts survive app restarts", "Pending edits are written to SQLite before they're sent and replayed on the next launch. 128 shared tests pass.", minutesAgo = 75, labels = listOf(sync), branch = "sync/offline-drafts", pr = 173, tokens = 40_200),
            card("c-wasm", "b-dieter", "todo", "Widgets for pinned notes", "Add home screen widgets that show pinned notes on Android and iOS.", minutesAgo = 15, labels = listOf(ui), started = false),
        )

    private val chats =
        mutableListOf(
            Card(id = "ch-node", scope = "chat", project_id = "p-addon", title = "Why is the search index rebuild slow?", provider = "claude", model = "opus", owner_daemon_id = desktop, created_at = at(90), updated_at = at(40), last_activity_at = at(40), initial_prompt_sent_at = at(90)),
            Card(id = "ch-frigate", scope = "chat", project_id = "p-home", title = "Plan the holiday sale banner", provider = "codex", model = "gpt-5.5", owner_daemon_id = homeAssistant, created_at = at(400), updated_at = at(300), last_activity_at = at(300), initial_prompt_sent_at = at(400)),
            Card(id = "ch-gradle", scope = "chat", title = "Compare Postgres connection poolers", provider = "claude", model = "sonnet", owner_daemon_id = desktop, created_at = at(2000), updated_at = at(1900), last_activity_at = at(1900), initial_prompt_sent_at = at(2000)),
        )

    private val allCards get() = cards + chats

    private var boundBoard = ""
    private var serial = 0
    private val extraTurns = mutableMapOf<String, MutableList<TimelineItem>>()
    private val liveActivity = mutableMapOf<String, String>()
    private val simulatedIds = mutableSetOf<String>()

    fun hasCard(id: String): Boolean = allCards.any { it.id == id && !it.archived }

    fun bindBoard(id: String) {
        boundBoard = id
    }

    private val retired = mutableSetOf<String>()

    /** Archives (retires) or restores a board; like the daemon, only one without open cards. */
    fun setRetired(id: String, value: Boolean) {
        val kept = cards.firstOrNull { it.board_id == id && !it.archived }
        if (value && kept != null)
            throw CoreException(FailureKind.PERMANENT, BoardRetirement.refusal("board has surviving card ${kept.id}")!!)
        if (value) retired += id else retired -= id
    }

    fun move(id: String, lane: String) {
        val index = cards.indexOfFirst { it.id == id }
        if (index >= 0) {
            if (lane != "running") liveActivity.remove(id)
            cards[index] = cards[index].copy(lane = lane, updated_at = at(0), last_activity_at = at(0))
        }
    }

    fun rename(id: String, title: String, prompt: String? = null) {
        val index = cards.indexOfFirst { it.id == id }
        if (index >= 0) {
            val cur = cards[index]
            cards[index] = cur.copy(title = title.ifBlank { cur.title }, initial_prompt = prompt ?: cur.initial_prompt, updated_at = at(0))
            return
        }
        val chatIndex = chats.indexOfFirst { it.id == id }
        if (chatIndex >= 0) chats[chatIndex] = chats[chatIndex].copy(title = title.ifBlank { chats[chatIndex].title }, updated_at = at(0))
    }

    fun setLabels(id: String, labelIds: List<String>) {
        val index = cards.indexOfFirst { it.id == id }
        if (index >= 0) cards[index] = cards[index].copy(label_ids = labelIds, updated_at = at(0))
    }

    fun archive(id: String) {
        liveActivity.remove(id)
        val index = cards.indexOfFirst { it.id == id }
        if (index >= 0) cards[index] = cards[index].copy(archived = true, updated_at = at(0))
    }

    fun send(cardId: String, text: String) {
        val turns = extraTurns.getOrPut(cardId) { mutableListOf() }
        val id = "sent-${turns.size}"
        turns += userItem(id, text, 0)
        turns += assistantItem("$id-reply", 0, listOf(textStep("$id-text", "This is a demo without an agent behind it, so nothing runs. In the real add-on this message would go to the daemon on **${machineName(cardId)}**.")))
    }

    private fun machineName(cardId: String) =
        if (allCards.firstOrNull { it.id == cardId }?.owner_daemon_id == homeAssistant) "Home Assistant" else "workstation"

    // Creation and simulated live runs -----------------------------------------------------------

    fun preview(command: CreationPreviewCommand, defaultProject: String): CreationPreview {
        val intent = command.intent ?: CreationIntent()
        val project =
            projects.firstOrNull { it.id == intent.project_id }
                ?: projects.firstOrNull { it.id == defaultProject }
                ?: projects.first()
        val board =
            if (command.chat) null
            else
                boards.firstOrNull { it.id == intent.board_id && it.project_id == project.id && it.id !in retired }
                    ?: boards.firstOrNull { it.project_id == project.id && it.id !in retired }
        val checkout =
            project.checkouts.firstOrNull { it.id == intent.checkout_id }
                ?: project.checkouts.firstOrNull()
        var provider = intent.selection?.provider.orEmpty().ifEmpty { "claude" }
        var model = intent.selection?.model.orEmpty().ifEmpty { defaultModel(provider) }
        var effort = intent.selection?.effort.orEmpty().ifEmpty { "high" }
        command.choice?.let { choice ->
            choice.provider?.takeIf { it.isNotEmpty() }?.let {
                provider = it
                model = defaultModel(it)
            }
            choice.model?.takeIf { it.isNotEmpty() }?.let { model = it }
            choice.effort?.takeIf { it.isNotEmpty() }?.let { effort = it }
        }
        val selection = HarnessSelection(provider = provider, model = model, effort = effort)
        val lane = if (command.chat) "" else intent.lane.ifEmpty { "running" }
        val resolved =
            intent.copy(
                project_id = project.id,
                board_id = board?.id.orEmpty(),
                checkout_id = checkout?.id.orEmpty(),
                lane = lane,
                selection = selection,
            )
        val models = modelsFor(provider)
        val showAgent = intent.title.isNotEmpty() || intent.prompt.isNotEmpty() || command.choice != null
        return CreationPreview(
            intent = resolved,
            catalog = CreationCatalogState.CREATION_CATALOG_STATE_LIVE,
            agent =
                if (!showAgent) null
                else
                    AgentControlsState(
                        selection = selection,
                        providers = PROVIDERS,
                        models = models,
                        provider_enabled = true,
                        model_enabled = true,
                        effort_enabled = true,
                        provider_label = PROVIDERS.firstOrNull { it.id == provider }?.name ?: provider,
                        model_label = models.firstOrNull { it.id == model }?.name ?: model,
                        effort_label = EFFORTS.firstOrNull { it.id == effort }?.name ?: effort,
                        efforts = EFFORTS,
                        effort_choices = EFFORTS,
                        effort_value = effort,
                    ),
            start_lanes = if (command.chat) emptyList() else board?.lanes.orEmpty(),
            title = if (command.chat) "New chat" else "New task",
            defers_start = !command.chat && lane == "todo",
            opens_after_create = true,
            daemon_id = checkout?.daemon_id ?: desktop,
        )
    }

    fun create(intent: CreationIntent, chat: Boolean, defaultProject: String): Card {
        val project =
            projects.firstOrNull { it.id == intent.project_id }
                ?: projects.firstOrNull { it.id == defaultProject }
                ?: projects.first()
        val checkout =
            project.checkouts.firstOrNull { it.id == intent.checkout_id }
                ?: project.checkouts.firstOrNull()
        val machine = checkout?.daemon_id ?: desktop
        val provider = intent.selection?.provider.orEmpty().ifEmpty { "claude" }
        val model = intent.selection?.model.orEmpty().ifEmpty { defaultModel(provider) }
        val prompt = intent.prompt.trim()
        val title =
            intent.title.trim().ifEmpty {
                if (chat) Titles.chat(prompt, intent.attachments)
                else Titles.creation("", prompt, intent.attachments)
            }
        if (chat) {
            val id = "ch-new-${++serial}"
            val created =
                Card(
                    id = id,
                    scope = "chat",
                    project_id = project.id,
                    title = title,
                    initial_prompt = prompt.ifEmpty { title },
                    initial_prompt_sent_at = at(0),
                    provider = provider,
                    model = model,
                    owner_daemon_id = machine,
                    created_at = at(0),
                    updated_at = at(0),
                    last_activity_at = at(0),
                )
            chats.add(0, created)
            send(id, prompt.ifEmpty { title })
            return created
        }
        val board =
            boards.firstOrNull { it.id == intent.board_id && it.project_id == project.id }
                ?: boards.firstOrNull { it.project_id == project.id }
                ?: boards.first()
        val lane = intent.lane.ifEmpty { "running" }
        val started = lane == "running"
        val id = "c-new-${++serial}"
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28).ifEmpty { "task-$serial" }
        val branch = if (intent.workspace_mode == "project") "" else intent.workspace_branch.ifEmpty { "dieter/$slug" }
        val created =
            Card(
                id = id,
                scope = "board",
                project_id = project.id,
                board_id = board.id,
                lane = lane,
                title = title,
                summary = if (started) "Exploring the repository and running tests…" else "",
                initial_prompt = prompt.ifEmpty { title },
                initial_prompt_sent_at = if (started) at(0) else "",
                provider = provider,
                model = model,
                owner_daemon_id = machine,
                created_at = at(0),
                updated_at = at(0),
                last_activity_at = at(0),
                label_ids = intent.label_ids,
                workspace_mode = if (branch.isEmpty()) "project" else "worktree",
                workspace =
                    if (branch.isEmpty()) null
                    else WorkspaceSummary(mode = "worktree", state = "clean", branch = branch, base_branch = project.base_branch.ifEmpty { "main" }),
            )
        cards.add(0, created)
        simulatedIds += id
        if (started) advanceRun(id, 0)
        return created
    }

    /** Starts a Todo card and initializes its simulated agent turn. */
    fun start(id: String): Boolean {
        val index = cards.indexOfFirst { it.id == id }
        if (index < 0) return false
        val card = cards[index]
        val slug = card.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28).ifEmpty { card.id }
        val branch = card.workspace?.branch.orEmpty().ifEmpty { "dieter/$slug" }
        cards[index] =
            card.copy(
                lane = "running",
                initial_prompt_sent_at = at(0),
                updated_at = at(0),
                last_activity_at = at(0),
                summary = "Exploring the repository and running tests…",
                workspace_mode = "worktree",
                workspace = card.workspace ?: WorkspaceSummary(mode = "worktree", state = "clean", branch = branch, base_branch = "main"),
            )
        simulatedIds += id
        advanceRun(id, 0)
        return true
    }

    /** Advances the simulated agent turn of [id] from exploration (0) through edits/tests (1..2) to Review (3). */
    fun advanceRun(id: String, step: Int): Boolean {
        val index = cards.indexOfFirst { it.id == id }
        if (index < 0) return false
        val card = cards[index]
        if (card.archived || (step > 0 && card.lane != "running")) return false
        val prompt = card.initial_prompt.ifBlank { card.title }
        val branch = card.workspace?.branch.orEmpty().ifEmpty { "dieter/task" }
        when (step) {
            0 -> {
                liveActivity[id] = "Reading the codebase…"
                extraTurns[id] =
                    mutableListOf(
                        userItem("$id-u", prompt, 0),
                        assistantItem(
                            "$id-a", 0,
                            tools = listOf(toolStep("$id-t1", "rg --files internal/", ToolCallStatus.TOOL_CALL_STATUS_RUNNING, "Running")),
                            toolSummary = "Running 1 command",
                            steps = emptyList(),
                        ),
                    )
            }
            1 -> {
                liveActivity[id] = "Editing 2 files and running tests…"
                cards[index] =
                    card.copy(
                        summary = "Editing 2 files and running the test suite with -race…",
                        updated_at = at(0),
                        last_activity_at = at(0),
                        workspace = WorkspaceSummary(mode = "worktree", state = "clean", branch = branch, base_branch = "main", changed_files = 2, additions = 48, deletions = 6, ahead = 1),
                        token_usage = TokenUsage(total_tokens = 18_400, reported_messages = 4),
                    )
                extraTurns[id] =
                    mutableListOf(
                        userItem("$id-u", prompt, 0),
                        assistantItem(
                            "$id-a", 0,
                            tools =
                                listOf(
                                    toolStep("$id-t1", "rg --files internal/", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "14 files"),
                                    toolStep("$id-t2", "Edit 2 files (+48 −6)", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                                    toolStep("$id-t3", "go test -race ./...", ToolCallStatus.TOOL_CALL_STATUS_RUNNING, "Running"),
                                ),
                            toolSummary = "Edited 2 files · running tests",
                            steps = emptyList(),
                        ),
                    )
            }
            2 -> {
                liveActivity[id] = "Opening pull request…"
                cards[index] =
                    card.copy(
                        summary = "Tests pass. Pushing branch and opening pull request…",
                        updated_at = at(0),
                        last_activity_at = at(0),
                        token_usage = TokenUsage(total_tokens = 29_600, reported_messages = 6),
                    )
                extraTurns[id] =
                    mutableListOf(
                        userItem("$id-u", prompt, 0),
                        assistantItem(
                            "$id-a", 0,
                            tools =
                                listOf(
                                    toolStep("$id-t1", "rg --files internal/", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "14 files"),
                                    toolStep("$id-t2", "Edit 2 files (+48 −6)", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                                    toolStep("$id-t3", "go test -race ./...", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "ok (0.42s)"),
                                    toolStep("$id-t4", "gh pr create --fill", ToolCallStatus.TOOL_CALL_STATUS_RUNNING, "Running"),
                                ),
                            toolSummary = "Edited 2 files · ran 2 commands",
                            steps = emptyList(),
                        ),
                    )
            }
            else -> {
                liveActivity.remove(id)
                cards[index] =
                    card.copy(
                        lane = "review",
                        summary = "Implemented the change, verified with -race and opened PR #43. Ready for review.",
                        updated_at = at(0),
                        last_activity_at = at(0),
                        workspace = WorkspaceSummary(mode = "worktree", state = "clean", branch = branch, base_branch = "main", changed_files = 2, additions = 48, deletions = 6, ahead = 1),
                        token_usage = TokenUsage(total_tokens = 36_200, reported_messages = 8),
                        pull_request = PullRequestSummary(provider = "github", number = 43, state = "open", checks_state = "success"),
                    )
                extraTurns[id] =
                    mutableListOf(
                        userItem("$id-u", prompt, 0),
                        assistantItem(
                            "$id-a", 0,
                            tools =
                                listOf(
                                    toolStep("$id-t1", "rg --files internal/", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "14 files"),
                                    toolStep("$id-t2", "Edit 2 files (+48 −6)", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                                    toolStep("$id-t3", "go test -race ./...", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "ok (0.42s)"),
                                    toolStep("$id-t4", "gh pr create --fill", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "PR #43"),
                                ),
                            toolSummary = "Edited 2 files · ran 2 commands",
                            steps =
                                listOf(
                                    textStep(
                                        "$id-reply",
                                        """
                                        Implemented **${card.title}** and verified it with the race detector.

                                        - Updated the implementation and added unit + race tests (`+48 −6` across 2 files)
                                        - Opened **PR #43** on `$branch` — all checks pass, ready for review.
                                        """.trimIndent(),
                                    )
                                ),
                        ),
                    )
            }
        }
        return true
    }

    // Session, workspace, inbox ------------------------------------------------------------------

    private val session =
        SessionSlice(
            phase = SessionSlice.Phase.PHASE_CONNECTED,
            phase_label = "Connected",
            gateway_origin = "https://gateway.getdieter.com",
            synced = true,
            tone = Tone.TONE_SUCCESS,
            machines =
                listOf(
                    MachineEntry(id = desktop, name = "workstation", display_name = "workstation", online = true, available = true, compatible = true, platform = "linux", release_version = "0.6.431", route = "direct", presence = "Online", detail = "Linux · direct", sync_state = MachineSyncState.MACHINE_SYNC_STATE_LIVE, sync_label = "Live", tone = Tone.TONE_SUCCESS, local = false),
                    MachineEntry(id = homeAssistant, name = "homeassistant", display_name = "Home Assistant", online = true, available = true, compatible = true, platform = "linux", release_version = "0.6.431", route = "relay", presence = "Online", detail = "Add-on · relay", sync_state = MachineSyncState.MACHINE_SYNC_STATE_LIVE, sync_label = "Live", tone = Tone.TONE_SUCCESS, local = true),
                ),
        )

    private fun workspace() =
        WorkspaceSlice(
            projects = projects,
            boards = boards.filter { it.id !in retired },
            retired_boards = boards.filter { it.id in retired }.map { it.copy(retired = true) },
            cards = allCards.filter { !it.archived },
            loaded = true,
            board_attention = mapOf("b-addon" to 1, "b-home" to 0, "b-dieter" to 1),
            project_hosts = mapOf("p-addon" to desktop, "p-home" to homeAssistant, "p-dieter" to desktop),
        )

    private fun row(
        id: String,
        kind: String,
        kindLabel: String,
        section: ActivityRow.Section,
        detail: String,
        minutesAgo: Int,
        needsYou: Boolean = false,
        canFinish: Boolean = false,
        startedMinutesAgo: Int = minutesAgo,
    ): ActivityRow {
        val card = allCards.first { it.id == id }
        val project = projects.firstOrNull { it.id == card.project_id }
        val board = boards.firstOrNull { it.id == card.board_id }
        return ActivityRow(
            card = card,
            kind = kind,
            kind_label = kindLabel,
            section = section,
            detail = detail,
            at_millis = millis(minutesAgo),
            started_at_millis = millis(startedMinutesAgo),
            shown_at_millis = millis(minutesAgo),
            project_name = project?.name.orEmpty(),
            board_name = board?.name.orEmpty(),
            chat = card.scope == "chat",
            needs_you = needsYou,
            can_finish = canFinish,
            title = card.title,
        )
    }

    private fun activity(): ActivitySlice {
        val dynamicRunning =
            cards.filter { it.id in simulatedIds && !it.archived && it.lane == "running" }.map {
                row(it.id, "RUNNING", "Running", ActivityRow.Section.SECTION_RUNNING, liveActivity[it.id] ?: "Working…", 0)
            }
        val dynamicReview =
            cards.filter { it.id in simulatedIds && !it.archived && it.lane == "review" }.map {
                row(it.id, "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #43 · 2 files changed", 0, canFinish = true)
            }
        val rows =
            listOf(
                row("c-e2e", "ANSWER", "Answer", ActivityRow.Section.SECTION_ATTENTION, "Approve: go test -race -run TestWebhookRetry", 3, needsYou = true, startedMinutesAgo = 9),
                row("ch-node", "UNREAD", "Unread reply", ActivityRow.Section.SECTION_ATTENTION, "Tokenizing once per document takes the rebuild from 40 s to 17 s.", 40, needsYou = true),
            ) +
                dynamicRunning +
                listOf(
                    row("c-web", "RUNNING", "Running", ActivityRow.Section.SECTION_RUNNING, "Running the race tests across the limiter…", 1, startedMinutesAgo = 12),
                    row("c-boiler", "RUNNING", "Running", ActivityRow.Section.SECTION_RUNNING, "Running the Playwright checkout flow…", 2, startedMinutesAgo = 21),
                ) +
                dynamicReview +
                listOf(
                    row("c-dreamfall", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #14 · 6 files changed", 26, canFinish = true),
                    row("c-battery", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #42 · 3 files changed · +42 −8", 48, canFinish = true),
                    row("c-agy", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #173 · checks passed", 75, canFinish = true),
                    row("c-node", "RECENT", "Recent", ActivityRow.Section.SECTION_RECENT, "Finished yesterday", 1440),
                )
        return ActivitySlice(
            rows = rows,
            summary =
                ActivitySummary(
                    running = 2 + dynamicRunning.size,
                    attention = 2,
                    recent = 4 + dynamicReview.size,
                    review = 3 + dynamicReview.size,
                ),
        )
    }

    private val navigation =
        NavigationSlice(
            caught_up = true,
            project_order = projects.map { it.id },
            expanded_projects = projects.map { it.id },
            projects = ProjectNavigation(order = projects.map { it.id }, unfiled = projects.map { it.id }, expanded = projects.map { it.id }),
        )

    private fun chatsSlice() =
        ChatsSlice(
            projects =
                listOf(
                    ChatProjectSection(project_id = "p-addon", chat_ids = chats.filter { it.project_id == "p-addon" }.map { it.id }, total = chats.count { it.project_id == "p-addon" }, show_chats = true),
                    ChatProjectSection(project_id = "p-home", chat_ids = chats.filter { it.project_id == "p-home" }.map { it.id }, total = chats.count { it.project_id == "p-home" }, show_chats = true),
                ),
            other_ids = chats.filter { it.project_id.isEmpty() || (it.project_id != "p-addon" && it.project_id != "p-home") }.map { it.id },
            visible_ids = chats.map { it.id },
        )

    // Boards ---------------------------------------------------------------------------------------

    private fun flags(card: Card): BoardCardFlags =
        when (card.id) {
            "c-e2e" -> BoardCardFlags(tone = RuntimeTone.RUNTIME_TONE_ATTENTION, runtime_label = "Needs approval", agent = BoardAgentStatus.BOARD_AGENT_STATUS_RUNNING, can_cancel = true)
            else ->
                when (card.lane) {
                    "running" -> BoardCardFlags(tone = RuntimeTone.RUNTIME_TONE_ACTIVE, runtime_label = "Working", agent = BoardAgentStatus.BOARD_AGENT_STATUS_RUNNING, agent_label = "Running", can_cancel = true)
                    "review" -> BoardCardFlags(tone = RuntimeTone.RUNTIME_TONE_DONE, runtime_label = "Ready for review")
                    "done" -> BoardCardFlags(tone = RuntimeTone.RUNTIME_TONE_IDLE)
                    else -> BoardCardFlags(tone = RuntimeTone.RUNTIME_TONE_IDLE, can_start = true, can_edit_draft = true)
                }
        }

    private fun boardView(): BoardViewSlice? {
        val board = boards.firstOrNull { it.id == boundBoard } ?: return null
        val onBoard = cards.filter { it.board_id == board.id && !it.archived }
        val lanes =
            board.lanes.map { lane ->
                BoardLaneView(
                    lane_id = lane.id,
                    name = lane.name,
                    kind =
                        when (lane.id) {
                            "running" -> BoardLaneKind.BOARD_LANE_KIND_RUNNING
                            "review" -> BoardLaneKind.BOARD_LANE_KIND_REVIEW
                            "done" -> BoardLaneKind.BOARD_LANE_KIND_DONE
                            else -> BoardLaneKind.BOARD_LANE_KIND_OTHER
                        },
                    descending = true,
                    card_ids = onBoard.filter { it.lane == lane.id }.sortedByDescending { it.updated_at }.map { it.id },
                )
            }
        return BoardViewSlice(
            target = BoardViewTarget(board_id = board.id),
            lanes = lanes,
            total = onBoard.size,
            machine_ids = listOf(desktop, homeAssistant),
            label_counts = board.labels.associate { label -> label.id to onBoard.count { label.id in it.label_ids } },
            cards = onBoard.associate { it.id to flags(it) },
            state_options =
                listOf(
                    BoardStateOption(state = BoardStateFilter.BOARD_STATE_FILTER_ALL, title = "All"),
                    BoardStateOption(state = BoardStateFilter.BOARD_STATE_FILTER_RUNNING, title = "Running"),
                    BoardStateOption(state = BoardStateFilter.BOARD_STATE_FILTER_WAITING, title = "Waiting"),
                    BoardStateOption(state = BoardStateFilter.BOARD_STATE_FILTER_REVIEW, title = "Review"),
                ),
            state_title = "All",
        )
    }

    // Conversations --------------------------------------------------------------------------------

    private fun textStep(id: String, text: String) =
        TimelineStep(id = id, kind = TimelineStepKind.TIMELINE_STEP_KIND_TEXT, text = text)

    private fun toolStep(id: String, title: String, status: ToolCallStatus, label: String, attention: Boolean = false) =
        TimelineStep(id = id, kind = TimelineStepKind.TIMELINE_STEP_KIND_TOOL, tool_title = title, tool_status = status, tool_status_label = label, tool_attention = attention)

    private fun userItem(id: String, text: String, minutesAgo: Int, attachments: List<TimelineStep> = emptyList()) =
        TimelineItem(
            id = id,
            user = true,
            groups = listOf(TimelineStepGroup(id = "$id-g", steps = attachments + textStep("$id-t", text))),
            created_at_millis = millis(minutesAgo),
            copyable = true,
            delivery = MessageDelivery.MESSAGE_DELIVERY_SYNCED,
        )

    private fun assistantItem(id: String, minutesAgo: Int, steps: List<TimelineStep>, tools: List<TimelineStep> = emptyList(), toolSummary: String = "", plans: List<String> = emptyList()) =
        TimelineItem(
            id = id,
            groups =
                listOfNotNull(
                    if (tools.isEmpty()) null else TimelineStepGroup(id = "$id-tools", activity = true, summary = toolSummary, steps = tools),
                    if (steps.isEmpty()) null else TimelineStepGroup(id = "$id-text", steps = steps),
                ),
            plan_ids = plans,
            created_at_millis = millis(minutesAgo),
            copyable = steps.isNotEmpty(),
        )

    private val webTimeline =
        listOf(
            userItem("w1", "Add per-key rate limiting to the public API. Use a token bucket per API key, return 429 with Retry-After, and make the limits configurable per plan.", 12),
            assistantItem(
                "w2", 10,
                tools =
                    listOf(
                        toolStep("w2-a", "Read internal/httpapi/router.go", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                        toolStep("w2-b", "rg \"Plan\\\\b\" internal", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "3 matches"),
                        toolStep("w2-c", "Edit internal/ratelimit/limiter.go (+38 −6)", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                        toolStep("w2-d", "go test ./internal/ratelimit/ -run TestBucket", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "0.21s"),
                    ),
                toolSummary = "Edited 2 files · ran 2 commands",
                plans = listOf("plan-web"),
                steps =
                    listOf(
                        textStep(
                            "w2-t",
                            """
                            Each API key now gets its own **token bucket**, sized by the key's plan:

                            - `Free` — 60 requests/min (burst 20)
                            - `Team` — 600 requests/min (burst 120)
                            - `Scale` — 3,000 requests/min (burst 600)

                            ```go
                            return WithRequestID(WithLogging(WithAuth(deps.Keys, WithRateLimit(deps.Limiter, mux))))
                            ```
                            """.trimIndent(),
                        )
                    ),
            ),
            userItem(
                "w3", "Nice. Does the docs page render the 429 problem example cleanly, and how do the bucket benchmarks look?", 4,
                attachments = listOf(TimelineStep(id = "w3-a", kind = TimelineStepKind.TIMELINE_STEP_KIND_ATTACHMENT, message_id = "m-w3", part_index = 0)),
            ),
            assistantItem(
                "w4", 1,
                tools = listOf(toolStep("w4-a", "go test -race -count=1 ./internal/ratelimit/... ./internal/httpapi/...", ToolCallStatus.TOOL_CALL_STATUS_RUNNING, "Running")),
                toolSummary = "Running 1 command",
                steps = listOf(
                    toolStep("w4-s", "Screenshot of /docs/rate-limits", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done")
                        .copy(message_id = "m-w4", part_index = 0),
                    textStep("w4-t", "Rejected requests return 429 Too Many Requests with Retry-After and the draft RateLimit headers, so SDKs can back off without guessing. Next I'm running the race tests across the limiter and the HTTP middleware.")),
            ),
        )

    private val webSubagent =
        Subagent(
            id = "sa-size",
            provider = "claude",
            name = "bench-limiter",
            agent_type = "general-purpose",
            description = "Benchmark token bucket contention",
            task = "Benchmark Allow under parallel key contention and report p99 and allocations.",
            status = "completed",
            model = "claude-opus-5-5",
            activity = "Reported the benchmark",
            tool_count = 6,
            requests = 9,
            tokens = 48_200,
            duration_ms = 184_000,
            recent_output = listOf("BenchmarkAllow/single-12  41.2 ns/op  0 B/op", "BenchmarkAllow/parallel-12  88.4 ns/op  0 B/op"),
            started_at = (now - 20.minutes).toString(),
            ended_at = (now - 17.minutes).toString(),
        )

    private val webPlan =
        TaskPlan(
            id = "plan-web",
            state = "active",
            phases =
                listOf(
                    TaskPlanPhase(
                        tasks =
                            listOf(
                                TaskPlanItem(id = "t1", content = "Read the middleware chain and API key resolution", status = "completed"),
                                TaskPlanItem(id = "t2", content = "Add a token bucket per API key", status = "completed"),
                                TaskPlanItem(id = "t3", content = "Run the race tests", active_form = "Running the race tests", status = "in_progress"),
                                TaskPlanItem(id = "t4", content = "Update the OpenAPI rate-limit headers", status = "pending"),
                            )
                    )
                ),
        )

    private fun genericTimeline(card: Card): List<TimelineItem> {
        if (card.id in simulatedIds) return emptyList()
        val prompt = card.initial_prompt.ifBlank { card.title }
        if (card.initial_prompt_sent_at.isEmpty()) return emptyList()
        return listOf(
            userItem("${card.id}-1", prompt, 60),
            assistantItem(
                "${card.id}-2", 30,
                tools =
                    listOf(
                        toolStep("${card.id}-a", "git status", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                        toolStep("${card.id}-b", "Edit 3 files", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                    ),
                toolSummary = "Edited 3 files · ran 1 command",
                steps = listOf(textStep("${card.id}-t", card.summary.ifBlank { "Done. The changes are in the card's workspace." })),
            ),
        ) +
            if (card.id == "c-e2e")
                listOf(
                    assistantItem(
                        "${card.id}-3", 3,
                        steps = listOf(textStep("${card.id}-q", "Retrier.schedule writes r.next without holding the delivery mutex. May I run the 200-iteration race test?")),
                        tools = listOf(toolStep("${card.id}-c", "go test -race -run TestWebhookRetry -count=200 ./internal/webhooks/", ToolCallStatus.TOOL_CALL_STATUS_NEEDS_APPROVAL, "Needs approval", attention = true)),
                        toolSummary = "1 command waiting for approval",
                    )
                )
            else emptyList()
    }

    /** The parts the web conversation's attachment and screenshot steps point at. */
    private val webMessages =
        listOf(
            UiMessage(
                id = "m-w3",
                role = "user",
                parts = listOf(MessagePart(type = "file", media_type = "image/png", filename = "rate-limits.png", url = "data:image/png;base64,${DemoImages.INGRESS}")),
            ),
            UiMessage(
                id = "m-w4",
                role = "assistant",
                parts =
                    listOf(
                        MessagePart(
                            type = "tool-browser_take_screenshot",
                            tool_call_id = "call-shot",
                            tool_name = "browser_take_screenshot",
                            state = "output-available",
                            has_output = true,
                            output_preview = """[{"type":"text","text":"Took the screenshot"},{"type":"image","data":"${DemoImages.SCREENSHOT.take(40)}""",
                            payload_revision = "r1",
                        )
                    ),
            ),
        )

    /** A tool's whole output, as the core loads it when its row is opened. */
    fun toolOutput(load: LoadToolOutput): ToolOutput? =
        if (load.tool_call_id != "call-shot") null
        else
            ToolOutput(
                card_id = load.card_id,
                message_id = load.message_id,
                tool_call_id = load.tool_call_id,
                tool_name = "browser_take_screenshot",
                state = "output-available",
                input_json = """{"fullPage":false}""".encodeUtf8(),
                output_json =
                    """[{"type":"text","text":"Took the screenshot"},{"type":"image","data":"${DemoImages.SCREENSHOT}","mimeType":"image/png"}]""".encodeUtf8(),
                revision = load.revision,
            )

    private fun conversation(cardId: String): ConversationSlice? {
        val card = allCards.firstOrNull { it.id == cardId } ?: return null
        val running = card.lane == "running"
        val timeline = (if (card.id == "c-web") webTimeline else genericTimeline(card)) + extraTurns[card.id].orEmpty()
        return ConversationSlice(
            card_id = card.id,
            messages = if (card.id == "c-web") webMessages else emptyList(),
            daemon_id = card.owner_daemon_id,
            card = card,
            project = projects.firstOrNull { it.id == card.project_id },
            board = boards.firstOrNull { it.id == card.board_id },
            conversation =
                Conversation(
                    card_id = card.id,
                    status = if (running) "running" else "idle",
                    task_plans = if (card.id == "c-web") listOf(webPlan) else emptyList(),
                    subagents = if (card.id == "c-web") listOf(webSubagent) else emptyList(),
                ),
            timeline = timeline,
            state =
                ConversationState(
                    runtime = if (running) "running" else "idle",
                    active_turn = running,
                    working = running,
                    live_activity = liveActivity[card.id] ?: if (card.id == "c-web") "Running the race tests across the limiter…" else if (running) "Working…" else "",
                    turn_started_at_millis = if (running) millis(if (card.id in simulatedIds) 0 else 12) else 0,
                    responding_model = card.model,
                    can_halt = running,
                    can_start = card.initial_prompt_sent_at.isEmpty() && card.scope == "board",
                    chat = card.scope == "chat",
                    context_used_tokens = card.token_usage?.total_tokens ?: 0,
                    context_window_tokens = 400_000,
                    context_percent = (((card.token_usage?.total_tokens ?: 0) * 100) / 400_000).toInt(),
                ),
        )
    }

    // Routing -----------------------------------------------------------------------------------

    fun update(slice: Slice, scope: String): Update? =
        when (slice) {
            Slice.SLICE_SESSION -> Update(slice = slice, scope = scope, session = session)
            Slice.SLICE_WORKSPACE -> Update(slice = slice, scope = scope, workspace = workspace())
            Slice.SLICE_ACTIVITY -> Update(slice = slice, scope = scope, activity = activity())
            Slice.SLICE_NAVIGATION -> Update(slice = slice, scope = scope, navigation = navigation)
            Slice.SLICE_CHATS -> Update(slice = slice, scope = scope, chats = chatsSlice())
            Slice.SLICE_OUTBOX -> Update(slice = slice, scope = scope, outbox = OutboxSlice())
            Slice.SLICE_BOARD_VIEW -> boardView()?.let { Update(slice = slice, scope = scope, board_view = it) }
            Slice.SLICE_CONVERSATION -> conversation(scope)?.let { Update(slice = slice, scope = scope, conversation = it) }
            else -> null
        }

    private companion object {
        val PROVIDERS =
            listOf(
                AgentPickerItem("claude", "Claude Code"),
                AgentPickerItem("codex", "Codex"),
                AgentPickerItem("antigravity", "Antigravity"),
            )
        val EFFORTS =
            listOf(
                EffortOption(id = "low", name = "Low"),
                EffortOption(id = "medium", name = "Medium"),
                EffortOption(id = "high", name = "High"),
            )

        fun modelsFor(provider: String): List<AgentPickerItem> =
            when (provider) {
                "codex" -> listOf(AgentPickerItem("gpt-5.5", "GPT-5.5"))
                "antigravity" -> listOf(AgentPickerItem("gemini-3-pro", "Gemini 3 Pro"))
                else -> listOf(AgentPickerItem("opus", "Opus"), AgentPickerItem("sonnet", "Sonnet"))
            }

        fun defaultModel(provider: String): String = modelsFor(provider).first().id
    }
}
