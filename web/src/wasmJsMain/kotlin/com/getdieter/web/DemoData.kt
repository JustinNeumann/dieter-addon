package com.getdieter.web

import com.getdieter.api.v1.Board
import com.getdieter.api.v1.Card
import com.getdieter.api.v1.Checkout
import com.getdieter.api.v1.Conversation
import com.getdieter.api.v1.Label
import com.getdieter.api.v1.MessagePart
import com.getdieter.api.v1.Project
import com.getdieter.api.v1.Subagent
import com.getdieter.api.v1.PullRequestSummary
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

    private val ui = Label(id = "l-ui", name = "UI", color = "#8B6CFF")
    private val ha = Label(id = "l-ha", name = "Home Assistant", color = "#41BDF5")
    private val bug = Label(id = "l-bug", name = "Bug", color = "#F2545B")
    private val infra = Label(id = "l-infra", name = "Infra", color = "#F5A623")

    private fun checkouts(project: String, vararg machines: String) =
        // absolute, as daemons report them: links in an agent's mail resolve against this
        machines.map { Checkout(id = "$project-$it", project_id = project, daemon_id = it, name = project, path = "/home/demo/$project") }

    private val projects =
        listOf(
            Project(id = "p-addon", name = "dieter-addon", path = "~/dieter-addon", board_count = 1, card_count = 5, chat_count = 1, base_branch = "main", checkouts = checkouts("dieter-addon", "daemon-desktop", "daemon-ha")),
            Project(id = "p-home", name = "Home-Assistant-Sem2", path = "~/Home-Assistant-Sem2", board_count = 1, card_count = 3, chat_count = 1, base_branch = "main", checkouts = checkouts("Home-Assistant-Sem2", "daemon-ha")),
            Project(id = "p-dieter", name = "dieter", path = "~/dieter", board_count = 3, card_count = 2, base_branch = "main", checkouts = checkouts("dieter", "daemon-desktop")),
        )

    private val boards =
        listOf(
            Board(id = "b-addon", project_id = "p-addon", name = "Add-on", lanes = Lanes.DEFAULT, labels = listOf(ui, bug, infra)),
            Board(id = "b-home", project_id = "p-home", name = "Automations", lanes = Lanes.DEFAULT, labels = listOf(ha, bug)),
            Board(id = "b-dieter", project_id = "p-dieter", name = "Upstream", lanes = Lanes.DEFAULT, labels = listOf(ui, infra)),
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
            card("c-web", "b-addon", "running", "Compose web UI behind Ingress", "Compiling the shared Compose UI for wasmJs and serving it from the add-on's Ingress page.", minutesAgo = 1, labels = listOf(ui), branch = "spike/compose-web", changed = 9, additions = 486, deletions = 31, tokens = 182_400),
            card("c-e2e", "b-addon", "running", "E2E: chat field focus on tablet widths", "Playwright run is waiting for approval to start the mock harness.", machine = homeAssistant, provider = "antigravity", model = "gemini-3-pro", minutesAgo = 3, labels = listOf(bug), branch = "e2e/tablet-focus", tokens = 41_900),
            card("c-dreamfall", "b-addon", "review", "Dreamfall proposal for upstream", "docs/proposals/dreamfall.md is ready: motivation, screens and a migration path.", provider = "codex", model = "gpt-5.5", minutesAgo = 26, labels = listOf(ui), branch = "proposal/dreamfall", pr = 14, tokens = 96_300),
            card("c-deeplink", "b-addon", "todo", "Push notifications open the right card", "Deep links from the HA companion app land on the Inbox instead of the card.", machine = homeAssistant, minutesAgo = 190, labels = listOf(bug), started = false),
            card("c-node", "b-addon", "done", "Pin Node 22.19 in the image", "Bumped the base image and verified the harness runtime checksum.", model = "sonnet", minutesAgo = 1440, labels = listOf(infra), branch = "image/node-22-19", tokens = 22_100),
            card("c-boiler", "b-home", "running", "Self-learning boiler: bathroom curve", "Fitting the heating curve from the last 14 days of flow temperatures.", machine = homeAssistant, minutesAgo = 2, labels = listOf(ha), branch = "heating/bathroom-curve", changed = 3, additions = 128, deletions = 12, tokens = 64_800),
            card("c-battery", "b-home", "review", "Charge the battery from grid below 12 ct", "Automation and a dashboard card are ready; tested against yesterday's prices.", machine = homeAssistant, model = "sonnet", minutesAgo = 48, labels = listOf(ha), branch = "energy/cheap-grid-charge", changed = 4, additions = 92, deletions = 7, tokens = 38_200),
            card("c-dhl", "b-home", "todo", "DHL detector: ignore the neighbour's parcels", minutesAgo = 600, labels = listOf(ha, bug), started = false),
            card("c-agy", "b-dieter", "review", "Antigravity harness", "Upstream review requested two naming changes; both are pushed.", minutesAgo = 75, labels = listOf(infra), branch = "antigravity-pr", pr = 173, tokens = 211_000),
            card("c-wasm", "b-dieter", "todo", "Proposal: web target for the shared UI", "wasmJs for :ui and :core:runtime, served by a trusted local proxy.", minutesAgo = 15, labels = listOf(ui), started = false),
        )

    private val chats =
        mutableListOf(
            Card(id = "ch-node", scope = "chat", project_id = "p-addon", title = "Kotlin/Wasm vs Node inside an add-on", provider = "claude", model = "opus", owner_daemon_id = desktop, created_at = at(90), updated_at = at(40), last_activity_at = at(40), initial_prompt_sent_at = at(90)),
            Card(id = "ch-frigate", scope = "chat", project_id = "p-home", title = "Frigate zones for the driveway", provider = "codex", model = "gpt-5.5", owner_daemon_id = homeAssistant, created_at = at(400), updated_at = at(300), last_activity_at = at(300), initial_prompt_sent_at = at(400)),
            Card(id = "ch-gradle", scope = "chat", title = "Gradle offline builds", provider = "claude", model = "sonnet", owner_daemon_id = desktop, created_at = at(2000), updated_at = at(1900), last_activity_at = at(1900), initial_prompt_sent_at = at(2000)),
        )

    private val allCards get() = cards + chats

    private var boundBoard = ""
    private val extraTurns = mutableMapOf<String, MutableList<TimelineItem>>()

    fun hasCard(id: String): Boolean = cards.any { it.id == id && !it.archived }

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
        if (index >= 0) cards[index] = cards[index].copy(lane = lane, updated_at = at(0))
    }

    fun send(cardId: String, text: String) {
        val turns = extraTurns.getOrPut(cardId) { mutableListOf() }
        val id = "sent-${turns.size}"
        turns += userItem(id, text, 0)
        turns += assistantItem("$id-reply", 0, listOf(textStep("$id-text", "This is a demo without an agent behind it, so nothing runs. In the real add-on this message would go to the daemon on **${machineName(cardId)}**.")))
    }

    private fun machineName(cardId: String) =
        if (allCards.firstOrNull { it.id == cardId }?.owner_daemon_id == homeAssistant) "Home Assistant" else "workstation"

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
            cards = allCards,
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

    private fun activity() =
        ActivitySlice(
            rows =
                listOf(
                    row("c-e2e", "ANSWER", "Answer", ActivityRow.Section.SECTION_ATTENTION, "Approve: ./run.sh -g \"chat\"", 3, needsYou = true, startedMinutesAgo = 9),
                    row("ch-node", "UNREAD", "Unread reply", ActivityRow.Section.SECTION_ATTENTION, "Short answer: not faster, but KMP pays off for the UI.", 40, needsYou = true),
                    row("c-web", "RUNNING", "Running", ActivityRow.Section.SECTION_RUNNING, "Linking the production bundle…", 1, startedMinutesAgo = 12),
                    row("c-boiler", "RUNNING", "Running", ActivityRow.Section.SECTION_RUNNING, "Fitting the curve to 14 days of data", 2, startedMinutesAgo = 21),
                    row("c-dreamfall", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #14 · 6 files changed", 26, canFinish = true),
                    row("c-battery", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "4 files changed · +92 −7", 48, canFinish = true),
                    row("c-agy", "REVIEW", "Review", ActivityRow.Section.SECTION_RECENT, "PR #173 · checks passed", 75, canFinish = true),
                    row("c-node", "RECENT", "Recent", ActivityRow.Section.SECTION_RECENT, "Finished yesterday", 1440),
                ),
            summary = ActivitySummary(running = 2, attention = 2, recent = 4, review = 3),
        )

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
                    ChatProjectSection(project_id = "p-addon", chat_ids = listOf("ch-node"), total = 1, show_chats = true),
                    ChatProjectSection(project_id = "p-home", chat_ids = listOf("ch-frigate"), total = 1, show_chats = true),
                ),
            other_ids = listOf("ch-gradle"),
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
        val onBoard = cards.filter { it.board_id == board.id }
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
                    TimelineStepGroup(id = "$id-text", steps = steps),
                ),
            plan_ids = plans,
            created_at_millis = millis(minutesAgo),
            copyable = true,
        )

    private val webTimeline =
        listOf(
            userItem("w1", "Compile the shared Compose UI for the browser and show it inside Home Assistant. Good looks are the goal, so use the real screens, not a lookalike.", 12),
            assistantItem(
                "w2", 10,
                tools =
                    listOf(
                        toolStep("w2-a", "Read apps/kotlin/ui/build.gradle.kts", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                        toolStep("w2-b", "rg \"expect fun\" apps/kotlin", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "11 matches"),
                        toolStep("w2-c", "Read core/runtime/…/platform/Platform.kt", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done"),
                        toolStep("w2-d", "./gradlew wasmJsBrowserDevelopmentExecutableDistribution", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "3m 11s"),
                    ),
                toolSummary = "Explored 3 files · ran 2 commands",
                plans = listOf("plan-web"),
                steps =
                    listOf(
                        textStep(
                            "w2-t",
                            """
                            The UI only talks to the core through `MobileCore`, so the browser build can feed it demo slices and render the **real screens**.

                            What the web target needed:

                            - `wasmJs()` for the Wire models, `:core:runtime` and `:ui`, all of which compile as they are
                            - 3 runtime actuals (`CoreLock`, `secureRandomBytes`, `localUtcOffsetSeconds`)
                            - the JVM stubs for the 11 UI platform hooks (terminal, screen, pickers, share sheet)

                            ```kotlin
                            ComposeViewport(viewportContainerId = "dieter") {
                                MobileApp(MobileStore(DemoCore()))
                            }
                            ```
                            """.trimIndent(),
                        )
                    ),
            ),
            userItem(
                "w3", "Nice. Does Sora load inside the Ingress iframe, and how big is the bundle?", 4,
                attachments = listOf(TimelineStep(id = "w3-a", kind = TimelineStepKind.TIMELINE_STEP_KIND_ATTACHMENT, message_id = "m-w3", part_index = 0)),
            ),
            assistantItem(
                "w4", 1,
                tools = listOf(toolStep("w4-a", "./gradlew wasmJsBrowserDistribution", ToolCallStatus.TOOL_CALL_STATUS_RUNNING, "Running")),
                toolSummary = "Running 1 command",
                steps = listOf(
                    toolStep("w4-s", "Screenshot of next/ in the iframe", ToolCallStatus.TOOL_CALL_STATUS_COMPLETED, "Done")
                        .copy(message_id = "m-w4", part_index = 0),
                    textStep("w4-t", "Sora comes from Compose resources, so it is served next to the bundle and works in the iframe. The development build is about 50 MB of wasm; I'm building the optimized one now to get the real number.")),
            ),
        )

    private val webSubagent =
        Subagent(
            id = "sa-size",
            provider = "claude",
            name = "bundle-size",
            agent_type = "general-purpose",
            description = "Measure the production bundle",
            task = "Build the optimized bundle and report the size of every asset, compressed and not.",
            status = "completed",
            model = "claude-opus-5-5",
            activity = "Reported the sizes",
            tool_count = 6,
            requests = 9,
            tokens = 48_200,
            duration_ms = 184_000,
            recent_output = listOf("dieter.wasm 6.45 MiB (2.1 MiB gzip)", "skiko.wasm 8.25 MiB (3.2 MiB gzip)"),
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
                                TaskPlanItem(id = "t1", content = "Add the wasmJs target", status = "completed"),
                                TaskPlanItem(id = "t2", content = "Render the real UI with demo data", status = "completed"),
                                TaskPlanItem(id = "t3", content = "Serve it inside Home Assistant", active_form = "Serving it inside Home Assistant", status = "in_progress"),
                                TaskPlanItem(id = "t4", content = "Compare with the Android app", status = "pending"),
                            )
                    )
                ),
        )

    private fun genericTimeline(card: Card): List<TimelineItem> {
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
                        steps = listOf(textStep("${card.id}-q", "The Playwright suite needs the mock harness. May I run it?")),
                        tools = listOf(toolStep("${card.id}-c", "./run.sh -g \"chat\"", ToolCallStatus.TOOL_CALL_STATUS_NEEDS_APPROVAL, "Needs approval", attention = true)),
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
                parts = listOf(MessagePart(type = "file", media_type = "image/png", filename = "ingress.png", url = "data:image/png;base64,${DemoImages.INGRESS}")),
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
                    live_activity = if (card.id == "c-web") "Linking the production bundle…" else if (running) "Working…" else "",
                    turn_started_at_millis = if (running) millis(12) else 0,
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
}
