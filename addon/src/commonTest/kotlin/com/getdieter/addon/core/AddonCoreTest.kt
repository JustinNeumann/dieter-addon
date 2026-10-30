package com.getdieter.addon.core

import com.getdieter.addon.v1.AddRepository
import com.getdieter.addon.v1.Answer
import com.getdieter.addon.v1.AnswerQuestion
import com.getdieter.addon.v1.ClaudeSignIn
import com.getdieter.addon.v1.ClaudeSlice
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.EnrollGateway
import com.getdieter.addon.v1.Enrollment
import com.getdieter.addon.v1.Failure
import com.getdieter.addon.v1.GatewaySlice
import com.getdieter.addon.v1.NameConversation
import com.getdieter.addon.v1.OrderBoards
import com.getdieter.addon.v1.SettingChange
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Step
import com.getdieter.addon.v1.SubmitClaudeCode
import com.getdieter.addon.v1.TestSsh
import com.getdieter.addon.v1.AntigravityInclusion
import com.getdieter.addon.v1.AntigravitySlice
import com.getdieter.addon.v1.AntigravityWindow
import com.getdieter.addon.v1.Tone
import com.getdieter.addon.v1.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** The bridge's answers by "METHOD path"; every request is recorded. */
internal class FakeBridge : Bridge {
    val requests = mutableListOf<Pair<String, JsonObject?>>()
    val routes = mutableMapOf<String, (JsonObject?) -> BridgeResponse>()
    val state = MutableSharedFlow<JsonObject>(replay = 1)

    fun answer(route: String, text: String, status: Int = 200) {
        routes[route] = { BridgeResponse(status, json(text)) }
    }

    override suspend fun request(method: String, path: String, body: JsonObject?, user: String, timeout: Duration): BridgeResponse {
        requests += "$method $path" to body
        return routes["$method $path"]?.invoke(body) ?: throw BridgeUnreachable("connection refused")
    }

    override fun events(fields: List<String>): Flow<JsonObject> = state

    fun sent(route: String): List<JsonObject?> = requests.filter { it.first == route }.map { it.second }
}

class AddonCoreTest {
    private val bridge = FakeBridge()

    private fun TestScope.core(): AddonCore =
        AddonCore(bridge, backgroundScope, pollInterval = 2.seconds, dispatcher = StandardTestDispatcher(testScheduler)).also { it.start() }

    private fun TestScope.watch(core: AddonCore, slice: Slice, scope: String = ""): MutableList<Update> {
        val seen = mutableListOf<Update>()
        core.observe(slice, scope) { seen += it }
        runCurrent()
        return seen
    }

    private suspend fun AddonCore.fails(command: Command): Failure = assertFailsWith<AddonFailure> { dispatch(command) }.failure

