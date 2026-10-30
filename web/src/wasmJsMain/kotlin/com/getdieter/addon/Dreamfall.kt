@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.getdieter.addon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.DecideIdea
import com.getdieter.addon.v1.DreamfallRun
import com.getdieter.addon.v1.DreamfallSlice
import com.getdieter.addon.v1.DreamfallStage
import com.getdieter.addon.v1.IdeaAction
import com.getdieter.addon.v1.IdeaCard
import com.getdieter.addon.v1.IdeaGroup
import com.getdieter.addon.v1.IdeaMeta
import com.getdieter.addon.v1.PauseDreamfall
import com.getdieter.addon.v1.RemoveDreamfall
import com.getdieter.addon.v1.RunDreamfallStage
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.StageKind
import com.getdieter.addon.v1.TrailEvent
import com.getdieter.addon.v1.Verdict
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.extensions.BoardGroup
import com.getdieter.mobile.extensions.BoardRenderer
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.store.MobileStore

/*
 * Dreamfall boards on the page: an Enumeration agent writes ideas every night, a Navigation agent
 * judges them, and the person picks what gets built on Implementation. What a board, an idea and the
 * settings show, and what a decision does, is the add-on core's (addon/…/core/Dreamfall.kt).
 */

/** The stage boards of every Dreamfall board, as last seen; lets a board open as Dreamfall at once. */
private var knownStageBoards by mutableStateOf(emptySet<String>())

/** Stage boards opened as a plain Kanban board from the Dreamfall menu, while that route is open. */
private val kanban = mutableStateListOf<String>()

@Composable
private fun rememberDreamfalls() =
    rememberAddonSlice(Slice.SLICE_DREAMFALLS)?.dreamfalls?.also { list ->
        if (!list.loading) knownStageBoards = list.dreamfalls.flatMap { it.board_ids }.toSet()
    }

/** Draws a Dreamfall stage board opened from Projects as its Dreamfall board, across the whole width. */
internal fun dreamfallBoards() =
    BoardRenderer(
        claims = { _, boardId ->
            rememberDreamfalls()
            if (boardId in kanban) {
                DisposableEffect(boardId) { onDispose { kanban.remove(boardId) } }
                false
            } else boardId in knownStageBoards
        },
        content = { store, boardId -> DreamfallBoardScreen(store, boardId) },
        wide = true,
    )

/** Opens a stage board as the ordinary Kanban board. */
private fun openKanban(store: MobileStore, boardId: String) {
    if (store.routes.value.top == MobileRoute.Board(boardId)) store.push(MobileRoute.Extension("dreamfall-kanban", boardId))
    else {
        kanban += boardId
        store.push(MobileRoute.Board(boardId))
    }
}

internal fun openIdea(store: MobileStore, card: String) = store.push(MobileRoute.Extension("dreamfall-idea", card))

/** The Tools row's value: ideas waiting for the person. */
@Composable
internal fun dreamfallWaiting(): String? =
    rememberDreamfalls()?.dreamfalls?.sumOf { it.waiting }?.takeIf { it > 0 }?.let { "$it waiting" }

/** Projects lists a Dreamfall board's stage boards as one row: its ideas, and a badge for those waiting for the person. */
@Composable
internal fun dreamfallGroups(): List<BoardGroup> {
    val list = rememberDreamfalls()?.dreamfalls ?: return emptyList()
    return remember(list) {
        list.filter { it.board_ids.isNotEmpty() }.map { d ->
            BoardGroup(
                boardIds = d.board_ids,
                title = d.name,
                subtitle =
                    listOfNotNull(
                            "Dreamfall",
                            "${d.ideas} ${if (d.ideas == 1) "idea" else "ideas"}",
                            if (d.waiting > 0) "${d.waiting} waiting for you" else null,
                        )
                        .joinToString(" · "),
                glyph = Glyph.WATERFALL,
                tint = DREAMFALL_TINT,
                value = "${d.ideas}",
                attention = d.waiting,
                route = MobileRoute.Board(d.board_ids.first()),
            )
        }
    }
}

// ---- Tools → Dreamfall

