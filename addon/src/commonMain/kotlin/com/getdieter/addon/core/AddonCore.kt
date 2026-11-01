package com.getdieter.addon.core

import com.getdieter.addon.v1.AddRepository
import com.getdieter.addon.v1.AntigravitySlice
import com.getdieter.addon.v1.AnswerQuestion
import com.getdieter.addon.v1.Choice
import com.getdieter.addon.v1.ClaudeSignIn
import com.getdieter.addon.v1.ClaudeSlice
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.Enrollment
import com.getdieter.addon.v1.Failure
import com.getdieter.addon.v1.GatewaySlice
import com.getdieter.addon.v1.NameConversation
import com.getdieter.addon.v1.Option
import com.getdieter.addon.v1.OrderBoards
import com.getdieter.addon.v1.Question
import com.getdieter.addon.v1.QuestionItem
import com.getdieter.addon.v1.QuestionOption
import com.getdieter.addon.v1.QuestionsSlice
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.SettingChange
import com.getdieter.addon.v1.SettingsSlice
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.SshSlice
import com.getdieter.addon.v1.SshTest
import com.getdieter.addon.v1.Tone
import com.getdieter.addon.v1.Update
import com.getdieter.core.runtime.localUtcOffsetSeconds
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/** A command the add-on core refused or could not run; [failure] says why. */
class AddonFailure(val failure: Failure) : Exception(failure.message)

fun interface AddonSubscription {
    fun close()
}

/**
 * The add-on core: the add-on's own features as commands and slices (dieter/addon/v1/addon.proto), the
 * way Dieter's core serves Dieter's. It runs once in the web host for every page. The bridge owns the
 * add-on's state (the enrollment and Claude sign-in processes, agent questions, settings); this core
 * reads it, decides what the pages show and what a command does, and keeps what only it knows: which
 * attempt a page follows. Its state changes run on one confined dispatcher.
 */
