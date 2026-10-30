package com.getdieter.addon.core

import com.getdieter.addon.v1.Answer
import com.getdieter.addon.v1.DreamfallSettings
import com.getdieter.addon.v1.InstructionsKind
import com.getdieter.addon.v1.Question
import com.getdieter.addon.v1.QuestionItem

/**
 * The add-on's rules that a form asks while it is filled in, the way Dieter's UI asks
 * `Labels.validate`: whether an input can work and what a tap changes. [AddonCore] applies the same
 * rules to every command, and the bridge checks again.
 */
object AddonRules {
    private val GATEWAY = Regex("""^https://[A-Za-z0-9.-]+(:\d{2,5})?$""")
    private val SSH_TARGET = Regex("""^(?:[A-Za-z0-9._-]{1,64}@)?[A-Za-z0-9][A-Za-z0-9.:-]{0,253}(?::\d{1,5})?$""")
    private val GIT_URL =
        Regex("""^(https://[\w.\-]+(:\d+)?/[\w.\-/~]+|git@[\w.\-]+:[\w.\-/~]+|ssh://[\w.\-@]+(:\d+)?/[\w.\-/~]+)$""")
    private val NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")
    private val BRANCH = Regex("""^[\w./-]{1,100}$""")
    private val TIME = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
    private val TIMEZONE = Regex("""^[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}$|^UTC$""")

    fun isTimezone(zone: String): Boolean = TIMEZONE.matches(zone)

    /** The blocks the instructions for every agent must keep exactly once (Dieter refuses the text otherwise). */
    val INSTRUCTION_BLOCKS = listOf("{{project.instructions_block}}", "{{labels.instructions_block}}")

    /** Why [text] cannot be saved as the [kind] instructions; null when it can. Dieter checks the variables. */
    fun instructionsProblem(kind: InstructionsKind, text: String): String? {
        if (text.isBlank()) return "Write the instructions, or reset them to Dieter's default."
        if (kind == InstructionsKind.INSTRUCTIONS_EVERY_AGENT)
            INSTRUCTION_BLOCKS.firstOrNull { text.split(it).size != 2 }?.let { return "$it must stay in the text exactly once." }
        return null
    }
    /** The longest navigation logic and implementation instructions the bridge keeps. */
    const val MAX_LOGIC = 4000

    /** Why [url] is no gateway address to enroll with, or null. */
    fun gatewayUrlProblem(url: String): String? =
        when {
            url.isBlank() -> "Type the gateway's address."
            !GATEWAY.matches(url.trim().trimEnd('/')) ->
                "The gateway must be an https origin such as https://gateway.example.net:4443."
            else -> null
        }

    /** Why [target] is no `user@host[:port]` to test the SSH key with, or null. */
    fun sshTargetProblem(target: String): String? =
        when {
            target.isBlank() -> "Type user@host."
            !SSH_TARGET.matches(target.trim()) -> "Use user@host or host, optionally with :port."
            else -> null
        }

    /** The project name a clone of [gitUrl] gets when none is given. */
    fun repositoryName(gitUrl: String): String =
        gitUrl.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")

    /** Why a repository cannot be added: a clone of [gitUrl], or with an empty one a new repository [name]. */
    fun repositoryProblem(gitUrl: String, name: String, branch: String): String? {
        val url = gitUrl.trim()
        val folder = name.trim().ifEmpty { if (url.isEmpty()) "" else repositoryName(url) }
        return when {
            url.isNotEmpty() && !GIT_URL.matches(url) -> "Use an https://, ssh:// or git@host:path address."
            folder.isEmpty() -> "Name the repository."
            !NAME.matches(folder) -> "Names use letters, digits, . _ - (up to 64)."
            url.isNotEmpty() && branch.isNotBlank() && !BRANCH.matches(branch.trim()) -> "That is no branch name."
            else -> null
        }
    }

    /** Whether [answer] answers [item]: an option chosen, or own text where the item takes it. */
    fun answers(item: QuestionItem, answer: Answer?): Boolean =
        answer != null && (answer.option_ids.isNotEmpty() || (item.free_form && answer.freeform.isNotBlank()))

    /** Whether [answers] can be sent: every item answered, or one when the agent takes partial answers. */
    fun canAnswer(question: Question, answers: Map<String, Answer>): Boolean {
        val answered = question.items.count { answers(it, answers[it.id]) }
        return answered > 0 && (question.allow_partial || answered == question.items.size)
    }

    /** [answer] after a tap on [optionId]: it replaces the choice, or with several allowed toggles it. */
    fun choose(item: QuestionItem, answer: Answer?, optionId: String): Answer {
        val current = answer ?: Answer()
        val ids =
            when {
                !item.multiple -> listOf(optionId)
                optionId in current.option_ids -> current.option_ids - optionId
                else -> current.option_ids + optionId
            }
        return current.copy(option_ids = ids)
    }

    /** Why Dreamfall [settings] cannot be saved; the bridge checks the same. */
    fun dreamfallProblem(settings: DreamfallSettings): String? {
        val file = settings.categories_file.trim()
        return when {
            settings.name.isBlank() -> "Name the Dreamfall board."
            settings.name.trim().length > 80 -> "Names have up to 80 characters."
            settings.categories.isBlank() && file.isEmpty() -> "Add categories, one per line, or a categories file of the project."
            file.startsWith("/") || ".." in file.split("/") -> "The categories file is a path inside the project."
            settings.per_category !in 1..10 -> "Ideas per category: 1 to 10."
            settings.navigation_logic.isBlank() -> "Describe how the Navigation agent decides which ideas go on."
            settings.navigation_logic.trim().length > MAX_LOGIC -> "The navigation logic has up to $MAX_LOGIC characters."
            settings.implementation_instructions.trim().length > MAX_LOGIC ->
                "The implementation instructions have up to $MAX_LOGIC characters."
            !TIME.matches(settings.enumeration_time.trim()) || !TIME.matches(settings.navigation_time.trim()) -> "Times are HH:MM, e.g. 02:00."
            settings.days.isEmpty() -> "Pick at least one weekday."
            settings.days.any { it !in 0..6 } -> "Weekdays are 0 (Sunday) to 6 (Saturday)."
            settings.timezone.isNotBlank() && !TIMEZONE.matches(settings.timezone.trim()) -> "Use a time zone name such as Europe/Berlin."
            else -> null
        }
    }
}