@Composable
internal fun DreamfallListScreen(store: MobileStore) {
    val list = rememberDreamfalls()
    val actions = rememberAddonActions(store)
    val new = { store.present(MobileRoute.Extension("dreamfall-form", "new:")) }
    // a bar action, not `primary`: beside the navigation rail the shared UI leaves `primary` to the rail's New task
    Screen(ScreenChrome("Dreamfall", actions = listOf(ChromeAction("dreamfall-new", "New Dreamfall board", Glyph.ADD) { new() }))) {
        LazyColumn(Modifier.fillMaxSize().testTag("dreamfall-list"), state = listState, contentPadding = padding) {
            item {
                when {
                    list == null || list.loading || list.error.isNotEmpty() -> StatusRow(list?.error.orEmpty(), "Dreamfall", actions::refresh)
                    list.dreamfalls.isEmpty() ->
                        EmptyState(
                            Glyph.WATERFALL,
                            "No Dreamfall boards",
                            "A Dreamfall board finds ideas for a project every night and lets you pick what gets built.",
                            Modifier.padding(top = 48.dp),
                        ) {
                            DButton("New Dreamfall board", new, Modifier.testTag("dreamfall-new-empty"), glyph = Glyph.ADD)
                        }
                    else ->
                        Group(list.dreamfalls) { d, position ->
                            ListRow(
                                d.name,
                                Modifier.testTag("dreamfall-${d.id}"),
                                position = position,
                                subtitle = d.detail,
                                value = if (d.waiting > 0) "${d.waiting} waiting" else null,
                                glyph = Glyph.WATERFALL,
                                tile = Color(DREAMFALL_TINT),
                                accessory = Accessory.CHEVRON,
                                onClick = { store.push(MobileRoute.Extension("dreamfall", d.id)) },
                            )
                        }
                }
            }
            item {
                SectionFooter(
                    "Every night the Enumeration agent writes new ideas per category and the Navigation agent judges " +
                        "them. You pick what gets built on Implementation; agents never create or move cards there."
                )
            }
        }
    }
}

// ---- a board

private val StageKind.glyph
    get() =
        when (this) {
            StageKind.STAGE_ENUMERATION -> Glyph.SPARKLES
            StageKind.STAGE_NAVIGATION -> Glyph.FILTER
            else -> Glyph.PERSON
        }

private val StageKind.tint
    get() =
        Color(
            when (this) {
                StageKind.STAGE_ENUMERATION -> 0xFFAF52DE
                StageKind.STAGE_NAVIGATION -> 0xFF0A84FF
                else -> 0xFF30B158
            }
        )

private const val DREAMFALL_TINT = 0xFFAF52DE

/** A decision the person is about to confirm. */
private class Asking(val card: String, val action: IdeaAction)

@Composable
internal fun DreamfallBoardScreen(store: MobileStore, scope: String) {
    val board = rememberAddonSlice(Slice.SLICE_DREAMFALL, scope)?.dreamfall
    val actions = rememberAddonActions(store)
    var chosen by remember(scope) { mutableStateOf<StageKind?>(null) }
    var asking by remember { mutableStateOf<Asking?>(null) }
    var remove by remember { mutableStateOf(false) }
    val decide = { card: IdeaCard, action: IdeaAction ->
        if (action.asks) asking = Asking(card.id, action)
        else actions.send(Command(decide_idea = DecideIdea(card.id, action.decision)))
    }
    val ready = board != null && !board.loading && !board.missing
    // a board in use makes its project the last used, as opening a board does
    LaunchedEffect(board?.project_id) { board?.project_id?.let(store::useProject) }
    Screen(
        ScreenChrome(
            board?.name?.ifEmpty { null } ?: "Dreamfall",
            subtitle = board?.subtitle.orEmpty(),
            actions = if (ready) listOf(boardMenu(store, board!!, actions) { remove = true }) else emptyList(),
        )
    ) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).testTag("dreamfall-board")) {
            when {
                board == null || board.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(Modifier.size(28.dp)) }
                board.missing -> EmptyState(Glyph.WATERFALL, "Not available", board.error, Modifier.padding(top = 48.dp))
                else ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 900.dp
                        val selected = chosen ?: board.first
                        Column(Modifier.fillMaxSize()) {
                            Funnel(board.stages, if (wide) null else selected, wide) { chosen = it }
                            if (wide)
                                Row(
                                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScreenMargin),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    board.stages.forEach { stage ->
                                        Column(
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(if (apple) 26.dp else 24.dp))
                                                .background(palette.fill.copy(alpha = palette.fill.alpha * .6f))
                                        ) {
                                            StageList(store, board, stage, padding.calculateBottomPadding(), wide = true, decide)
                                        }
                                    }
                                }
                            else
                                board.stages.firstOrNull { it.kind == selected }?.let { stage ->
                                    key(stage.kind) { StageList(store, board, stage, padding.calculateBottomPadding(), wide = false, decide) }
                                }
                        }
                    }
            }
        }
    }
    asking?.let { a -> DecideDialog(store, a) { asking = null } }
    if (remove && board != null)
        ConfirmDialog(
            "Remove ${board.name}?",
            "Its schedules and records go. The three boards and their cards stay as ordinary boards.",
            "Remove",
            onConfirm = {
                remove = false
                actions.send(Command(remove_dreamfall = RemoveDreamfall(board.id))) { if (it) store.pop() }
            },
            onDismiss = { remove = false },
            destructive = true,
        )
}