class AddonCore(
    private val bridge: Bridge,
    parent: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    clock: () -> Instant = { Clock.System.now() },
    /** The UTC offset of a time zone at an instant; the default knows only the machine's zone. */
    offsetSeconds: (Instant, String) -> Int = { at, _ -> localUtcOffsetSeconds(at) },
    /** The time zone new Dreamfall boards start with: the machine's. */
    timezone: () -> String = { "UTC" },
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + dispatcher)
    private val dreamfall = Dreamfall(bridge, scope, clock, offsetSeconds, timezone)
    private val instructions = Instructions(bridge, scope)

    private val gateway = MutableStateFlow(GatewaySlice(loading = true))
    private val ssh = MutableStateFlow(SshSlice(loading = true))
    private val claude = MutableStateFlow(ClaudeSlice(loading = true))
    private val settings = MutableStateFlow(SettingsSlice(loading = true))
    private val questions = MutableStateFlow(QuestionsSlice(loading = true))
    private val antigravity = MutableStateFlow(AntigravitySlice())

    // What the bridge said last, and what only this core knows
    private var gatewayJson: JsonObject? = null
    private var gatewayError = ""
    private var enrollment = Enrollment()
    /** The page shows an enrollment this core started or saw running, until it is put away. */
    private var followingEnrollment = false
    private var enrollmentPoll: Job? = null
    private var sshJson: JsonObject? = null
    private var sshError = ""
    private var sshTest: SshTest? = null
    private var auth: JsonObject? = null
    private var authError = ""
    private var claudeSignIn = ClaudeSignIn()
    private var settingsJson: JsonObject? = null
    private var settingsError = ""
    /** Antigravity as the bridge's stream last reported it; the settings say which groups the summary leaves out. */
    private var antigravityReported = AntigravitySlice()
    private var pending: List<Question> = emptyList()
    private var questionsLoaded = false
    /** Answered here; the bridge's stream drops them a moment later. */
    private val answered = mutableSetOf<String>()
    private val loadedAt = mutableMapOf<Slice, TimeSource.Monotonic.ValueTimeMark>()
    private val loading = mutableMapOf<Slice, Job>()

    /** Follows the bridge's live state (agent questions, the Claude login, Dreamfall). */
    fun start() {
        scope.launch {
            bridge.events(STREAMED).collect { state ->
                dreamfall.ingest(state)
                (state["claude_auth"] as? JsonObject)?.let {
                    auth = it
                    authError = ""
                    publishClaude()
                }
                antigravityReported =
                    AntigravitySlice(
                        configured = state.objects("backends").any { it.str("id") == "antigravity" },
                        groups =
                            AddonRules.antigravityGroups(
                                state.objects("quota").firstOrNull { it.str("provider") == "antigravity" }.objects("windows")
                            ),
                    )
                publishAntigravity()
                pending = state.objects("questions").mapNotNull(::parseQuestion)
                answered.retainAll(pending.map { it.id }.toSet())
                questionsLoaded = true
                publishQuestions()
            }
        }
    }

    fun stop() = scope.coroutineContext[Job]?.cancel()

    /**
     * Calls [receive] with [slice] now and after every change, on the core's dispatcher; for questions
     * [scope] is a conversation's ID, or empty for all. Starts reading what the bridge does not push
     * when it is older than [STALE].
     */
    fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): AddonSubscription {
        val updates: Flow<Update> =
            when (slice) {
                Slice.SLICE_GATEWAY -> gateway.map { Update(gateway = it) }
                Slice.SLICE_SSH -> ssh.map { Update(ssh = it) }
                Slice.SLICE_CLAUDE -> claude.map { Update(claude = it) }
                Slice.SLICE_SETTINGS -> settings.map { Update(settings = it) }
                Slice.SLICE_ANTIGRAVITY -> antigravity.map { Update(antigravity = it) }
                Slice.SLICE_QUESTIONS ->
                    questions.map { all ->
                        Update(questions = if (scope.isEmpty()) all else all.copy(questions = all.questions.filter { it.card_id == scope }))
                    }
                else -> dreamfall.updates(slice, scope) ?: instructions.updates(slice) ?: invalid("The add-on has no such page.")
            }
        val job =
            this.scope.launch {
                refreshIfStale(slice)
                var sequence = 0L
                updates.distinctUntilChanged().collect { receive(it.copy(slice = slice, scope = scope, sequence = ++sequence)) }
            }
        return AddonSubscription { job.cancel() }
    }

    /** Runs [command] for the Home Assistant [user] behind the page. Throws [AddonFailure]. */
    suspend fun dispatch(command: Command, user: String = ""): Result =
        withContext(dispatcher) {
            try {
                execute(command, user)
            } catch (failure: AddonFailure) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw AddonFailure(failureOf(error))
            }
        }

    private suspend fun execute(command: Command, user: String): Result {
        command.enroll_gateway?.let { return enroll(it.gateway_url) }
        command.close_enrollment?.let { return closeEnrollment() }
        command.unenroll_gateway?.let { return unenroll() }
        command.test_ssh?.let { return testSsh(it.target) }
        command.begin_claude_sign_in?.let { return beginClaudeSignIn() }
        command.submit_claude_code?.let { return submitClaudeCode(it.code) }
        command.close_claude_sign_in?.let { return closeClaudeSignIn() }
        command.sign_out_of_claude?.let { return signOutOfClaude() }
        command.change_setting?.let { return changeSetting(it) }
        command.name_conversation?.let { return nameConversation(it, user) }
        command.order_boards?.let { return orderBoards(it, user) }
        command.answer_question?.let { return answer(it, user) }
        command.add_repository?.let { return addRepository(it) }
        dreamfall.execute(command, user)?.let { return it }
        instructions.execute(command)?.let { return it }
        command.refresh?.let {
            for (slice in listOf(Slice.SLICE_GATEWAY, Slice.SLICE_SSH, Slice.SLICE_SETTINGS, Slice.SLICE_CLAUDE)) load(slice)
            instructions.reload()
            return Result()
        }
        invalid("The add-on does not know this command.")
    }

    // ---- reading what the bridge does not push

    private fun refreshIfStale(slice: Slice) {
        val at = loadedAt[slice]
        val needed =
            when (slice) {
                Slice.SLICE_CLAUDE -> auth == null
                Slice.SLICE_GATEWAY, Slice.SLICE_SSH, Slice.SLICE_SETTINGS -> at == null || at.elapsedNow() > STALE
                // the groups come with the stream; which of them the summary leaves out is a setting
                Slice.SLICE_ANTIGRAVITY -> settingsJson == null && loadedAt[Slice.SLICE_SETTINGS] == null
                else -> false
            }
        if (needed && loading[slice]?.isActive != true) loading[slice] = scope.launch { load(slice) }
    }

    private suspend fun load(slice: Slice) {
        when (slice) {
            Slice.SLICE_GATEWAY -> {
                val json = readOrNull("gateway") { gatewayError = it } ?: return publishGateway()
                gatewayJson = json
                gatewayError = ""
                follow(json["enroll"] as? JsonObject)
                publishGateway()
            }
            Slice.SLICE_SSH -> {
                sshJson = readOrNull("ssh") { sshError = it } ?: return publishSsh()
                sshError = ""
                publishSsh()
            }
            Slice.SLICE_SETTINGS -> {
                settingsJson = readOrNull("settings") { settingsError = it } ?: return publishSettings()
                settingsError = ""
                publishSettings()
            }
            Slice.SLICE_ANTIGRAVITY -> load(Slice.SLICE_SETTINGS)
            Slice.SLICE_CLAUDE -> {
                val status = readOrNull("claude/login") { authError = it } ?: return publishClaude()
                (status["auth"] as? JsonObject)?.let { auth = it }
                authError = ""
                publishClaude()
            }
            else -> {}
        }
        loadedAt[slice] = TimeSource.Monotonic.markNow()
    }

    private suspend fun readOrNull(path: String, failed: (String) -> Unit): JsonObject? =
        try {
            bridge.read(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(failureOf(error).message)
            null
        }

    // ---- gateway enrollment

    private suspend fun enroll(requested: String): Result {
        if (enrollment.phase.running) return Result()
        val fixed = gatewayJson.str("option_url")
        val url = fixed.ifEmpty { requested }.trim().trimEnd('/')
        AddonRules.gatewayUrlProblem(url)?.let(::invalid)
        followingEnrollment = true
        enrollment = Enrollment(Enrollment.Phase.PHASE_STARTING)
        publishGateway()
        val status =
            try {
                bridge.write("gateway/enroll", buildJsonObject { put("url", url) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enrollment = Enrollment(Enrollment.Phase.PHASE_FAILED, message = failureOf(error).message)
                publishGateway()
                return Result()
            }
        follow(status)
        publishGateway()
        return Result()
    }

    /** Takes the bridge's enrollment process ([status]: running, url, code, done) into the slice. */
    private fun follow(status: JsonObject?) {
        status ?: return
        val done = status["done"] as? JsonObject
        enrollment =
            when {
                status.bool("running") -> {
                    followingEnrollment = true
                    val url = status.str("url")
                    if (url.isEmpty()) Enrollment(Enrollment.Phase.PHASE_STARTING)
                    else Enrollment(Enrollment.Phase.PHASE_WAITING, sign_in_url = url, code = status.str("code"))
                }
                !followingEnrollment -> Enrollment()
                done == null && enrollment.phase == Enrollment.Phase.PHASE_STARTING ->
                    Enrollment(Enrollment.Phase.PHASE_FAILED, message = "The daemon did not start the enrollment.")
                done == null -> enrollment
                done.bool("ok") ->
                    Enrollment(
                        Enrollment.Phase.PHASE_DONE,
                        message = "Enrolled as ${done.str("daemon_name").ifEmpty { "a machine" }}. Approve its code under Devices.",
                    )
                else -> Enrollment(Enrollment.Phase.PHASE_FAILED, message = done.str("error").ifEmpty { "The enrollment failed." })
            }
        if (enrollment.phase.running && enrollmentPoll?.isActive != true) enrollmentPoll = scope.launch { pollEnrollment() }
    }

    /** While the daemon waits for the authorization: the bridge's state every [pollInterval]. */
    private suspend fun pollEnrollment() {
        val until = TimeSource.Monotonic.markNow() + ENROLL_LIMIT
        while (enrollment.phase.running && until.hasNotPassedNow()) {
            delay(pollInterval)
            load(Slice.SLICE_GATEWAY)
        }
    }

    private suspend fun closeEnrollment(): Result {
        val running = enrollment.phase.running
        enrollmentPoll?.cancel()
        followingEnrollment = false
        enrollment = Enrollment()
        publishGateway()
        if (running) runCatching { bridge.write("gateway/enroll/cancel") }
        return Result()
    }

    private suspend fun unenroll(): Result {
        bridge.write("gateway/unenroll")
        followingEnrollment = false
        enrollment = Enrollment()
        load(Slice.SLICE_GATEWAY)
        return Result("Enrollment dropped. Enroll the add-on again.")
    }

    private fun publishGateway() {
        val g = gatewayJson
        if (g == null) {
            gateway.value = GatewaySlice(loading = gatewayError.isEmpty(), error = gatewayError, enrollment = enrollment)
            return
        }
        val embedded = g.str("mode") == "embedded" || g.str("url").startsWith("http://127.0.0.1:")
        val enrolled = g.bool("enrolled")
        val state = g.str("state")
        val lastError = g.str("last_error")
        val trust = g.str("trust")
        gateway.value =
            GatewaySlice(
                error = gatewayError,
                enrolled = enrolled || embedded,
                title =
                    when {
                        embedded -> g.str("daemon_name").ifEmpty { g.str("name").ifEmpty { "Home Assistant" } } + " (embedded gateway)"
                        enrolled -> g.str("daemon_name").ifEmpty { "Enrolled" }
                        else -> ""
                    },
                detail =
                    when {
                        embedded ->
                            listOf(
                                "Standalone mode",
                                (state.ifEmpty { "connecting" }) + if (lastError.isEmpty()) "" else ": $lastError",
                                "Switch gateway_mode to external in the add-on options to use a remote gateway.",
                            ).joinToString(" · ")
                        !enrolled -> ""
                        else -> listOf(g.str("url"), state + if (lastError.isEmpty()) "" else ": $lastError").filter { it.isNotBlank() }.joinToString(" · ")
                    },
                tone = if (!enrolled) Tone.TONE_NEUTRAL else if (state == "connected") Tone.TONE_SUCCESS else Tone.TONE_WARNING,
                action =
                    when {
                        embedded -> if (enrolled) GatewaySlice.Action.ACTION_DEVICES else GatewaySlice.Action.ACTION_NONE
                        !enrolled -> GatewaySlice.Action.ACTION_NONE
                        trust == "REVOKED" || trust == "CONFLICT" -> GatewaySlice.Action.ACTION_REENROLL
                        else -> GatewaySlice.Action.ACTION_DEVICES
                    },
                machine_name = g.str("name"),
                gateway_url = g.str("option_url"),
                gateway_url_locked = embedded || g.str("option_url").isNotEmpty(),
                enrollment = enrollment,
            )
    }

    // ---- the add-on's SSH key

    private suspend fun testSsh(target: String): Result {
        AddonRules.sshTargetProblem(target)?.let(::invalid)
        if (sshTest?.running == true) return Result()
        val host = target.trim()
        sshTest = SshTest(target = host, running = true)
        publishSsh()
        sshTest =
            try {
                val answer = bridge.request("POST", "ssh/test", buildJsonObject { put("target", host) }).json
                if (answer.bool("ok")) SshTest(host, ok = true, message = "Connected: ${answer.str("output")}")
                else SshTest(host, message = "Failed: ${answer.str("error").ifEmpty { "the add-on gave no reason" }}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SshTest(host, message = "Failed: ${failureOf(error).message}")
            }
        publishSsh()
        return Result()
    }

    private fun publishSsh() {
        val k = sshJson
        ssh.value =
            SshSlice(
                loading = k == null && sshError.isEmpty(),
                error = sshError,
                public_key = k.str("public_key"),
                fingerprint = k.str("fingerprint"),
                test = sshTest,
            )
    }

    // ---- the Claude Code login of the add-on's harness

    private suspend fun beginClaudeSignIn(): Result {
        if (claudeSignIn.phase.running) return Result()
        claudeSignIn = ClaudeSignIn(ClaudeSignIn.Phase.PHASE_STARTING)
        publishClaude()
        claudeSignIn =
            try {
                val status = bridge.write("claude/login")
                (status["auth"] as? JsonObject)?.let { auth = it }
                val url = status.str("url")
                if (url.isNotEmpty()) ClaudeSignIn(ClaudeSignIn.Phase.PHASE_CODE, sign_in_url = url)
                else
                    ClaudeSignIn(
                        ClaudeSignIn.Phase.PHASE_FAILED,
                        message = (status["done"] as? JsonObject).str("error").ifEmpty { "Claude Code did not print a sign-in link." },
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ClaudeSignIn(ClaudeSignIn.Phase.PHASE_FAILED, message = failureOf(error).message)
            }
        publishClaude()
        return Result()
    }

    private suspend fun submitClaudeCode(code: String): Result {
        if (claudeSignIn.phase != ClaudeSignIn.Phase.PHASE_CODE) invalid("Start the sign-in again.")
        if (code.isBlank()) invalid("Paste the code from claude.com.")
        val url = claudeSignIn.sign_in_url
        claudeSignIn = ClaudeSignIn(ClaudeSignIn.Phase.PHASE_SUBMITTING, sign_in_url = url)
        publishClaude()
        claudeSignIn =
            try {
                val answer = bridge.request("POST", "claude/login/code", buildJsonObject { put("code", code.trim()) }).json
                (answer["auth"] as? JsonObject)?.let { auth = it }
                if (answer.bool("ok"))
                    ClaudeSignIn(ClaudeSignIn.Phase.PHASE_DONE, message = "Signed in to Claude. The agent restarts with the new login.")
                else ClaudeSignIn(ClaudeSignIn.Phase.PHASE_FAILED, message = answer.str("error").ifEmpty { "The sign-in failed." })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ClaudeSignIn(ClaudeSignIn.Phase.PHASE_FAILED, message = failureOf(error).message)
            }
        publishClaude()
        return Result()
    }

    private suspend fun closeClaudeSignIn(): Result {
        val stop = claudeSignIn.phase != ClaudeSignIn.Phase.PHASE_NONE && claudeSignIn.phase != ClaudeSignIn.Phase.PHASE_DONE
        claudeSignIn = ClaudeSignIn()
        publishClaude()
        if (stop) runCatching { bridge.write("claude/login/cancel") }
        return Result()
    }

    private suspend fun signOutOfClaude(): Result {
        val answer = bridge.write("claude/logout")
        (answer["auth"] as? JsonObject)?.let { auth = it }
        publishClaude()
        return Result("Signed out of Claude.")
    }

    private fun publishClaude() {
        val a = auth
        if (a == null) {
            claude.value = ClaudeSlice(loading = authError.isEmpty(), error = authError, sign_in = claudeSignIn)
            return
        }
        val method = a.str("method")
        val plan = a.str("plan")
        claude.value =
            ClaudeSlice(
                signed_in = a.bool("signed_in"),
                title =
                    when (method) {
                        "login" -> "Signed in" + if (plan.isNotEmpty()) " ($plan)" else ""
                        "token" -> "Setup-token from the add-on options"
                        else -> "Not signed in"
                    },
                detail =
                    when (method) {
                        "login" -> "Claude Code in this add-on uses your Claude account."
                        "token" ->
                            "Such tokens cannot read the quota, so nothing is shown for Claude Code. Sign in with your " +
                                "Claude account to see it."
                        else ->
                            "The Claude Code agent cannot run. Sign in with your Claude account (subscription) here, or " +
                                "put a setup-token into the add-on options."
                    },
                tone =
                    when (method) {
                        "login" -> Tone.TONE_SUCCESS
                        "token" -> Tone.TONE_NEUTRAL
                        else -> Tone.TONE_WARNING
                    },
                action = if (method == "login") ClaudeSlice.Action.ACTION_SIGN_OUT else ClaudeSlice.Action.ACTION_SIGN_IN,
                sign_in = claudeSignIn,
            )
    }

    // ---- settings

    /** A conversation created under its placeholder title on the add-on's machine: Ollama names it (Settings → Task titles). */
    private suspend fun nameConversation(name: NameConversation, user: String): Result {
        if (name.id.isBlank() || name.prompt.isBlank() || name.provisional.isBlank()) invalid("Name which conversation?")
        bridge.write(
            "titles",
            buildJsonObject {
                put("kind", if (name.chat) "chat" else "card")
                put("id", name.id)
                put("prompt", name.prompt)
                put("provisional", name.provisional)
            },
            user,
        )
        return Result()
    }

    /** The bridge keeps one board order for both pages and every device. */
    private suspend fun orderBoards(order: OrderBoards, user: String): Result {
        if (order.board_ids.isEmpty() || order.board_ids.any { it.isBlank() }) invalid("Which boards, in which order?")
        val saved = bridge.write("boards/order", buildJsonObject { putJsonArray("ids") { order.board_ids.forEach { add(JsonPrimitive(it)) } } }, user)
        settingsJson = settingsJson?.let { JsonObject(it + ("board_order" to (saved["board_order"] ?: return@let it))) }
        publishSettings()
        return Result()
    }

    private suspend fun changeSetting(change: SettingChange): Result {
        change.title_model?.let { model ->
            val known = settingsJson.objects("title_models").map { it.str("id") }
            if (known.isNotEmpty() && model !in known) invalid("The add-on has no such title model.")
            settingsJson = bridge.write("settings", buildJsonObject { put("title_model", model) })
            publishSettings()
            return Result()
        }
        change.worktree_retention_days?.let { days ->
            if (days < 0) invalid("Keep work folders for 0 (always) or more days.")
            settingsJson = bridge.write("settings", buildJsonObject { put("worktree_retention_days", days) })
            publishSettings()
            return Result(
                if (days == 0) "Work folders are kept." else "Finished work folders are removed after ${retentionLabel(days)}."
            )
        }
        change.antigravity_inclusion?.let { inclusion ->
            if (inclusion.group.isBlank()) invalid("Which quota group?")
            settingsJson =
                bridge.write(
                    "settings",
                    buildJsonObject {
                        putJsonObject("antigravity_inclusion") {
                            put("group", inclusion.group)
                            put("included", inclusion.included)
                        }
                    },
                )
            publishSettings()
            return Result()
        }
        change.palette?.let { palette ->
            if (palette.isBlank()) invalid("Which palette?")
            settingsJson = bridge.write("settings", buildJsonObject { put("palette", palette) })
            publishSettings()
            return Result()
        }
        invalid("Choose a setting.")
    }

    private fun publishAntigravity() {
        val excluded = settingsJson.strings("antigravity_excluded").toSet()
        antigravity.value =
            antigravityReported.copy(groups = antigravityReported.groups.map { it.copy(excluded = it.name in excluded) })
    }

    private fun publishSettings() {
        publishAntigravity()                       // which groups the summary leaves out is a setting
        val d = settingsJson
        if (d == null) {
            settings.value = SettingsSlice(loading = settingsError.isEmpty(), error = settingsError)
            return
        }
        val models = d.objects("title_models")
        val current = d.str("title_model")
        val model = models.firstOrNull { it.str("id") == current }
        val days = d.int("worktree_retention_days") ?: 0
        settings.value =
            SettingsSlice(
                error = settingsError,
                title_model =
                    Choice(
                        selected = current,
                        selected_label = model?.str("name") ?: current,
                        options =
                            models.map {
                                Option(it.str("id"), it.str("name") + if (!it.bool("available")) " (unavailable)" else "")
                            },
                        warning = if (model != null && !model.bool("available")) model.str("detail") else "",
                    ),
                worktree_retention =
                    Choice(
                        selected = days.toString(),
                        selected_label = retentionLabel(days),
                        options = (RETENTION_DAYS + days).distinct().sorted().map { Option(it.toString(), retentionLabel(it)) },
                    ),
                titles_daemon_id = d.str("titles_daemon_id"),
                board_order = d.strings("board_order"),
                palette = d.str("palette"),
            )
    }

    // ---- agent questions

    private suspend fun answer(command: AnswerQuestion, user: String): Result {
        val question =
            pending.firstOrNull { it.id == command.question_id && it.id !in answered }
                ?: throw AddonFailure(Failure(Failure.Kind.KIND_FAILED, "The agent no longer waits for this answer."))
        val body =
            if (command.decline) buildJsonObject { put("action", "declined") }
            else {
                if (!AddonRules.canAnswer(question, command.answers))
                    invalid(if (question.allow_partial) "Answer at least one question." else "Answer every question.")
                val all = question.items.all { AddonRules.answers(it, command.answers[it.id]) }
                buildJsonObject {
                    put("action", if (all) "answered" else "partially-answered")
                    putJsonObject("answers") {
                        for (item in question.items) {
                            val given = command.answers[item.id]
                            if (!AddonRules.answers(item, given) || given == null) continue
                            putJsonObject(item.id) {
                                putJsonArray("optionIds") { given.option_ids.forEach { add(JsonPrimitive(it)) } }
                                if (item.free_form && given.freeform.isNotBlank()) put("freeform", given.freeform.trim())
                            }
                        }
                    }
                }
            }
        bridge.write("questions/${segment(question.id)}/answer", body, user)
        answered += question.id
        publishQuestions()
        return Result(if (command.decline) "Question declined." else "Answer sent.")
    }

    private fun publishQuestions() {
        questions.value = QuestionsSlice(loading = !questionsLoaded, questions = pending.filter { it.id !in answered })
    }

    // ---- repositories

    private suspend fun addRepository(command: AddRepository): Result {
        val url = command.git_url.trim()
        AddonRules.repositoryProblem(url, command.name, command.branch)?.let(::invalid)
        val body =
            if (url.isEmpty()) buildJsonObject { put("folder", command.name.trim()) }
            else
                buildJsonObject {
                    put("git_url", url)
                    put("name", command.name.trim())
                    put("branch", command.branch.trim())
                }
        val added = bridge.request("POST", "project", body, timeout = CLONE_LIMIT).let { response ->
            val error = response.json.str("error")
            if (response.status !in 200..299 || error.isNotEmpty()) throw BridgeRefused(error.ifEmpty { "The add-on answered ${response.status}." })
            response.json
        }
        val name = added.str("name").ifEmpty { command.name.trim().ifEmpty { AddonRules.repositoryName(url) } }
        return Result(if (added.bool("existed")) "$name was already registered." else "$name added.")
    }

    companion object {
        /** What the core follows of the bridge's state. */
        private val STREAMED =
            listOf("questions", "claude_auth", "dreamfall", "dreamfall_cards", "projects", "boards", "harnesses", "quota", "backends")
        /** Slices the bridge does not push are read again when a page opens them after this long. */
        val STALE: Duration = 15.seconds
        /** `dieter daemon enroll` waits for the authorization about as long as a device code lives. */
        val ENROLL_LIMIT: Duration = 30.minutes
        /** Cloning a repository can take a while. */
        val CLONE_LIMIT: Duration = 15.minutes
        val RETENTION_DAYS = listOf(0, 7, 14, 30, 60, 90, 180, 365)

        fun retentionLabel(days: Int): String =
            when (days) {
                0 -> "Never remove"
                1 -> "1 day"
                else -> "$days days"
            }

        private fun segment(id: String): String =
            id.filter { it.isLetterOrDigit() || it in "-_." }.ifEmpty { invalid("That question cannot be answered.") }
    }
}

private val Enrollment.Phase.running
    get() = this == Enrollment.Phase.PHASE_STARTING || this == Enrollment.Phase.PHASE_WAITING

private val ClaudeSignIn.Phase.running
    get() = this == ClaudeSignIn.Phase.PHASE_STARTING || this == ClaudeSignIn.Phase.PHASE_CODE || this == ClaudeSignIn.Phase.PHASE_SUBMITTING

internal fun invalid(message: String): Nothing = throw AddonFailure(Failure(Failure.Kind.KIND_INVALID, message))

private fun failureOf(error: Exception): Failure =
    when (error) {
        is AddonFailure -> error.failure
        is BridgeUnreachable -> Failure(Failure.Kind.KIND_UNREACHABLE, "The add-on did not answer: ${error.message}")
        else -> Failure(Failure.Kind.KIND_FAILED, error.message ?: "The add-on could not do that.")
    }

/** An agent's question as the bridge relays it (`session` is the conversation, `input` Claude Code's). */
internal fun parseQuestion(o: JsonObject): Question? {
    val id = o.str("id").ifEmpty { return null }
    val input = o["input"] as? JsonObject
    val items =
        input.objects("questions").mapIndexed { index, x ->
            val options =
                x.objects("options").mapIndexed { i, option ->
                    QuestionOption(option.str("id").ifEmpty { i.toString() }, option.str("label"), option.str("description"))
                }
            QuestionItem(
                id = x.str("id").ifEmpty { index.toString() },
                chip = x.str("header"),
                question = x.str("question"),
                options = options,
                multiple = x.bool("allowMultiple"),
                free_form = x.bool("allowFreeForm") || options.isEmpty(),
            )
        }
    return Question(
        id = id,
        card_id = o.str("session"),
        items = items,
        allow_partial = input.bool("allowPartialAnswers"),
        summary = items.firstOrNull()?.question.orEmpty(),
        created_at = o.str("created_at"),
    )
}
