package com.getdieter.addon.core

import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.CreateDreamfall
import com.getdieter.addon.v1.DecideIdea
import com.getdieter.addon.v1.DreamfallRun
import com.getdieter.addon.v1.DreamfallSettings
import com.getdieter.addon.v1.Failure
import com.getdieter.addon.v1.IdeaGroup
import com.getdieter.addon.v1.IdeaMeta
import com.getdieter.addon.v1.PauseDreamfall
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.RunDreamfallStage
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.StageKind
import com.getdieter.addon.v1.TrailEvent
import com.getdieter.addon.v1.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A night of Dreamfall: Enumeration found two ideas, Navigation promoted one, nothing picked yet. */
private fun night(rev: Int = 10, extraPipeline: String = "", navigationLane: String = "todo") =
    json(
        """{"rev":$rev,
        "projects":[{"id":"p1","name":"Dieter"}],
        "boards":[{"id":"b-e","name":"Enumeration","project_id":"p1","lanes":["todo","running","review","done"]},
                  {"id":"b-n","name":"Navigation","project_id":"p1","lanes":["todo","running","review","done"]},
                  {"id":"b-i","name":"Implementation","project_id":"p1","lanes":["todo","running","review","done"]},
                  {"id":"b-x","name":"Spare","project_id":"p1","lanes":["todo","running","review","done"]},
                  {"id":"b-y","name":"Lanes of its own","project_id":"p1","lanes":["todo","done"]}],
        "harnesses":[{"id":"mock","name":"Mock","default_model":"m1","models":[{"id":"m1","name":"Mock 1","efforts":["low","high"]}]},
                     {"id":"claude-code","name":"Claude Code","default_model":"opus","models":[{"id":"opus","name":"Opus","efforts":[]}]}],
        "dreamfall":{"ready":true,"defaults":{"navigation_logic":"Promote what helps."},"ideas":{"df1":3},
          "pipelines":[{"id":"df1","name":"Nightly","project_id":"p1","goal":"Make it nicer","categories":["UX","Speed"],
            "per_category":2,"navigation_logic":"Keep good ones","implementation_instructions":"Write tests",
            "implementation_mail":false,"timezone":"Europe/Berlin","enabled":true,"mail":false,
            "agent":{"provider":"mock","model":"m1","effort":"low"},
            "stages":[
              {"role":"enumeration","name":"Enumeration","board_id":"b-e","actor":"agent","cron":"0 2 * * *","run_title":"Enumeration run","old_run_titles":[],"schedule_id":"s-e"},
              {"role":"navigation","name":"Navigation","board_id":"b-n","actor":"agent","cron":"0 5 * * 1,3","run_title":"Navigation run","schedule_id":"s-n"},
              {"role":"implementation","name":"Implementation","board_id":"b-i","actor":"human","cron":"","run_title":"","schedule_id":""}]}
            $extraPipeline],
          "records":{
            "c-e1":{"stage":"enumeration","category":"UX","created_by":{"actor":"agent","name":"Enumeration agent","run":"run-e"},
                    "verdict":{"kind":"promote","rationale":"Useful","score":8},"transferred_to":"c-n1","events":3,"u":5},
            "c-n1":{"stage":"navigation","category":"UX","source":"c-e1","created_by":{"actor":"agent","name":"Navigation agent","run":"run-n"},
                    "source_verdict":{"kind":"promote","rationale":"Useful","score":8},"events":1,"u":6},
            "c-e2":{"stage":"enumeration","category":"Speed","created_by":{"actor":"agent","run":"run-e"},"events":1,"u":2}},
          "runs":{"df1":[{"card":"run-e","stage":"enumeration","status":"done","started_at":"2026-10-29T01:00:00Z",
                          "finished_at":"2026-10-29T01:04:00Z","counts":{"created":2}}]}},
        "dreamfall_cards":[
          {"id":"run-e","title":"Enumeration run","lane":"done","board_id":"b-e","runtime":"idle","created_at":"2026-10-29T01:00:00Z","updated_at":"2026-10-29T01:04:00Z"},
          {"id":"c-e1","title":"[UX] Dark mode","lane":"done","board_id":"b-e","initial_prompt":"## Idea\n**Why**: people ask\n\n---\nFound by run-e",
           "created_at":"2026-10-29T01:02:00Z","updated_at":"2026-10-29T04:00:00Z","phase_changed_at":"2026-10-29T04:00:00Z"},
          {"id":"c-e2","title":"[Speed] Cache","lane":"todo","board_id":"b-e","initial_prompt":"Cache the state","created_at":"2026-10-29T01:03:00Z","updated_at":"2026-10-29T01:03:00Z"},
          {"id":"c-n1","title":"[UX] Dark mode","lane":"$navigationLane","board_id":"b-n","initial_prompt":"## Idea\nA dark theme for the page\n\n---\n**Promoted from Enumeration** by Navigation agent, 2026-10-29: Useful\n\n<!-- dreamfall-source c-e1 -->",
           "created_at":"2026-10-29T04:00:00Z","updated_at":"2026-10-29T10:00:00Z"}]}"""
    )