private fun boardMenu(store: MobileStore, board: DreamfallSlice, actions: AddonActions, remove: () -> Unit): ChromeAction =
    ChromeAction(
        "dreamfall-menu",
        "Dreamfall options",
        Glyph.MORE_HORIZONTAL,
        menu =
            listOf(
                MenuSection(
                    board.stages
                        .filter { it.can_run }
                        .map { stage ->
                            ChromeAction("dreamfall-run-${stage.board_id}", "Run ${stage.name} now", Glyph.PLAY) {
                                actions.send(Command(run_dreamfall_stage = RunDreamfallStage(board.id, stage.kind)))
                            }
                        } +
                        ChromeAction(
                            "dreamfall-pause",
                            if (board.paused) "Resume the schedules" else "Pause the schedules",
                            if (board.paused) Glyph.PLAY_CIRCLE else Glyph.PAUSE,
                        ) {
                            actions.send(Command(pause_dreamfall = PauseDreamfall(board.id, paused = !board.paused)))
                        }
                ),
                MenuSection(
                    listOf(
                        ChromeAction("dreamfall-settings", "Settings…", Glyph.SETTINGS) {
                            store.present(MobileRoute.Extension("dreamfall-form", board.id))
                        },
                        ChromeAction(
                            "dreamfall-kanban",
                            "Open a stage as a Kanban board",
                            Glyph.BOARD,
                            menu =
                                listOf(
                                    MenuSection(
                                        board.stages.map { stage ->
                                            ChromeAction("dreamfall-kanban-${stage.board_id}", stage.name, stage.kind.glyph) {
                                                openKanban(store, stage.board_id)
                                            }
                                        }
                                    )
                                ),
                        ),
                    )
                ),
                MenuSection(listOf(ChromeAction("dreamfall-remove", "Remove Dreamfall…", Glyph.TRASH, destructive = true) { remove() })),
            ),
    )

/** The stages in a row with how many ideas wait in each and how many went on; on phones they are the tabs. */
@Composable
private fun Funnel(stages: List<DreamfallStage>, selected: StageKind?, wide: Boolean, select: (StageKind) -> Unit) = BoxWithConstraints {
    // what went on between stages; a phone shows only the arrows, so the names fit
    val roomy = maxWidth >= 520.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 8.dp).testTag("dreamfall-funnel"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, stage ->
            if (index > 0)
                Column(Modifier.padding(horizontal = if (roomy) 4.dp else 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Glyph.CHEVRON_RIGHT, null, tint = palette.tertiaryLabel, size = 16.dp)
                    if (roomy && stage.passes.isNotEmpty()) Text(stage.passes, style = type.caption, color = palette.secondaryLabel, maxLines = 1)
                }
            val on = selected == stage.kind
            Row(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) stage.kind.tint.copy(alpha = if (palette.dark) .28f else .14f) else palette.fill.copy(alpha = palette.fill.alpha * .6f))
                    .then(if (selected != null) Modifier.pressable(onClick = { select(stage.kind) }) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { contentDescription = "${stage.name}, ${stage.waiting} waiting" }
                    .testTag("dreamfall-stage-tab-${stage.kind.name.removePrefix("STAGE_").lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val count: @Composable () -> Unit = {
                    Text("${stage.waiting}", style = type.headline, color = if (stage.waiting > 0) palette.label else palette.tertiaryLabel)
                }
                if (wide) {
                    Icon(stage.kind.glyph, null, tint = stage.kind.tint, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stage.name, style = type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stage.funnel, style = type.caption, color = palette.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    count()
                } else
                    // phones: the name under the icon and count, so three stages fit side by side
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(stage.kind.glyph, null, tint = stage.kind.tint, size = 16.dp)
                            Spacer(Modifier.weight(1f))
                            count()
                        }
                        Text(stage.name, style = type.caption.copy(fontWeight = FontWeight.SemiBold), color = palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
            }
        }
    }
}

