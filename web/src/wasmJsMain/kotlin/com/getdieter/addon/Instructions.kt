package com.getdieter.addon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.ChangeInstructions
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.InstructionsKind
import com.getdieter.addon.v1.InstructionsSlice
import com.getdieter.addon.v1.Slice
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.platform.platformMonospace
import com.getdieter.mobile.store.MobileStore

private val InstructionsKind.glyph
    get() = when (this) {
        InstructionsKind.INSTRUCTIONS_BOARD_CARDS -> Glyph.BOARD
        InstructionsKind.INSTRUCTIONS_CHATS -> Glyph.CHAT
        else -> Glyph.CODE
    }

private val InstructionsKind.tile
    get() = Color(
        when (this) {
            InstructionsKind.INSTRUCTIONS_BOARD_CARDS -> 0xFF0A84FF
            InstructionsKind.INSTRUCTIONS_CHATS -> 0xFF30B158
            else -> 0xFFFF9F0A
        }
    )

/** Settings → Agent instructions: the account's parts of every turn's instructions, each opening its editor. */
@Composable
internal fun InstructionsSection(store: MobileStore) {
    val s = rememberAddonSlice(Slice.SLICE_INSTRUCTIONS)?.instructions
    val actions = rememberAddonActions(store)
    Column(Modifier.testTag("addon-instructions")) {
        SectionHeader("Agent instructions")
        if (s == null || s.items.isEmpty()) {
            StatusRow(s?.error.orEmpty(), "Instructions", actions::refresh)
            return@Column
        }
        Group(s.items) { item, position ->
            ListRow(
                item.title,
                Modifier.testTag("instructions-${item.kind.name.removePrefix("INSTRUCTIONS_").lowercase()}"),
                position = position,
                subtitle = item.summary.ifEmpty { null },
                glyph = item.kind.glyph,
                tile = item.kind.tile,
                accessory = Accessory.CHEVRON,
                onClick = { store.push(MobileRoute.Extension("agent-instructions", item.kind.name)) },
            )
        }
        SectionFooter(
            "Dieter puts these at the start of every turn's instructions: first the part for every agent, then the one " +
                "for board cards or standalone chats. Changes apply from each conversation's next turn."
        )
    }
}

/** One part's editor: the text, what it may use, Save and Reset to Dieter's default. */
@Composable
internal fun InstructionsScreen(store: MobileStore, kindName: String) {
    val kind = InstructionsKind.entries.firstOrNull { it.name == kindName } ?: InstructionsKind.INSTRUCTIONS_EVERY_AGENT
    val s = rememberAddonSlice(Slice.SLICE_INSTRUCTIONS)?.instructions
    val item = s?.items?.firstOrNull { it.kind == kind }
    val actions = rememberAddonActions(store)
    // the page keeps the draft until it is saved; a new text from Dieter replaces it only while untouched
    var draft by remember(kind) { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.text) { if (draft == null || draft == item?.text) draft = item?.text }
    var busy by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val text = draft.orEmpty()
    val problem = item?.let { AddonRules.instructionsProblem(kind, text) }
    val ready = item != null && !busy && problem == null && text != item.text
    fun change(command: ChangeInstructions) {
        busy = true
        actions.send(Command(change_instructions = command)) { done ->
            busy = false
            if (done) {
                draft = null
                store.pop()
            }
        }
    }
    Screen(
        ScreenChrome(
            item?.title ?: "Agent instructions",
            subtitle = "Agent instructions",
            confirm = ChromeAction("instructions-save", "Save", Glyph.CHECK, enabled = ready) { change(ChangeInstructions(kind, text)) },
        )
    ) {
        if (item == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item { StatusRow(s?.error.orEmpty(), "Instructions", actions::refresh) }
            }
            return@Screen
        }
        LazyColumn(Modifier.fillMaxSize().testTag("instructions-editor"), state = listState, contentPadding = padding) {
            item {
                FormColumn {
                    MobileTextField(
                        text,
                        { draft = it },
                        Modifier.fillMaxWidth().testTag("instructions-text").named("Instructions"),
                        textStyle = type.footnote.copy(fontFamily = platformMonospace()),
                        minLines = 8,
                    )
                }
            }
            if (problem != null && text != item.text)
                item { Note(problem, Modifier.padding(horizontal = 32.dp, vertical = 4.dp).testTag("instructions-problem"), palette.destructive) }
            item { SectionFooter(item.help) }
            if (kind == InstructionsKind.INSTRUCTIONS_EVERY_AGENT && s.overrides.isNotEmpty())
                item {
                    SectionHeader("Own templates")
                    Group(s.overrides) { o, position ->
                        ListRow(o.name, position = position, subtitle = o.detail, glyph = if (o.board) Glyph.BOARD else Glyph.FOLDER)
                    }
                    SectionFooter("These use their own template instead of this one (dieter prompt project|board … --inherit returns them to it).")
                }
            item {
                SectionHeader("Variables")
                Text(
                    s.variables.joinToString("\n"),
                    Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    style = type.footnote.copy(fontFamily = platformMonospace()),
                    color = palette.secondaryLabel,
                )
                SectionFooter("Replaced with the conversation's values when the instructions are put together; an unknown variable can't be saved.")
            }
            item {
                Group(listOf(Unit)) { _, position ->
                    ListRow(
                        "Reset to Dieter's default",
                        Modifier.testTag("instructions-reset"),
                        position = position,
                        subtitle = "Later Dieter releases then update it",
                        glyph = Glyph.UNDO,
                        glyphTint = palette.destructive,
                        onClick = { if (!busy) resetting = true },
                    )
                }
            }
        }
    }
    if (resetting)
        ConfirmDialog(
            "Reset \"${item?.title}\" to Dieter's default?",
            "Your text in it is lost.",
            "Reset",
            onConfirm = {
                resetting = false
                change(ChangeInstructions(kind, reset = true))
            },
            onDismiss = { resetting = false },
        )
}