    @Test
    fun enrollmentRunsFromStartToTheApprovedMachine() = runTest {
        bridge.answer("GET gateway", """{"enrolled":false,"name":"home-assistant","option_url":"","enroll":{"running":false}}""")
        val core = core()
        val seen = watch(core, Slice.SLICE_GATEWAY)
        assertEquals(1L, seen.first().sequence)
        assertEquals("home-assistant", seen.last().gateway!!.machine_name)
        assertFalse(seen.last().gateway!!.gateway_url_locked)

        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(enroll_gateway = EnrollGateway("http://gw.example"))).kind)
        assertTrue(bridge.sent("POST gateway/enroll").isEmpty())

        bridge.answer("POST gateway/enroll", """{"running":true,"url":"https://gw.example/device","code":"AB12-CD34"}""")
        bridge.answer("GET gateway", """{"enrolled":false,"name":"home-assistant","enroll":{"running":true,"url":"https://gw.example/device","code":"AB12-CD34"}}""")
        core.dispatch(Command(enroll_gateway = EnrollGateway("https://gw.example:4443/")))
        assertEquals("https://gw.example:4443", bridge.sent("POST gateway/enroll").single()!!.str("url"))
        advanceTimeBy(1.seconds)
        val waiting = seen.last().gateway!!.enrollment!!
        assertEquals(Enrollment.Phase.PHASE_WAITING, waiting.phase)
        assertEquals("AB12-CD34", waiting.code)

        bridge.answer(
            "GET gateway",
            """{"enrolled":true,"daemon_name":"home-assistant","url":"https://gw.example:4443","state":"connected","trust":"TRUSTED",
               "name":"home-assistant","enroll":{"running":false,"done":{"ok":true,"daemon_name":"home-assistant"}}}""",
        )
        advanceTimeBy(3.seconds)
        val done = seen.last().gateway!!
        assertEquals(Enrollment.Phase.PHASE_DONE, done.enrollment!!.phase)
        assertEquals("Enrolled as home-assistant. Approve its code under Devices.", done.enrollment!!.message)
        assertTrue(done.enrolled)
        assertEquals("https://gw.example:4443 · connected", done.detail)
        assertEquals(Tone.TONE_SUCCESS, done.tone)
        assertEquals(GatewaySlice.Action.ACTION_DEVICES, done.action)

        core.dispatch(Command(close_enrollment = Step()))
        runCurrent()
        assertEquals(Enrollment.Phase.PHASE_NONE, seen.last().gateway!!.enrollment!!.phase)
        assertTrue(bridge.sent("POST gateway/enroll/cancel").isEmpty(), "a finished enrollment is only put away")
    }

    @Test
    fun aFailedStartShowsWhyAndClosingStopsTheProcess() = runTest {
        bridge.answer("GET gateway", """{"enrolled":false,"name":"ha","option_url":"https://fixed.example","enroll":{"running":false}}""")
        bridge.answer("POST gateway/enroll", """{"error":"could not begin enrollment: connection refused"}""", status = 400)
        bridge.answer("POST gateway/enroll/cancel", """{"ok":true}""")
        val core = core()
        val seen = watch(core, Slice.SLICE_GATEWAY)
        assertTrue(seen.last().gateway!!.gateway_url_locked)
        core.dispatch(Command(enroll_gateway = EnrollGateway("https://typed.example")))
        assertEquals("https://fixed.example", bridge.sent("POST gateway/enroll").single()!!.str("url"), "the add-on's option wins")
        runCurrent()
        val failed = seen.last().gateway!!.enrollment!!
        assertEquals(Enrollment.Phase.PHASE_FAILED, failed.phase)
        assertEquals("could not begin enrollment: connection refused", failed.message)
        core.dispatch(Command(close_enrollment = Step()))
        assertTrue(bridge.sent("POST gateway/enroll/cancel").isEmpty())
    }

    @Test
    fun aMachineTheAccountDroppedOffersEnrollingAgain() = runTest {
        bridge.answer(
            "GET gateway",
            """{"enrolled":true,"daemon_name":"ha","url":"https://gw","state":"disconnected","last_error":"revoked","trust":"REVOKED","enroll":{}}""",
        )
        bridge.answer("POST gateway/unenroll", """{"ok":true}""")
        val core = core()
        val row = watch(core, Slice.SLICE_GATEWAY).last().gateway!!
        assertEquals(GatewaySlice.Action.ACTION_REENROLL, row.action)
        assertEquals(Tone.TONE_WARNING, row.tone)
        assertEquals("https://gw · disconnected: revoked", row.detail)
        assertEquals("Enrollment dropped. Enroll the add-on again.", core.dispatch(Command(unenroll_gateway = Step())).message)
    }

    @Test
    fun theSshTestReportsTheOutcomeOnThePage() = runTest {
        bridge.answer("GET ssh", """{"public_key":"ssh-ed25519 AAAA dieter@ha","fingerprint":"SHA256:x"}""")
        bridge.answer("POST ssh/test", """{"ok":false,"error":"Connection refused"}""")
        val core = core()
        val seen = watch(core, Slice.SLICE_SSH)
        assertEquals("ssh-ed25519 AAAA dieter@ha", seen.last().ssh!!.public_key)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(test_ssh = TestSsh("not a host"))).kind)
        core.dispatch(Command(test_ssh = TestSsh(" nobody@127.0.0.1:1 ")))
        runCurrent()
        assertEquals("Failed: Connection refused", seen.last().ssh!!.test!!.message)
        assertEquals("nobody@127.0.0.1:1", bridge.sent("POST ssh/test").single()!!.str("target"))
        bridge.answer("POST ssh/test", """{"ok":true,"output":"pi"}""")
        core.dispatch(Command(test_ssh = TestSsh("pi@pi")))
        runCurrent()
        assertTrue(seen.last().ssh!!.test!!.ok)
        assertEquals("Connected: pi", seen.last().ssh!!.test!!.message)
    }

    @Test
    fun claudeSignInGoesFromLinkToCodeAndAWrongCodeEndsTheAttempt() = runTest {
        bridge.state.emit(json("""{"questions":[],"claude_auth":{"signed_in":false,"method":null}}"""))
        bridge.answer("POST claude/login", """{"running":true,"url":"https://claude.com/oauth?x=1","auth":{"signed_in":false}}""")
        bridge.answer("POST claude/login/code", """{"ok":false,"error":"Invalid code","auth":{"signed_in":false}}""")
        bridge.answer("POST claude/login/cancel", """{"ok":true}""")
        val core = core()
        val seen = watch(core, Slice.SLICE_CLAUDE)
        assertEquals("Not signed in", seen.last().claude!!.title)
        assertEquals(ClaudeSlice.Action.ACTION_SIGN_IN, seen.last().claude!!.action)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(submit_claude_code = SubmitClaudeCode("x"))).kind)
        core.dispatch(Command(begin_claude_sign_in = Step()))
        runCurrent()
        assertEquals(ClaudeSignIn.Phase.PHASE_CODE, seen.last().claude!!.sign_in!!.phase)
        assertEquals("https://claude.com/oauth?x=1", seen.last().claude!!.sign_in!!.sign_in_url)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(submit_claude_code = SubmitClaudeCode(" "))).kind)
        core.dispatch(Command(submit_claude_code = SubmitClaudeCode(" wrong#state ")))
        runCurrent()
        assertEquals("wrong#state", bridge.sent("POST claude/login/code").single()!!.str("code"))
        assertEquals(ClaudeSignIn.Phase.PHASE_FAILED, seen.last().claude!!.sign_in!!.phase)
        assertEquals("Invalid code", seen.last().claude!!.sign_in!!.message)
        core.dispatch(Command(close_claude_sign_in = Step()))
        runCurrent()
        assertEquals(1, bridge.sent("POST claude/login/cancel").size)
        assertEquals(ClaudeSignIn.Phase.PHASE_NONE, seen.last().claude!!.sign_in!!.phase)
        bridge.state.emit(json("""{"questions":[],"claude_auth":{"signed_in":true,"method":"login","plan":"max"}}"""))
        runCurrent()
        assertEquals("Signed in (max)", seen.last().claude!!.title)
        assertEquals(ClaudeSlice.Action.ACTION_SIGN_OUT, seen.last().claude!!.action)
    }

    @Test
    fun settingsOfferTheRetentionChoicesAndConfirmAChange() = runTest {
        val view = """{"title_model":"ollama","worktree_retention_days":%d,"title_models":[
            {"id":"openai","name":"OpenAI","available":true,"detail":""},
            {"id":"ollama","name":"Ollama (HA)","available":false,"detail":"No AI Task entity"}]}"""
        bridge.answer("GET settings", view.format(45))
        bridge.routes["POST settings"] = { body -> BridgeResponse(200, json(view.format(body.int("worktree_retention_days") ?: 45))) }
        val core = core()
        val seen = watch(core, Slice.SLICE_SETTINGS)
        val s = seen.last().settings!!
        assertEquals("No AI Task entity", s.title_model!!.warning)
        assertEquals("Ollama (HA) (unavailable)", s.title_model!!.options[1].label)
        assertEquals(listOf("0", "7", "14", "30", "45", "60", "90", "180", "365"), s.worktree_retention!!.options.map { it.id })
        assertEquals("Never remove", s.worktree_retention!!.options.first().label)
        assertEquals(
            "Finished work folders are removed after 30 days.",
            core.dispatch(Command(change_setting = SettingChange(worktree_retention_days = 30))).message,
        )
        runCurrent()
        assertEquals("30 days", seen.last().settings!!.worktree_retention!!.selected_label)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(change_setting = SettingChange(title_model = "gpt"))).kind)
    }

    @Test
    fun whileOllamaNamesTasksTheCoreSaysWhereAndHandsThemToTheBridge() = runTest {
        bridge.answer(
            "GET settings",
            """{"title_model":"ollama","worktree_retention_days":14,"titles_daemon_id":"d-addon","title_models":[
                {"id":"ollama","name":"Ollama (HA)","available":true,"detail":"ai_task.ollama"}]}""",
        )
        bridge.answer("POST titles", """{"ok":true}""")
        val core = core()
        assertEquals("d-addon", watch(core, Slice.SLICE_SETTINGS).last().settings!!.titles_daemon_id)
        val named = core.dispatch(Command(name_conversation = NameConversation("c-1", false, "Fix the heating\nplease", "Fix the heating")))
        assertEquals("", named.message, "nothing to tell: the title changes a moment later")
        assertEquals(
            """{"kind":"card","id":"c-1","prompt":"Fix the heating\nplease","provisional":"Fix the heating"}""",
            bridge.sent("POST titles").single().toString(),
        )
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(name_conversation = NameConversation("", true, "Hi", "Hi"))).kind)
        assertEquals(1, bridge.sent("POST titles").size)
    }

    @Test
    fun antigravitysQuotaGroupsComeFromTheBridge() = runTest {
        val core = core()
        val seen = watch(core, Slice.SLICE_ANTIGRAVITY)
        bridge.state.emit(json("""{"questions":[],"backends":[{"id":"openai_codex"}],"quota":[]}"""))
        runCurrent()
        assertEquals(AntigravitySlice(), seen.last().antigravity)
        bridge.state.emit(
            json(
                """{"questions":[],"backends":[{"id":"antigravity","name":"Antigravity"}],
                   "quota":[{"provider":"anthropic_claude","windows":[{"id":"five_hour","remaining":40}]},
                            {"provider":"antigravity","windows":[
                              {"label":"Gemini · Week","remaining":80,"resets_at":"2026-11-02T00:00:00Z","group":"Gemini","models":["Gemini Flash","Gemini Pro"]},
                              {"label":"Gemini · 5h","remaining":35,"resets_at":"2026-10-30T18:00:00Z","group":"Gemini","models":["Gemini Flash","Gemini Pro"]},
                              {"label":"Argon · Week","remaining":100,"resets_at":null,"group":"Argon","models":["Argon"]}]}]}"""
            )
        )
        runCurrent()
        val shown = seen.last().antigravity!!
        assertTrue(shown.configured)
        assertEquals(listOf("Gemini" to 35, "Argon" to 100), shown.groups.map { it.name to it.remaining })
        assertEquals(listOf("Gemini Flash", "Gemini Pro"), shown.groups.first().models)
        assertEquals(
            listOf(AntigravityWindow("Week", 80, "2026-11-02T00:00:00Z"), AntigravityWindow("5h", 35, "2026-10-30T18:00:00Z")),
            shown.groups.first().windows,
        )
        assertEquals("", shown.groups.last().windows.single().resets_at)
        assertEquals(emptyList(), shown.groups.last().models)
    }

    @Test
    fun anAntigravityGroupLeftOutOfTheSummaryIsASettingOfTheAddOn() = runTest {
        var excluded = listOf("Argon")
        val view = {
            """{"title_model":"openai","worktree_retention_days":14,"board_order":[],"title_models":[],""" +
                """"antigravity_excluded":[${excluded.joinToString(",") { "\"$it\"" }}]}"""
        }
        bridge.routes["GET settings"] = { BridgeResponse(200, json(view())) }
        bridge.routes["POST settings"] = { body ->
            val change = body!!["antigravity_inclusion"] as JsonObject
            val group = change.str("group")
            excluded = if (change.bool("included")) excluded - group else excluded + group
            BridgeResponse(200, json(view()))
        }
        val core = core()
        val seen = watch(core, Slice.SLICE_ANTIGRAVITY)
        bridge.state.emit(
            json(
                """{"questions":[],"backends":[{"id":"antigravity"}],"quota":[{"provider":"antigravity","windows":[
                   {"label":"Gemini · Week","remaining":95,"group":"Gemini"},{"label":"Argon · Week","remaining":0,"group":"Argon"}]}]}"""
            )
        )
        runCurrent()
        // the groups come with the stream, which of them the summary leaves out is read when Usage opens
        assertEquals(listOf("Gemini" to false, "Argon" to true), seen.last().antigravity!!.groups.map { it.name to it.excluded })

        core.dispatch(Command(change_setting = SettingChange(antigravity_inclusion = AntigravityInclusion("Gemini", false))))
        runCurrent()
        assertEquals("""{"antigravity_inclusion":{"group":"Gemini","included":false}}""", bridge.sent("POST settings").single().toString())
        assertEquals(listOf(true, true), seen.last().antigravity!!.groups.map { it.excluded })

        core.dispatch(Command(change_setting = SettingChange(antigravity_inclusion = AntigravityInclusion("Argon", true))))
        runCurrent()
        assertEquals(listOf("Gemini" to true, "Argon" to false), seen.last().antigravity!!.groups.map { it.name to it.excluded })
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(change_setting = SettingChange(antigravity_inclusion = AntigravityInclusion(" ", true)))).kind)
    }

    @Test
    fun boardsKeepOneOrderForBothPagesAndEveryDevice() = runTest {
        bridge.answer("GET settings", """{"title_model":"openai","worktree_retention_days":14,"board_order":["b2"],"title_models":[]}""")
        bridge.answer("POST boards/order", """{"ok":true,"board_order":["b3","b1","b2"]}""")
        val core = core()
        val seen = watch(core, Slice.SLICE_SETTINGS)
        assertEquals(listOf("b2"), seen.last().settings!!.board_order)
        core.dispatch(Command(order_boards = OrderBoards(listOf("b3", "b1"))))
        assertEquals("""{"ids":["b3","b1"]}""", bridge.sent("POST boards/order").single().toString())
        runCurrent()
        assertEquals(listOf("b3", "b1", "b2"), seen.last().settings!!.board_order)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(order_boards = OrderBoards(emptyList()))).kind)
        // dragged ones first, the rest (new boards) after them in Dieter's order
        assertEquals(listOf("b2", "b1", "b4", "b5"), AddonRules.inBoardOrder(listOf("b1", "b4", "b2", "b5"), listOf("b2", "b1")) { it })
    }

    @Test
    fun questionsAreAnsweredAsTheAgentAskedAndLeaveThePageAtOnce() = runTest {
        bridge.state.emit(
            json(
                """{"claude_auth":null,"questions":[
                {"id":"q-1","session":"c_a","created_at":"t","input":{"allowPartialAnswers":false,"questions":[
                  {"id":"colour","question":"Which colour?","header":"Colour","allowFreeForm":true,
                   "options":[{"id":"red","label":"Red"},{"id":"blue","label":"Blue","description":"calm"}]},
                  {"question":"Why?"}]}},
                {"id":"q-2","session":"c_b","input":{"questions":[{"question":"Go?","options":[{"label":"Yes"}]}]}}]}"""
            )
        )
        bridge.answer("POST questions/q-1/answer", """{"ok":true}""")
        bridge.answer("POST questions/q-2/answer", """{"ok":true}""")
        val core = core()
        val mine = watch(core, Slice.SLICE_QUESTIONS, "c_a")
        val all = watch(core, Slice.SLICE_QUESTIONS)
        assertEquals(listOf("q-1"), mine.last().questions!!.questions.map { it.id })
        assertEquals(2, all.last().questions!!.questions.size)
        val question = mine.last().questions!!.questions.single()
        assertEquals("Which colour?", question.summary)
        assertEquals("1", question.items[1].id, "an item without id is named by its place")
        assertTrue(question.items[1].free_form, "an item without options takes text")

        val blue = mapOf("colour" to Answer(listOf("blue"), freeform = " and green "))
        assertFalse(AddonRules.canAnswer(question, blue))
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(answer_question = AnswerQuestion("q-1", blue))).kind)
        assertEquals(
            "Answer sent.",
            core.dispatch(Command(answer_question = AnswerQuestion("q-1", blue + ("1" to Answer(freeform = "because"))))).message,
        )
        assertEquals(
            json("""{"action":"answered","answers":{"colour":{"optionIds":["blue"],"freeform":"and green"},"1":{"optionIds":[],"freeform":"because"}}}"""),
            bridge.sent("POST questions/q-1/answer").single(),
        )
        runCurrent()
        assertTrue(mine.last().questions!!.questions.isEmpty())
        assertEquals(
            Failure.Kind.KIND_FAILED,
            core.fails(Command(answer_question = AnswerQuestion("q-1", blue))).kind,
            "an answered question is gone",
        )
        assertEquals("Question declined.", core.dispatch(Command(answer_question = AnswerQuestion("q-2", decline = true))).message)
        assertEquals(json("""{"action":"declined"}"""), bridge.sent("POST questions/q-2/answer").single())
    }

    @Test
    fun repositoriesAreCheckedBeforeTheBridgeClones() = runTest {
        bridge.answer("POST project", """{"ok":true,"name":"Hello-World","existed":true}""")
        val core = core()
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(add_repository = AddRepository(git_url = "ftp://x/y"))).kind)
        assertEquals(Failure.Kind.KIND_INVALID, core.fails(Command(add_repository = AddRepository(name = "bad name"))).kind)
        assertEquals(
            "Hello-World was already registered.",
            core.dispatch(Command(add_repository = AddRepository(git_url = "https://github.com/octocat/Hello-World.git"))).message,
        )
        bridge.answer("POST project", """{"ok":true,"name":"Scratch"}""")
        assertEquals("Scratch added.", core.dispatch(Command(add_repository = AddRepository(name = "Scratch"))).message)
        assertEquals(json("""{"folder":"Scratch"}"""), bridge.sent("POST project").last())
    }

    @Test
    fun anUnreachableBridgeShowsOnThePageAndFailsCommandsAsUnreachable() = runTest {
        val core = core()
        val seen = watch(core, Slice.SLICE_SETTINGS)
        assertFalse(seen.last().settings!!.loading)
        assertTrue(seen.last().settings!!.error.startsWith("The add-on did not answer"))
        assertEquals(Failure.Kind.KIND_UNREACHABLE, core.fails(Command(unenroll_gateway = Step())).kind)
    }
}