/** New task on a stage's board; an idea added by hand waits in the queue (Todo) like the agent's. */
private fun newOnStage(store: MobileStore, board: DreamfallSlice, stage: DreamfallStage) {
    store.newConversation(false)
    val intent = store.creation.intent.value.copy(project_id = board.project_id, board_id = stage.board_id, checkout_id = "")
    store.creation.update(if (stage.human) intent else intent.copy(lane = "todo"))
}

@Composable
private fun StageList(
    store: MobileStore,
    board: DreamfallSlice,
    stage: DreamfallStage,
    bottom: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    decide: (IdeaCard, IdeaAction) -> Unit,
) {
    val actions = rememberAddonActions(store)
    val open = remember(stage.kind) { mutableStateMapOf<IdeaGroup.Kind, Boolean>() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("dreamfall-stage-${stage.kind.name.removePrefix("STAGE_").lowercase()}"),
        contentPadding = PaddingValues(start = if (wide) 12.dp else ScreenMargin, end = if (wide) 12.dp else ScreenMargin, top = 6.dp, bottom = bottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("head") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (wide) Text(stage.name, style = type.headline, color = palette.label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (stage.human) Glyph.PERSON else Glyph.SPARKLES, null, tint = palette.secondaryLabel, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stage.actor, style = type.footnote, color = palette.secondaryLabel)
                }
                stage.run?.let { RunLine(store, board, stage, it, actions) }
                if (stage.can_add)
                    DButton(
                        if (stage.human) "New task" else "New idea",
                        { newOnStage(store, board, stage) },
                        Modifier.testTag("dreamfall-new-${stage.kind.name.removePrefix("STAGE_").lowercase()}"),
                        kind = ButtonKind.PLAIN,
                        glyph = Glyph.ADD,
                    )
            }
        }
        if (stage.gate.isNotEmpty())
            item("gate") {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StageKind.STAGE_IMPLEMENTATION.tint.copy(alpha = if (palette.dark) .2f else .1f))
                        .padding(12.dp),
                ) {
                    Icon(Glyph.LOCK, null, tint = StageKind.STAGE_IMPLEMENTATION.tint, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("You decide what gets built", style = type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = palette.label)
                        Text(stage.gate, style = type.footnote, color = palette.secondaryLabel)
                        if (stage.gate_extra.isNotEmpty()) Text(stage.gate_extra, style = type.footnote, color = palette.secondaryLabel)
                    }
                }
            }
        if (stage.empty.isNotEmpty())
            item("empty") { Text(stage.empty, Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = type.subheadline, color = palette.secondaryLabel) }
        stage.groups.forEach { group -> ideaGroup(store, stage, group, open, decide) }
    }
}

private fun LazyListScope.ideaGroup(
    store: MobileStore,
    stage: DreamfallStage,
    group: IdeaGroup,
    open: MutableMap<IdeaGroup.Kind, Boolean>,
    decide: (IdeaCard, IdeaAction) -> Unit,
) {
    val shown = !group.collapsible || open[group.kind] == true
    if (group.title.isNotEmpty())
        item("group-${group.kind}") {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (group.collapsible) Modifier.pressable(onClick = { open[group.kind] = !shown }) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { contentDescription = "${group.title}, ${group.count}" + if (group.collapsible) if (shown) ", shown" else ", folded" else "" }
                    .testTag("dreamfall-group-${group.kind.name.removePrefix("KIND_").lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group.title, Modifier.weight(1f), style = type.footnote.copy(fontWeight = FontWeight.SemiBold), color = palette.secondaryLabel)
                Text(group.count, style = type.footnote, color = palette.secondaryLabel)
                if (group.collapsible) {
                    Spacer(Modifier.width(4.dp))
                    Icon(if (shown) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, null, tint = palette.secondaryLabel, size = 14.dp)
                }
            }
        }
    if (shown)
        items(group.cards, key = { "${group.kind}-${it.id}" }) { card ->
            IdeaCardView(card, stage.kind, onOpen = { if (card.opens_conversation) store.openConversation(card.id) else openIdea(store, card.id) }) {
                decide(card, it)
            }
        }
}