class DreamfallTest {
    private val bridge = FakeBridge()

    // noon in Berlin (CET): 11:00 UTC
    private fun TestScope.core(): AddonCore =
        AddonCore(
                bridge,
                backgroundScope,
                dispatcher = StandardTestDispatcher(testScheduler),
                clock = { Instant.parse("2026-10-29T11:00:00Z") },
                offsetSeconds = { _, zone -> if (zone == "Europe/Berlin") 3600 else 0 },
                timezone = { "Europe/Berlin" },
            )
            .also { it.start() }

    private fun TestScope.watch(core: AddonCore, slice: Slice, scope: String = ""): MutableList<Update> {
        val seen = mutableListOf<Update>()
        core.observe(slice, scope) { seen += it }
        runCurrent()
        return seen
    }

    @Test
    fun theListNamesEachBoardWithWhatWaitsForThePerson() = runTest {
        bridge.state.emit(night())
        val list = watch(core(), Slice.SLICE_DREAMFALLS).last().dreamfalls!!
        val board = list.dreamfalls.single()
        assertEquals("Nightly", board.name)
        assertEquals("Dieter · 3 ideas", board.detail)
        assertEquals(1, board.waiting, "the idea Navigation holds for the person")
        assertEquals(listOf("b-e", "b-n", "b-i"), board.board_ids)
    }