class AddonRulesTest {
    private val single =
        com.getdieter.addon.v1.QuestionItem(id = "a", options = listOf(com.getdieter.addon.v1.QuestionOption("x"), com.getdieter.addon.v1.QuestionOption("y")))

    @Test
    fun aTapReplacesTheChoiceOrTogglesItWhereSeveralAreAllowed() {
        assertEquals(listOf("y"), AddonRules.choose(single, Answer(listOf("x")), "y").option_ids)
        val many = single.copy(multiple = true)
        assertEquals(listOf("x", "y"), AddonRules.choose(many, Answer(listOf("x")), "y").option_ids)
        assertEquals(listOf("y"), AddonRules.choose(many, Answer(listOf("x", "y")), "x").option_ids)
    }

    @Test
    fun formInputsAreCheckedAsTheBridgeChecksThem() {
        assertEquals(null, AddonRules.gatewayUrlProblem("https://gateway.example.net:4443"))
        assertTrue(AddonRules.gatewayUrlProblem("https://gw/path") != null)
        assertEquals(null, AddonRules.sshTargetProblem("pi@192.0.2.1:2222"))
        assertEquals("Hello-World", AddonRules.repositoryName("git@github.com:octocat/Hello-World.git"))
        assertEquals(null, AddonRules.repositoryProblem("ssh://git@host:22/team/repo", "", "main"))
        assertTrue(AddonRules.repositoryProblem("https://github.com/o/r", "", "bad branch") != null)
    }
}