@Composable
private fun RunLine(store: MobileStore, board: DreamfallSlice, stage: DreamfallStage, run: DreamfallRun, actions: AddonActions) {
    val tone =
        when (run.status) {
            DreamfallRun.Status.STATUS_WORKING -> palette.accent
            DreamfallRun.Status.STATUS_DONE -> palette.success
            DreamfallRun.Status.STATUS_FAILED -> palette.destructive
            DreamfallRun.Status.STATUS_WAITING -> palette.warning
            else -> palette.tertiaryLabel
        }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (run.card_id.isNotEmpty()) Modifier.pressable(onClick = { store.openConversation(run.card_id) }) else Modifier)
            .padding(vertical = 4.dp)
            .testTag("dreamfall-run-${stage.kind.name.removePrefix("STAGE_").lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (run.status == DreamfallRun.Status.STATUS_WORKING) LiveDot(tone) else Box(Modifier.size(8.dp).background(tone, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(run.text, style = type.footnote, color = palette.label)
            if (run.note.isNotEmpty()) Text(run.note, style = type.caption, color = palette.secondaryLabel, maxLines = 3)
        }
        if (stage.can_run && run.status != DreamfallRun.Status.STATUS_WORKING)
            DButton(
                "Run now",
                { actions.send(Command(run_dreamfall_stage = RunDreamfallStage(board.id, stage.kind))) },
                Modifier.testTag("dreamfall-run-now-${stage.kind.name.removePrefix("STAGE_").lowercase()}"),
                kind = ButtonKind.PLAIN,
                glyph = Glyph.PLAY,
            )
    }
}

private val CATEGORY_COLORS = listOf("AF52DE", "0A84FF", "30B158", "FF9F0A", "FF375F", "5E5CE6", "32ADE6", "A2845E")

private fun categoryColor(name: String) = CATEGORY_COLORS[name.lowercase().fold(0) { h, c -> (h * 31 + c.code) and 0xFFFF } % CATEGORY_COLORS.size]

private val IdeaMeta.Kind.glyph
    get() =
        when (this) {
            IdeaMeta.Kind.KIND_FROM -> Glyph.FORK
            IdeaMeta.Kind.KIND_TO -> Glyph.ARROW_UP
            IdeaMeta.Kind.KIND_AGENT -> Glyph.SPARKLES
            IdeaMeta.Kind.KIND_OUTSIDE -> Glyph.WARNING
            IdeaMeta.Kind.KIND_WORKSPACE -> Glyph.BRANCH
            else -> Glyph.PERSON
        }

private val DecideIdea.Decision.glyph
    get() =
        when (this) {
            DecideIdea.Decision.DECISION_START -> Glyph.PLAY
            DecideIdea.Decision.DECISION_REVIEW -> Glyph.CHECKLIST
            DecideIdea.Decision.DECISION_PARK, DecideIdea.Decision.DECISION_RELEASE -> Glyph.ARCHIVE
            DecideIdea.Decision.DECISION_RESTORE -> Glyph.UNDO
            else -> Glyph.CHEVRON_RIGHT
        }