    @Test
    fun aBoardShowsItsStagesTheirRunsAndTheIdeasByLane() = runTest {
        bridge.state.emit(night())
        val board = watch(core(), Slice.SLICE_DREAMFALL, "b-n").last().dreamfall!!
        assertEquals("df1", board.id, "a stage board opens its Dreamfall board")
        assertEquals("Dieter · Dreamfall · 3 ideas", board.subtitle)
        assertEquals(StageKind.STAGE_NAVIGATION, board.first)
        val (enumeration, navigation, implementation) = board.stages

        assertEquals("Agent · daily 02:00 · writes new ideas", enumeration.actor)
        assertEquals(DreamfallRun.Status.STATUS_DONE, enumeration.run!!.status)
        assertEquals("Run · today 02:00 – 02:04 · 2 new ideas", enumeration.run!!.text)
        assertEquals("run-e", enumeration.run!!.card_id)
        val queued = enumeration.groups.first()
        assertEquals(IdeaGroup.Kind.KIND_QUEUE, queued.kind)
        assertEquals(listOf("Cache"), queued.cards.map { it.title })
        assertEquals("Waiting for Navigation · 05:00", queued.cards.single().status)
        assertEquals("Speed", queued.cards.single().category)
        assertNull(queued.cards.single().action, "agents judge Enumeration's queue")
        val transferred = enumeration.groups[1]
        assertEquals(IdeaGroup.Kind.KIND_TRANSFERRED, transferred.kind)
        assertEquals("Transferred", transferred.title)
        assertEquals(listOf(IdeaMeta(IdeaMeta.Kind.KIND_AGENT, "Run today 02:00"), IdeaMeta(IdeaMeta.Kind.KIND_TO, "to Navigation")).toSet(), transferred.cards.single().metas.toSet())
        assertEquals("Idea people ask", transferred.cards.single().excerpt, "heading marks, bold labels and the footer go")

        assertEquals("Agent · Mon Wed 05:00 · judges Enumeration", navigation.actor)
        assertEquals("1 of 1", navigation.passes, "of Enumeration's judged ideas, one went on")
        assertEquals("No run yet · next at 05:00", navigation.run!!.text)
        assertTrue(navigation.can_run)
        val idea = navigation.groups.single().cards.single()
        assertEquals("Waiting for you", idea.status)
        assertEquals(DecideIdea.Decision.DECISION_PICK, idea.action!!.decision)
        assertEquals("Pick for Implementation", idea.action!!.ask_title)
        assertEquals("Idea A dark theme for the page", idea.excerpt)
        assertEquals("Promoted", idea.verdict!!.label)
        assertEquals("8/10 · Useful", idea.verdict!!.text)
        assertEquals(listOf("from Enumeration"), idea.metas.map { it.text })
        assertEquals("1h", idea.age)

        assertTrue(implementation.human)
        assertEquals("Pick an idea in Navigation to copy it here as a Todo draft. Start it when you are ready.", implementation.gate)
        assertEquals("Picked cards get your instructions", implementation.gate_extra)
        assertEquals("Nothing picked yet.", implementation.empty)
        assertFalse(implementation.can_run)
    }

    @Test
    fun aPickStaysBusyUntilTheStateShowsIt() = runTest {
        bridge.state.emit(night())
        bridge.answer("POST dreamfall/df1/act", """{"ok":true,"id":"c-i1","created":true,"rev":11}""")
        val core = core()
        val board = watch(core, Slice.SLICE_DREAMFALL, "df1")
        val pick = async { core.dispatch(Command(decide_idea = DecideIdea("c-n1", DecideIdea.Decision.DECISION_PICK, note = " soon ", worktree = true)), "Justin") }
        runCurrent()
        assertEquals("Picking…", board.last().dreamfall!!.stages[1].groups.single().cards.single().busy)
        val sent = bridge.sent("POST dreamfall/df1/act").single()!!
        assertEquals("""{"action":"pick","card":"c-n1","note":"soon","workspace":"worktree","start":false}""", sent.toString())
        assertFalse(pick.isCompleted, "rev 11 has not arrived yet")

        bridge.state.emit(night(rev = 11, navigationLane = "done"))
        runCurrent()
        assertEquals(Result("Picked: a Todo draft on Implementation."), pick.await())
        val navigation = board.last().dreamfall!!.stages[1]
        assertEquals("Picked", navigation.groups.single().title)
        assertEquals("", navigation.groups.single().cards.single().busy)
    }

    @Test
    fun decisionsAndRunsCheckWhatTheyAreFor() = runTest {
        bridge.state.emit(night())
        bridge.answer("POST dreamfall/df1/run", """{"ok":true}""")
        bridge.answer("POST dreamfall/df1/update", """{"ok":true}""")
        val core = core()
        runCurrent()
        val notOnABoard = assertFailsWith<AddonFailure> { core.dispatch(Command(decide_idea = DecideIdea("c-zz", DecideIdea.Decision.DECISION_PICK))) }
        assertEquals(Failure.Kind.KIND_INVALID, notOnABoard.failure.kind)
        assertFailsWith<AddonFailure> { core.dispatch(Command(run_dreamfall_stage = RunDreamfallStage("df1", StageKind.STAGE_IMPLEMENTATION))) }
        assertEquals("Navigation run started.", core.dispatch(Command(run_dreamfall_stage = RunDreamfallStage("df1", StageKind.STAGE_NAVIGATION))).message)
        assertEquals("""{"stage":"navigation"}""", bridge.sent("POST dreamfall/df1/run").single().toString())
        assertEquals("Schedules paused.", core.dispatch(Command(pause_dreamfall = PauseDreamfall("df1", paused = true))).message)
        assertEquals("""{"enabled":false}""", bridge.sent("POST dreamfall/df1/update").single().toString())
    }

