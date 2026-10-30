package com.getdieter.addon.core

import com.getdieter.addon.v1.ChangeInstructions
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.InstructionsKind
import com.getdieter.addon.v1.InstructionsSlice
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Update
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's agent instructions (`dieter prompt`, through the bridge's `api/prompts`): Settings → Agent
 * instructions. Read when shown and older than [AddonCore.STALE]; a save answers with the new texts.
 */
internal class Instructions(private val bridge: Bridge, private val scope: CoroutineScope) {
    private val slice = MutableStateFlow(InstructionsSlice(loading = true))
    private var json: JsonObject? = null
    private var error = ""
    private var loadedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var loading: Job? = null

    fun updates(slice: Slice): Flow<Update>? {
        if (slice != Slice.SLICE_INSTRUCTIONS) return null
        val at = loadedAt
        if ((at == null || at.elapsedNow() > AddonCore.STALE) && loading?.isActive != true) loading = scope.launch { load() }
        return this.slice.map { Update(instructions = it) }
    }

    suspend fun execute(command: Command): Result? {
        val change = command.change_instructions ?: return null
        val key = KEYS[change.kind] ?: invalid("Choose which instructions to change.")
        if (!change.reset) AddonRules.instructionsProblem(change.kind, change.text)?.let(::invalid)
        val body =
            if (change.reset) buildJsonObject { put("reset", buildJsonArray { add(JsonPrimitive(key)) }) }
            else buildJsonObject { put(key, change.text) }
        json = bridge.write("prompts", body)
        error = ""
        loadedAt = TimeSource.Monotonic.markNow()
        publish()
        return Result(if (change.reset) "Reset to Dieter's default." else "Saved; applies from each conversation's next turn.")
    }

    /** Reads the texts again (the pages' retry). */
    suspend fun reload() = load()

    private suspend fun load() {
        try {
            json = bridge.read("prompts")
            error = ""
            loadedAt = TimeSource.Monotonic.markNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "The add-on could not read the instructions."
        }
        publish()
    }

    private fun publish() {
        val d = json
        if (d == null) {
            slice.value = InstructionsSlice(loading = error.isEmpty(), error = error)
            return
        }
        slice.value =
            InstructionsSlice(
                error = error,
                items =
                    KEYS.map { (kind, key) ->
                        val text = d.str(key)
                        InstructionsSlice.Instructions(kind, TITLES.getValue(kind), summary(text), text, HELP.getValue(kind))
                    },
                variables = d.strings("variables").map { "{{$it}}" },
                overrides =
                    d.objects("overrides").map {
                        val board = it.str("kind") == "board"
                        InstructionsSlice.Override(
                            name = it.str("name").ifEmpty { it.str("id") },
                            detail = if (!board) "Project" else it.str("project").ifEmpty { null }?.let { p -> "Board of $p" } ?: "Board",
                            board = board,
                        )
                    },
            )
    }

    companion object {
        /** The bridge's keys, in the order Dieter puts the parts together. */
        private val KEYS =
            linkedMapOf(
                InstructionsKind.INSTRUCTIONS_EVERY_AGENT to "template",
                InstructionsKind.INSTRUCTIONS_BOARD_CARDS to "board_skill",
                InstructionsKind.INSTRUCTIONS_CHATS to "chat_skill",
            )
        private val TITLES =
            mapOf(
                InstructionsKind.INSTRUCTIONS_EVERY_AGENT to "Every agent",
                InstructionsKind.INSTRUCTIONS_BOARD_CARDS to "Board cards",
                InstructionsKind.INSTRUCTIONS_CHATS to "Standalone chats",
            )
        private val HELP =
            mapOf(
                InstructionsKind.INSTRUCTIONS_EVERY_AGENT to
                    "Opens the instructions of every turn, for every agent (Claude, Codex, Antigravity) in every project. " +
                        "Add your own text, e.g. at the end; {{project.instructions_block}} and {{labels.instructions_block}} " +
                        "must stay in it exactly once.",
                InstructionsKind.INSTRUCTIONS_BOARD_CARDS to
                    "Follows the part above in the instructions of a card on a board: how the agent uses the Dieter CLI, " +
                        "lanes and labels.",
                InstructionsKind.INSTRUCTIONS_CHATS to
                    "Follows the part above in the instructions of a standalone chat (no board, lanes or labels).",
            )
        private val VARIABLE = Regex("""\{\{[\w.]+\}\}""")

        /** The last line that says something besides variables, as the classic page shows it. */
        fun summary(text: String): String =
            text.lines().map { it.trim() }.lastOrNull { it.replace(VARIABLE, "").isNotBlank() }.orEmpty()
    }
}