@Composable
private fun IdeaCardView(card: IdeaCard, stage: StageKind, onOpen: () -> Unit, act: (IdeaAction) -> Unit) {
    ContentCard(
        Modifier.testTag("idea-${card.id}").semantics { contentDescription = listOf(card.category, card.title, card.status).filter { it.isNotEmpty() }.joinToString(", ") },
        onClick = onOpen,
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (card.category.isNotEmpty()) LabelPill(card.category, categoryColor(card.category))
            Row(verticalAlignment = Alignment.Top) {
                Text(card.title, Modifier.weight(1f), style = type.headline, color = palette.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.age.isNotEmpty()) Text(card.age, Modifier.padding(start = 10.dp, top = 2.dp), style = type.footnote, color = palette.secondaryLabel)
            }
            if (card.excerpt.isNotEmpty())
                Text(card.excerpt, style = type.subheadline, color = palette.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            card.verdict?.let { VerdictLine(it, maxLines = 2) }
            if (card.metas.isNotEmpty())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    card.metas.forEach { meta ->
                        MetaPill(meta.text, meta.kind.glyph, tint = if (meta.kind == IdeaMeta.Kind.KIND_OUTSIDE) palette.warning else palette.secondaryLabel)
                    }
                }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tone = toneColor(card.status_tone)
                    if (card.live) LiveDot(tone, size = 7.dp) else if (stage == StageKind.STAGE_IMPLEMENTATION) Box(Modifier.size(7.dp).background(tone, CircleShape))
                    if (card.live || stage == StageKind.STAGE_IMPLEMENTATION) Spacer(Modifier.width(6.dp))
                    Text(card.status, style = type.footnote, color = if (stage == StageKind.STAGE_IMPLEMENTATION) tone else palette.secondaryLabel, maxLines = 1)
                }
                if (card.detail.isNotEmpty()) Text(card.detail, style = type.caption, color = palette.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val action = card.action
            when {
                card.busy.isNotEmpty() ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(card.busy, style = type.footnote, color = palette.secondaryLabel)
                    }
                action != null ->
                    DButton(
                        action.label,
                        { act(action) },
                        Modifier.testTag("idea-${action.decision.name.removePrefix("DECISION_").lowercase()}-${card.id}"),
                        kind = if (action.primary) ButtonKind.PROMINENT else ButtonKind.TONAL,
                        glyph = action.decision.glyph,
                    )
            }
        }
    }
}

@Composable
private fun VerdictLine(verdict: Verdict, maxLines: Int = Int.MAX_VALUE) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (verdict.good) Glyph.CHECK_CIRCLE else Glyph.CANCEL, null, tint = if (verdict.good) palette.success else palette.secondaryLabel, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            listOf(verdict.label, verdict.text).filter { it.isNotEmpty() }.joinToString(" · "),
            style = type.footnote,
            color = palette.secondaryLabel,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Confirms a decision that asks: a note for the trail, and for a pick where the work happens. */
@Composable
private fun DecideDialog(store: MobileStore, asking: Asking, close: () -> Unit) {
    val actions = rememberAddonActions(store)
    val action = asking.action
    val pick = action.decision == DecideIdea.Decision.DECISION_PICK
    var note by remember { mutableStateOf("") }
    var worktree by remember { mutableStateOf(true) }
    var start by remember { mutableStateOf(false) }
    AddonDialog(
        action.ask_title,
        "dreamfall-decide",
        buttons = {
            DialogButton("Cancel", close, tag = "dreamfall-decide-cancel")
            DialogButton(if (pick && start) "Pick and start" else action.label.removeSuffix(" anyway"), {
                close()
                actions.send(Command(decide_idea = DecideIdea(asking.card, action.decision, note = note, worktree = worktree, start = start)))
            }, tag = "dreamfall-decide-confirm")
        },
    ) {
        Text(action.ask_text, style = type.body, color = palette.label)
        MobileTextField(
            note,
            { note = it },
            Modifier.fillMaxWidth().testTag("dreamfall-note").named("Note"),
            label = { Text("Note (optional)") },
            placeholder = { Text("Why, or what to keep in mind") },
            minLines = 2,
        )
        if (pick) {
            Segmented(listOf("Own worktree", "Project folder"), if (worktree) 0 else 1, { worktree = it == 0 }, testTagPrefix = "dreamfall-workspace")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start right away", style = type.body, color = palette.label)
                    Text("Otherwise it waits in Todo as a draft", style = type.footnote, color = palette.secondaryLabel)
                }
                DSwitch(start, { start = it }, Modifier.testTag("dreamfall-start").named("Start right away"))
            }
        }
    }
}

// ---- an idea

private val TrailEvent.Kind.glyph
    get() =
        when (this) {
            TrailEvent.Kind.KIND_CREATED -> Glyph.SPARKLES
            TrailEvent.Kind.KIND_JUDGED -> Glyph.FILTER
            TrailEvent.Kind.KIND_RELEASED, TrailEvent.Kind.KIND_PARKED -> Glyph.ARCHIVE
            TrailEvent.Kind.KIND_TRANSFERRED, TrailEvent.Kind.KIND_PICKED -> Glyph.ARROW_UP
            TrailEvent.Kind.KIND_RESTORED -> Glyph.UNDO
            TrailEvent.Kind.KIND_STARTED -> Glyph.PLAY
            TrailEvent.Kind.KIND_NOW -> Glyph.CIRCLE_FILL
            else -> Glyph.HISTORY
        }