    @Test
    fun aNewBoardStartsFromTheFormsDefaultsAndOpensWhenItIsThere() = runTest {
        bridge.state.emit(night())
        val core = core()
        val form = watch(core, Slice.SLICE_DREAMFALL_FORM, "new:p1").last().dreamfall_form!!
        assertEquals("Dieter", form.project_name)
        assertEquals(listOf("Spare"), form.boards.map { it.label }, "boards of other Dreamfalls and without a Review lane are not offered")
        val settings = form.settings!!
        assertEquals("claude-code", settings.provider)
        assertEquals("opus", settings.model)
        assertEquals("Europe/Berlin", settings.timezone)
        // the page adds the viewer's time zone; one that is not a zone name leaves the machine's
        val zoned = watch(core, Slice.SLICE_DREAMFALL_FORM, "new:p1@America/New_York").last().dreamfall_form!!
        assertEquals("America/New_York", zoned.settings!!.timezone)
        assertEquals("Dieter", zoned.project_name)
        val odd = watch(core, Slice.SLICE_DREAMFALL_FORM, "new:p1@not a zone").last().dreamfall_form!!
        assertEquals("Europe/Berlin", odd.settings!!.timezone)
        assertEquals("Promote what helps.", settings.navigation_logic)
        assertEquals((0..6).toList(), settings.days)

        assertEquals("Add categories, one per line, or a categories file of the project.", AddonRules.dreamfallProblem(settings))
        val ready = settings.copy(categories = "UX\nSpeed", days = listOf(1, 3))
        assertNull(AddonRules.dreamfallProblem(ready))
        assertEquals("Times are HH:MM, e.g. 02:00.", AddonRules.dreamfallProblem(ready.copy(navigation_time = "5")))
        assertEquals("The categories file is a path inside the project.", AddonRules.dreamfallProblem(ready.copy(categories_file = "../x")))

        bridge.answer("POST dreamfall", """{"ok":true,"id":"df2"}""")
        val created = async { core.dispatch(Command(create_dreamfall = CreateDreamfall("p1", ready, mapOf("implementation" to "b-x", "navigation" to ""))), "Justin") }
        runCurrent()
        val body = bridge.sent("POST dreamfall").single()!!
        assertEquals("""{"implementation":"b-x"}""", body["boards"].toString())
        assertEquals("[1,3]", body["days"].toString())
        assertEquals("""{"provider":"claude-code","model":"opus","effort":""}""", body["agent"].toString())
        assertFalse(created.isCompleted, "it waits for the new board in the state")
        bridge.state.emit(night(rev = 12, extraPipeline = """,{"id":"df2","name":"Dreamfall","project_id":"p1","stages":[]}"""))
        runCurrent()
        assertEquals(Result("Dreamfall board Dreamfall created.", created_id = "df2"), created.await())
    }

    @Test
    fun theSettingsFormShowsTheBoardAsSaved() = runTest {
        bridge.state.emit(night())
        val form = watch(core(), Slice.SLICE_DREAMFALL_FORM, "df1").last().dreamfall_form!!
        assertTrue(form.edit)
        val s = form.settings!!
        assertEquals("UX\nSpeed", s.categories)
        assertEquals("02:00", s.enumeration_time)
        assertEquals("05:00", s.navigation_time)
        assertEquals((0..6).toList(), s.days, "the days follow Enumeration's schedule")
        assertEquals("mock", s.provider)
        assertEquals(listOf("low", "high"), form.agents.first().models.single().efforts.map { it.id })
    }

