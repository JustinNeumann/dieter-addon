package com.getdieter.addon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.Answer
import com.getdieter.addon.v1.AnswerQuestion
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.Question
import com.getdieter.addon.v1.Slice
import com.getdieter.mobile.design.*
import com.getdieter.mobile.store.MobileStore

/** Inbox: the conversations whose agent waits for an answer, above everything else. */
@Composable
internal fun WaitingForAnswerSection(store: MobileStore) {
    val questions = rememberAddonSlice(Slice.SLICE_QUESTIONS)?.questions?.questions.orEmpty()
    val workspace by store.workspace.slice.collectAsState()
    if (questions.isEmpty()) return
    val byCard = questions.groupBy { it.card_id }
    Column(Modifier.testTag("addon-questions")) {
        SectionHeader("Waiting for your answer", prominent = true)
        Group(byCard.entries.toList()) { (cardId, asked), position ->
            ListRow(
                workspace.cards.firstOrNull { it.id == cardId }?.title?.ifBlank { null } ?: "Conversation",
                Modifier.testTag("question-row-$cardId"),
                position = position,
                subtitle = asked.first().summary,
                glyph = Glyph.INFO,
                tile = Color(0xFFFF9F0A),
                accessory = Accessory.CHEVRON,
                onClick = { store.openConversation(cardId) },
            )
        }
    }
}

/** Conversation: the agent's open question with its options, above the composer. */
@Composable
internal fun QuestionWidget(store: MobileStore, cardId: String) {
    rememberAddonSlice(Slice.SLICE_QUESTIONS, cardId)?.questions?.questions.orEmpty().forEach { question ->
        key(question.id) { QuestionCard(store, question) }
    }
}

@Composable
private fun QuestionCard(store: MobileStore, question: Question) {
    // the draft is the page's; what an answer needs and what a tap changes are the add-on core's rules
    var answers by remember(question.id) { mutableStateOf(mapOf<String, Answer>()) }
    var sending by remember(question.id) { mutableStateOf(false) }
    val actions = rememberAddonActions(store)
    fun submit(decline: Boolean) {
        sending = true
        actions.send(Command(answer_question = AnswerQuestion(question.id, if (decline) emptyMap() else answers, decline))) { sent ->
            if (!sent) sending = false
        }
    }
    ContentCard(
        Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp).heightIn(max = 420.dp).testTag("question-${question.id}"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Glyph.INFO, null, size = 18.dp, tint = palette.secondaryLabel)
                Text("The agent asks", style = type.subheadline, color = palette.secondaryLabel)
            }
            question.items.forEach { item ->
                val answer = answers[item.id]
                Spacer(Modifier.height(10.dp))
                if (item.chip.isNotEmpty()) Note(item.chip.uppercase())
                Text(item.question, style = type.headline, modifier = Modifier.padding(vertical = 4.dp))
                item.options.forEach { option ->
                    val on = answer != null && option.id in answer.option_ids
                    val pick = { answers = answers + (item.id to AddonRules.choose(item, answer, option.id)) }
                    Row(
                        Modifier.fillMaxWidth()
                            .then(
                                if (item.multiple) Modifier.toggleable(on, enabled = !sending, role = Role.Checkbox) { pick() }
                                else Modifier.selectable(on, enabled = !sending, role = Role.RadioButton, onClick = pick)
                            )
                            .padding(vertical = 6.dp)
                            .testTag("question-option-${item.id}-${option.id}"),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (item.multiple) Checkbox(on, null) else RadioButton(on, null)
                        Column(Modifier.padding(start = 10.dp, top = 2.dp)) {
                            Text(option.label, style = type.bodyEmphasized)
                            if (option.description.isNotEmpty()) Note(option.description)
                        }
                    }
                }
                if (item.free_form) {
                    val label = if (item.options.isEmpty()) "Your answer" else "Or your own answer"
                    MobileTextField(
                        answer?.freeform.orEmpty(),
                        { text -> answers = answers + (item.id to (answer ?: Answer()).copy(freeform = text)) },
                        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("question-free-${item.id}").named(label),
                        enabled = !sending,
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DButton("Decline", { submit(true) }, Modifier.testTag("question-decline"), kind = ButtonKind.PLAIN, enabled = !sending)
                DButton(
                    "Answer",
                    { submit(false) },
                    Modifier.testTag("question-answer"),
                    enabled = AddonRules.canAnswer(question, answers) && !sending,
                    loading = sending,
                )
            }
        }
    }
}