@Composable
internal fun IdeaScreen(store: MobileStore, cardId: String) {
    val idea = rememberAddonSlice(Slice.SLICE_IDEA, cardId)?.idea
    val actions = rememberAddonActions(store)
    var asking by remember { mutableStateOf<Asking?>(null) }
    Screen(
        ScreenChrome(
            idea?.title?.ifEmpty { null } ?: "Idea",
            subtitle = idea?.subtitle.orEmpty(),
            actions = listOf(ChromeAction("idea-conversation", "Open the conversation", Glyph.CHAT) { store.openConversation(cardId) }),
        )
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("dreamfall-idea"), state = listState, contentPadding = padding) {
            when {
                idea == null || idea.loading ->
                    item { Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(28.dp)) } }
                idea.missing -> item { EmptyState(Glyph.SPARKLES, "Not available", idea.error, Modifier.padding(top = 48.dp)) }
                else -> {
                    item("head") {
                        Column(Modifier.padding(horizontal = ScreenMargin, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                MetaPill(idea.stage_label, idea.stage.glyph, tint = idea.stage.tint)
                                if (idea.category.isNotEmpty()) LabelPill(idea.category, categoryColor(idea.category))
                            }
                            idea.verdict?.let { VerdictLine(it) }
                            if (idea.busy.isNotEmpty())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spinner(Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(idea.busy, style = type.footnote, color = palette.secondaryLabel)
                                }
                            else if (idea.actions.isNotEmpty())
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    idea.actions.forEach { action ->
                                        DButton(
                                            action.label,
                                            {
                                                if (action.asks) asking = Asking(cardId, action)
                                                else actions.send(Command(decide_idea = DecideIdea(cardId, action.decision)))
                                            },
                                            Modifier.testTag("idea-action-${action.decision.name.removePrefix("DECISION_").lowercase()}"),
                                            kind = if (action.primary) ButtonKind.PROMINENT else ButtonKind.TONAL,
                                            glyph = action.decision.glyph,
                                        )
                                    }
                                }
                        }
                    }
                    if (idea.brief.isNotEmpty())
                        item("brief") {
                            SectionHeader("Brief")
                            ContentCard(Modifier.padding(horizontal = ScreenMargin).testTag("idea-brief")) { RichText(idea.brief) }
                        }
                    if (idea.provenance.size > 1)
                        item("provenance") {
                            SectionHeader("Where it came from")
                            Group(idea.provenance) { step, position ->
                                ListRow(
                                    step.title,
                                    position = position,
                                    subtitle = step.detail,
                                    glyph = step.stage.glyph,
                                    glyphTint = step.stage.tint,
                                    selected = step.current,
                                    accessory = if (step.current) Accessory.NONE else Accessory.CHEVRON,
                                    onClick = if (step.current) null else ({ openIdea(store, step.card_id) }),
                                )
                            }
                        }
                    item("trail-head") { SectionHeader("Trail") }
                    if (idea.trail_loading) item("trail-loading") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() } }
                    if (idea.error.isNotEmpty()) item("trail-error") { Note(idea.error, Modifier.padding(horizontal = 32.dp), palette.warning) }
                    items(idea.trail.size, key = { "trail-$it" }) { i -> TrailRow(idea.trail[i], last = i == idea.trail.lastIndex) }
                    item("end") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
    asking?.let { a -> DecideDialog(store, a) { asking = null } }
}

@Composable
private fun TrailRow(event: TrailEvent, last: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenMargin + 4.dp).height(IntrinsicSize.Min).testTag("trail-${event.kind.name.removePrefix("KIND_").lowercase()}")) {
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (event.human) StageKind.STAGE_IMPLEMENTATION.tint.copy(alpha = .16f) else palette.fill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(event.kind.glyph, null, tint = if (event.human) StageKind.STAGE_IMPLEMENTATION.tint else palette.secondaryLabel, size = 13.dp)
            }
            if (!last) Box(Modifier.width(1.5.dp).weight(1f).background(palette.separator))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.title, style = type.subheadline.copy(fontWeight = FontWeight.Medium), color = palette.label)
            if (event.detail.isNotEmpty()) Text(event.detail, style = type.footnote, color = palette.secondaryLabel)
            if (event.quote.isNotEmpty()) Text(event.quote, style = type.footnote.copy(fontStyle = FontStyle.Italic), color = palette.secondaryLabel)
        }
    }
}