    @Test
    fun anIdeaShowsItsBriefWhereItCameFromAndWhatHappened() = runTest {
        bridge.state.emit(night())
        bridge.answer(
            "GET dreamfall/card/c-n1",
            """{"card":{},"record":{},"brief":"## Idea\nA dark theme for the page <!-- df:c-e1 -->",
            "chain":[{"card":"c-e1","stage":"enumeration","created_by":{"actor":"agent","name":"Enumeration agent"},"created_at":"2026-10-29T01:02:00Z","verdict":{"kind":"promote"},"known":true},
                     {"card":"c-n1","stage":"navigation","created_by":{"actor":"agent","name":"Navigation agent"},"created_at":"2026-10-29T04:00:00Z","known":true,"self":true}],
            "events":[{"kind":"created","actor":"agent","name":"Enumeration agent","run":"run-e","at":"2026-10-27T01:02:00Z","stage":"enumeration"},
                      {"kind":"judged","actor":"agent","name":"Navigation agent","verdict":"promote","rationale":"Useful","score":8,"at":"2026-10-29T04:00:00Z"},
                      {"kind":"transferred","actor":"agent","stage":"navigation","at":"2026-10-29T04:00:00Z"}]}""",
        )
        val core = core()
        val seen = watch(core, Slice.SLICE_IDEA, "c-n1")
        val idea = seen.last().idea!!
        assertFalse(idea.trail_loading)
        assertEquals("Dark mode", idea.title)
        assertEquals("Dieter · Nightly · Navigation", idea.subtitle)
        assertEquals("Navigation · Waiting for you", idea.stage_label)
        assertEquals(listOf("Pick", "Park"), idea.actions.map { it.label })
        assertEquals("## Idea\nA dark theme for the page", idea.brief)
        assertEquals(
            listOf("Enumeration · c-e1" to "Found by Enumeration agent · today 02:02 · promoted", "Navigation · this card" to "Promoted by Navigation agent · today 05:00"),
            idea.provenance.map { it.title to it.detail },
        )
        assertEquals(
            listOf(
                "Created on Enumeration" to "Enumeration agent · run run-e · 27 Oct 02:02",
                "Judged: promote" to "Navigation agent · today 05:00",
                "Transferred to Navigation, source released" to "Agent · today 05:00",
                "Now: Waiting for you on Navigation" to "changed 1h ago",
            ),
            idea.trail.map { it.title to it.detail },
        )
        assertEquals("Useful Score 8/10.", idea.trail[1].quote)
        assertEquals(TrailEvent.Kind.KIND_NOW, idea.trail.last().kind)
        assertEquals(1, bridge.sent("GET dreamfall/card/c-n1").size, "read once while the record stays")
    }

    @Test
    fun aRemovedBoardSaysSo() = runTest {
        bridge.state.emit(night())
        val board = watch(core(), Slice.SLICE_DREAMFALL, "df9").last().dreamfall!!
        assertTrue(board.missing)
    }

    @Test
    fun cronLinesReadAsTimesAndDays() {
        assertEquals("02:05", Dreamfall.hhmm("5 2 * * *"))
        assertEquals("", Dreamfall.hhmm("@daily"))
        assertEquals(listOf(0, 1), Dreamfall.days("0 2 * * 7,1"))
        assertEquals("Mon Sun", Dreamfall.whenLabel("0 2 * * 0,1"))
        assertEquals(Triple(2026L, 10, 29), Dreamfall.civil(Instant.parse("2026-10-29T00:00:00Z").epochSeconds / 86_400))
        assertEquals(Triple(2000L, 2, 29), Dreamfall.civil(Instant.parse("2000-02-29T00:00:00Z").epochSeconds / 86_400))
    }
}
