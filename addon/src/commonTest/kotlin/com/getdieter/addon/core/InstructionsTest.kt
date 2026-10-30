package com.getdieter.addon.core

import com.getdieter.addon.v1.ChangeInstructions
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.InstructionsKind
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class InstructionsTest {
    private val bridge = FakeBridge()
    private val prompts =
        """{"template":"You are working inside Dieter on project {{project.name}}.\n\n{{project.instructions_block}}\n{{labels.instructions_block}}",
           "board_skill":"Use the Dieter CLI.\nMove the card to Review when done.","chat_skill":"",
           "variables":["project.name","project.instructions_block","labels.instructions_block"],
           "overrides":[{"kind":"project","id":"p1","name":"Dieter"},{"kind":"board","id":"b1","name":"Main","project":"Garden"}]}"""

    private fun TestScope.core(): AddonCore =
        AddonCore(bridge, backgroundScope, pollInterval = 2.seconds, dispatcher = StandardTestDispatcher(testScheduler)).also { it.start() }

    private fun TestScope.watch(core: AddonCore): MutableList<Update> {
        val seen = mutableListOf<Update>()
        core.observe(Slice.SLICE_INSTRUCTIONS, "") { seen += it }
        runCurrent()
        return seen
    }

    @Test
    fun theThreePartsWithSummariesVariablesAndOwnTemplates() = runTest {
        bridge.answer("GET prompts", prompts)
        val slice = watch(core()).last().instructions!!
        assertEquals(listOf("Every agent", "Board cards", "Standalone chats"), slice.items.map { it.title })
        val every = slice.items[0]
        assertEquals(InstructionsKind.INSTRUCTIONS_EVERY_AGENT, every.kind)
        assertEquals("You are working inside Dieter on project {{project.name}}.", every.summary, "lines of variables only are skipped")
        assertEquals("Move the card to Review when done.", slice.items[1].summary)
        assertEquals("", slice.items[2].summary)
        assertEquals("{{project.name}}", slice.variables.first())
        assertEquals(listOf("Project", "Board of Garden"), slice.overrides.map { it.detail })
    }

    @Test
    fun aSaveAndAResetGoToTheBridgeAndShowItsAnswer() = runTest {
        bridge.answer("GET prompts", prompts)
        bridge.answer("POST prompts", prompts.replace("Use the Dieter CLI.", "Use the CLI."))
        val core = core()
        val seen = watch(core)
        val saved = core.dispatch(Command(change_instructions = ChangeInstructions(InstructionsKind.INSTRUCTIONS_BOARD_CARDS, "Use the CLI.")))
        assertEquals("Saved; applies from each conversation's next turn.", saved.message)
        assertEquals("""{"board_skill":"Use the CLI."}""", bridge.sent("POST prompts").last().toString())
        runCurrent()
        assertEquals("Use the CLI.\nMove the card to Review when done.", seen.last().instructions!!.items[1].text)
        core.dispatch(Command(change_instructions = ChangeInstructions(InstructionsKind.INSTRUCTIONS_CHATS, reset = true)))
        assertEquals("""{"reset":["chat_skill"]}""", bridge.sent("POST prompts").last().toString())
    }

    @Test
    fun theRulesRefuseWhatDieterWouldBeforeItIsSent() = runTest {
        bridge.answer("GET prompts", prompts)
        val core = core()
        val every = InstructionsKind.INSTRUCTIONS_EVERY_AGENT
        assertEquals("Write the instructions, or reset them to Dieter's default.", AddonRules.instructionsProblem(every, "  "))
        assertEquals(
            "{{labels.instructions_block}} must stay in the text exactly once.",
            AddonRules.instructionsProblem(every, "Hi {{project.instructions_block}}"),
        )
        assertEquals(
            "{{project.instructions_block}} must stay in the text exactly once.",
            AddonRules.instructionsProblem(every, "{{project.instructions_block}}{{project.instructions_block}}{{labels.instructions_block}}"),
        )
        assertNull(AddonRules.instructionsProblem(InstructionsKind.INSTRUCTIONS_CHATS, "No blocks needed here."))
        assertFailsWith<AddonFailure> { core.dispatch(Command(change_instructions = ChangeInstructions(every, "Hi"))) }
        assertEquals(emptyList(), bridge.sent("POST prompts"))
    }
}
