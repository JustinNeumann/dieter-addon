package com.getdieter.operator

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The `dieter` CLI as Dreamfall uses it, in memory: the kv store with revisions, cards, boards and schedules. */
class FakeStore : Dieter {
    val kv = linkedMapOf<String, Pair<String, Int>>()
    val cards = linkedMapOf<String, JsonObject>()
    val boards = linkedMapOf<String, JsonObject>()
    val schedules = linkedMapOf<String, List<String>>()
    val calls = mutableListOf<List<String>>()
    private var serial = 0

    private fun next(prefix: String) = prefix + "%08d".format(++serial)

    fun board(project: String, name: String): String {
        val id = next("b_")
        boards[id] = buildJsonObject {
            put("id", id)
            put("project_id", project)
            put("name", name)
            put("lanes", Json.parseToJsonElement("""[{"id":"todo"},{"id":"running"},{"id":"review"},{"id":"done"}]"""))
        }
        return id
    }

    fun card(board: String, title: String, lane: String = "todo", prompt: String = "brief of $title"): String {
        val id = next("c_")
        cards[id] = buildJsonObject {
            put("id", id)
            put("title", title)
            put("board_id", board)
            put("project_id", boards[board]?.get("project_id") ?: JsonPrimitive("p_1"))
            put("lane", lane)
            put("initial_prompt", prompt)
        }
        return id
    }

    fun record(key: String): JsonObject? = kv[key]?.let { Json.parseToJsonElement(it.first) as JsonObject }

    override fun run(args: List<String>, timeout: Duration): JsonElement? {
        calls += args
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val b64 = { s: String -> Base64.getEncoder().encodeToString(s.toByteArray()) }
        return when (args.take(2).joinToString(" ")) {
            "kv get" -> {
                val key = opt("--key")!!
                val (value, rev) = kv[key] ?: throw CliError("error: key $key not found")
                Json.parseToJsonElement("""{"key":"$key","revision":"r$rev","deleted":false,"valueJson":"${b64(value)}","versions":[{"valueJson":"${b64(value)}"}]}""")
            }
            "kv put" -> {
                val key = opt("--key")!!
                val rev = opt("--revision")
                val have = kv[key]
                if (rev == null && have != null) throw CliError("error: key exists")
                if (rev != null && (have == null || "r${have.second}" != rev)) throw CliError("error: revision conflict")
                kv[key] = File(opt("--file")!!).readText() to (have?.second ?: 0) + 1
                null
            }
            "kv list" -> {
                val prefix = opt("--prefix").orEmpty()
                JsonObject(mapOf("entries" to JsonArray(kv.filterKeys { it.startsWith(prefix) }.map { (k, v) -> Json.parseToJsonElement("""{"key":"$k","revision":"r${v.second}","valueJson":"${b64(v.first)}"}""") })))
            }
            "kv delete" -> kv.remove(opt("--key")!!).let { null }
            "card show" -> JsonObject(mapOf("card" to (cards[args.last()] ?: throw CliError("error: card ${args.last()} not found"))))
            "card list" -> {
                val archived = "--archived" in args
                JsonObject(mapOf("cards" to JsonArray(cards.values.filter {
                    it.field("board_id") == opt("--board") && (opt("--lane") == null || it.field("lane") == opt("--lane")) && it["archived"].truthy() == archived
                })))
            }
            "card create" -> {
                val id = next("c_")
                cards[id] = buildJsonObject {
                    put("id", id)
                    put("title", opt("--title"))
                    put("board_id", opt("--board"))
                    put("project_id", opt("--project"))
                    put("lane", opt("--lane"))
                    put("initial_prompt", opt("--prompt-file")?.let { File(it).readText() } ?: opt("--prompt"))
                    for (k in listOf("provider", "model", "effort")) opt("--$k")?.let { put(k, it) }
                    put("mail_access", "--mail" in args)
                }
                JsonObject(mapOf("card" to cards.getValue(id)))
            }
            "card move" -> {
                cards[args.last()] = cards.getValue(args.last()).updated("lane" to opt("--lane"))
                null
            }
            "card start" -> {
                cards[args.last()] = cards.getValue(args.last()).updated("lane" to "running", "initial_prompt_sent_at" to "2026-10-30T10:00:00Z")
                null
            }
            "board list" -> JsonObject(mapOf("boards" to JsonArray(boards.values.toList())))
            "board create" -> JsonObject(mapOf("board" to boards.getValue(board(opt("--project")!!, opt("--name")!!))))
            "board show" -> JsonObject(mapOf("board" to (boards[args.last()] ?: throw CliError("error: board ${args.last()} not found")).updated("retirementRevision" to "rv")))
            "board retire" -> {
                check(opt("--revision") == "rv")
                boards[args.last()] = boards.getValue(args.last()).updated("retired" to true)
                null
            }
            "schedule create" -> next("sch_").let { id ->
                schedules[id] = args
                JsonObject(mapOf("schedule" to buildJsonObject { put("id", id) }))
            }
            "schedule update" -> {
                if (args.last() !in schedules) throw CliError("error: schedule not found")
                schedules[args.last()] = args
                null
            }
            "schedule delete" -> schedules.remove(args.last()).let { null }
            "schedule run" -> JsonPrimitive("started")
            else -> throw IllegalStateException("unexpected: $args")
        }
    }
}

class DreamfallTest {
    private val pipe = Json.parseToJsonElement(
        """{"id": "df_0123456789", "name": "Dreamfall", "project_id": "p_1", "goal": "Ideas for the house", "categories": ["Energy", "Heating"],
        "per_category": 3, "enabled": true, "agent": {"provider": "claude-code", "model": "opus", "effort": "high"}, "timezone": "Europe/Berlin",
        "stages": [{"role": "enumeration", "name": "Enumeration", "board_id": "b_e", "actor": "agent", "cron": "0 2 * * *", "run_title": "Dreamfall · Enumeration run", "schedule_id": "sch_e"},
                   {"role": "navigation", "name": "Navigation", "board_id": "b_n", "actor": "agent", "cron": "0 5 * * *", "run_title": "Dreamfall · Navigation run", "schedule_id": "sch_n"},
                   {"role": "implementation", "name": "Implementation", "board_id": "b_i", "actor": "human"}]}""",
    ) as JsonObject
    private val store = FakeStore()
    private val df = Dreamfall(store)

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun card(board: String, title: String) = DfCard.of(obj("""{"board_id": "$board", "title": "$title"}"""))

    private fun settings(vararg pairs: Pair<String, Any?>, cur: JsonObject = JsonObject(emptyMap())) = df.cleanSettings(JsonObject(pairs.associate { (k, v) -> k to json(v) }), cur)

    @Test
    fun titlesCarryTheCategoryAndDuplicatesIgnoreCaseAndPunctuation() {
        assertEquals("Heating" to "Shutters as night insulation", Dreamfall.splitCategory("[Heating] Shutters as night insulation"))
        assertEquals("" to "No category", Dreamfall.splitCategory("No category"))
        assertEquals(Dreamfall.titleKey("[Heating] Shutters as night insulation"), Dreamfall.titleKey("shutters as NIGHT insulation!"))
        assertTrue(Dreamfall.titleKey("Shutters at night") != Dreamfall.titleKey("Shutters as night insulation"))
        assertTrue(Dreamfall.isRunCard(pipe, card("b_e", "Dreamfall · Enumeration run")))
        assertFalse(Dreamfall.isRunCard(pipe, card("b_n", "Dreamfall · Enumeration run")))
        val renamed = pipe.updated("stages" to Dreamfall.stages(pipe).mapIndexed { i, s -> if (i == 0) s.updated("old_run_titles" to listOf("Ideas · Enumeration run")) else s })
        assertTrue(Dreamfall.isRunCard(renamed, card("b_e", "Ideas · Enumeration run")))
    }

    @Test
    fun siblingsMergeTheirTrailsAndKeepTheNewestVerdict() {
        val a = obj("""{"card": "c_1", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}], "verdict": {"kind": "release", "at": "2026-10-28T02:00:00Z"}}""")
        val b = obj("""{"card": "c_1", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}, {"id": "2", "kind": "judged", "at": "2026-10-28T03:00:00Z"}],
            "verdict": {"kind": "promote", "at": "2026-10-28T03:00:00Z"}, "transferred_to": "c_2"}""")
        val enc = { v: JsonObject -> Base64.getEncoder().encodeToString(v.toString().toByteArray()) }
        val entry = obj("""{"key": "c.c_1", "revision": "r", "valueJson": "${enc(a)}", "versions": [{"valueJson": "${enc(a)}"}, {"valueJson": "${enc(b)}"}]}""")
        val (merged, revision) = Dreamfall.decodeEntry(entry)
        assertEquals("r", revision)
        assertEquals(listOf("1", "2"), merged!!.objs("events").map { it.field("id") })
        assertEquals("promote", merged.obj("verdict")!!.field("kind"))
        assertEquals("c_2", merged.field("transferred_to"))
        assertNull(Dreamfall.decodeEntry(obj("""{"key": "p.x", "revision": "r2", "deleted": true, "valueJson": "${enc(obj("""{"id": "x"}"""))}"}""")).first)
    }

    @Test
    fun aRerunAddsNoSecondTransferAndLongTextsAreClipped() {
        var rec = obj("""{"events": []}""")
        rec = Dreamfall.addEvents(rec, df.event("transferred", "agent", "Navigation agent", "to" to "c_2"))
        rec = Dreamfall.addEvents(rec, df.event("transferred", "agent", "Navigation agent", "to" to "c_2"))
        rec = Dreamfall.addEvents(rec, df.event("restored", "human", "you"))
        rec = Dreamfall.addEvents(rec, df.event("restored", "human", "you"))
        assertEquals(listOf("transferred", "restored", "restored"), rec.objs("events").map { it.field("kind") })
        val e = df.event("judged", "agent", "x", "rationale" to "word ".repeat(400), "score" to null)
        assertTrue(e.field("rationale").length <= Dreamfall.MAX_TEXT)
        assertFalse("score" in e)
        assertTrue(Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$").matches(e.field("at")))
    }

    @Test
    fun settingsHaveDefaultsTimesAndWeekdays() {
        val st = settings("name" to "Ideas", "goal" to "g", "categories" to "- Energy\n\n* Heating\n")
        assertEquals(listOf("Energy", "Heating"), st.strings("categories"))
        assertEquals("0 2 * * *" to "0 5 * * *", st.field("enumeration_cron") to st.field("navigation_cron"))
        assertEquals("45 4 * * *", Dreamfall.cronOf("4:45"))
        assertEquals("04:45", Dreamfall.hhmmOf("45 4 * * *"))
        assertEquals("45 4 * * 1,2,3,4,5", Dreamfall.cronOf("4:45", listOf(1, 2, 3, 4, 5)))
        assertEquals("45 4 * * 1,6,0", Dreamfall.cronOf("4:45", listOf(0, 6, 1)))
        assertEquals("45 4 * * *", Dreamfall.cronOf("4:45", listOf(0, 1, 2, 3, 4, 5, 6)))
        assertEquals(listOf(1, 6, 0), Dreamfall.daysOf("45 4 * * 1,6,0"))
        assertEquals(Dreamfall.ALL_DAYS, Dreamfall.daysOf("45 4 * * *"))
        assertEquals("04:45", Dreamfall.hhmmOf("45 4 * * 1,6,0"))
        val weekdays = settings("name" to "x", "categories" to "a", "days" to listOf(1, 3))
        assertEquals("0 2 * * 1,3" to "0 5 * * 1,3", weekdays.field("enumeration_cron") to weekdays.field("navigation_cron"))
        val onTwoDays = pipe.updated("stages" to Dreamfall.stages(pipe).map { if (it.field("role") == "enumeration") it.updated("cron" to "0 2 * * 1,3") else it })
        assertEquals("0 2 * * 1,3", settings("name" to "y", cur = onTwoDays).field("enumeration_cron"))
        for (bad in listOf<Any>(emptyList<Int>(), listOf(7), listOf("1"), "1", listOf(true))) {
            assertFailsWith<DreamfallError>("$bad") { settings("name" to "x", "categories" to "a", "days" to bad) }
        }
        // an update keeps what it does not name
        val renamed = settings("name" to "Renamed", cur = pipe)
        assertEquals("0 2 * * *", renamed.field("enumeration_cron"))
        assertEquals(listOf("Energy", "Heating"), renamed.strings("categories"))
    }

    @Test
    fun badSettingsAreRefused() {
        for (bad in listOf(
            mapOf("name" to "", "categories" to "a"), mapOf("name" to "x"), mapOf("name" to "x", "categories" to "a", "timezone" to "Not a zone!"),
            mapOf("name" to "x", "categories_file" to "../secret"), mapOf("name" to "x", "categories" to "a", "enumeration_time" to "25:00"),
        )) assertFailsWith<DreamfallError>("$bad") { df.cleanSettings(json(bad) as JsonObject) }
        for (bad in listOf("", "   \n ", "x".repeat(Dreamfall.MAX_LOGIC + 1))) {
            assertFailsWith<DreamfallError> { settings("name" to "x", "categories" to "a", "navigation_logic" to bad) }
        }
    }

    @Test
    fun thePromptsNameTheStepsTheBoardsAndTheMail() {
        val e = Dreamfall.enumerationPrompt(pipe)
        for (s in listOf("dreamfall run begin", "dreamfall ideas", "dreamfall add --category", "at least 3 new ideas", "- Energy", "dreamfall run end",
            "Ideas for the house", "do not touch the Navigation or Implementation boards")) assertTrue(s in e, s)
        assertTrue("improvements of this project" in Dreamfall.enumerationPrompt(pipe.updated("goal" to "")))
        val n = Dreamfall.navigationPrompt(pipe)
        assertTrue("dreamfall promote <card>" in n)
        assertTrue("never create, move or start cards there" in n)
        assertTrue("\n7. Report briefly" in n)
        assertFalse("mail send" in n)
        val mailed = Dreamfall.navigationPrompt(pipe.updated("mail" to true))
        assertTrue("dieter mail send" in mailed)
        assertTrue("\n8. Report briefly" in mailed)
    }

    @Test
    fun theNavigationLogicIsThePersonsAndReachesThePromptQuoted() {
        assertEquals(Dreamfall.DEFAULT_NAVIGATION_LOGIC, settings("name" to "x", "categories" to "a").field("navigation_logic"))
        val st = settings("name" to "x", "categories" to "a", "navigation_logic" to "Only ideas under 50 EUR.\r\n\r\n\r\nNever lighting.")
        assertEquals("Only ideas under 50 EUR.\n\nNever lighting.", st.field("navigation_logic"))
        val t = Dreamfall.navigationPrompt(pipe.updated("navigation_logic" to st.field("navigation_logic")))
        assertTrue("   > Only ideas under 50 EUR.\n   >\n   > Never lighting." in t)
        assertFalse("about one idea in four" in t)
        assertTrue("it decides, not\n   your own taste" in t)
        val kept = pipe.updated("navigation_logic" to "Keep me.")
        assertEquals("Keep me.", settings("name" to "Renamed", cur = kept).field("navigation_logic"))
        assertEquals("New.", settings("navigation_logic" to "New.", cur = kept).field("navigation_logic"))
        assertTrue("about one idea in five should pass" in Dreamfall.navigationLogic(pipe.updated("pass_hint" to "about one idea in five")))
        assertEquals(Dreamfall.DEFAULT_NAVIGATION_LOGIC, Dreamfall.navigationLogic(pipe))
        assertEquals("", settings("name" to "x", "categories" to "a").field("goal"))
    }

    @Test
    fun pickedCardsCarryTheBoardsInstructionsMailAccessAndHowToFinish() {
        val st = settings("name" to "x", "categories" to "a")
        assertEquals("" to false, st.field("implementation_instructions") to st["implementation_mail"].truthy())
        assertEquals("## For every implementation on this Dreamfall board\n\n" + Dreamfall.FINISH_HOWTO to emptyList(), Dreamfall.implementationExtras(pipe.updated(*st.entries.map { it.key to it.value }.toTypedArray())))
        assertFalse("For every implementation" in df.copyBrief("idea", "Picked", "Navigation", "you", "", "c_1"))
        assertTrue("--lane review" in Dreamfall.implementationExtras(pipe).first && "no git remote" in Dreamfall.implementationExtras(pipe).first)
        val withMail = settings("name" to "x", "categories" to "a", "implementation_instructions" to "Add a chart.\r\n\r\n\r\nMail it.", "implementation_mail" to true)
        assertEquals("Add a chart.\n\nMail it.", withMail.field("implementation_instructions"))
        val (text, args) = Dreamfall.implementationExtras(pipe.updated(*withMail.entries.map { it.key to it.value }.toTypedArray()))
        assertEquals(listOf("--mail"), args)
        val brief = df.copyBrief("**Idea:** X", "Picked", "Navigation", "you", "now", "c_1", text)
        assertTrue("## For every implementation on this Dreamfall board\n\nAdd a chart.\n\nMail it." in brief)
        assertTrue(brief.indexOf("dieter mail send") < brief.indexOf(Dreamfall.FINISH_HOWTO), "finishing comes last")
        assertTrue(brief.trimEnd().endsWith("<!-- dreamfall-source: c_1 -->"), "the marker stays last")
        assertTrue("vega-lite" in Dreamfall.implementationExtras(pipe.updated("implementation_mail" to true)).first)
        val cur = pipe.updated("implementation_instructions" to "Keep.", "implementation_mail" to true)
        assertEquals("Keep." to true, settings("name" to "Renamed", cur = cur).let { it.field("implementation_instructions") to it["implementation_mail"].truthy() })
        assertEquals("" to false, settings("implementation_instructions" to "", "implementation_mail" to false, cur = cur).let { it.field("implementation_instructions") to it["implementation_mail"].truthy() })
        assertFailsWith<DreamfallError> { settings("implementation_instructions" to "x".repeat(Dreamfall.MAX_LOGIC + 1), cur = cur) }
    }

    /** A Dreamfall board made through the fake CLI: its pipeline and the run cards' boards. */
    private fun created(vararg extra: Pair<String, Any?>): JsonObject =
        df.create(JsonObject(mapOf("project" to json("p_1"), "categories" to json("Energy\nHeating"), "per_category" to json(2), "enabled" to json(false), "agent" to json(mapOf("provider" to "mock"))) + extra.associate { (k, v) -> k to json(v) }), "Justin")

    @Test
    fun aDreamfallBoardIsThreeBoardsAndTwoPausedSchedules() {
        val p = created()
        assertEquals(listOf("enumeration" to "agent", "navigation" to "agent", "implementation" to "human"), Dreamfall.stages(p).map { it.field("role") to it.field("actor") })
        assertEquals(3, store.boards.size)
        assertEquals(2, store.schedules.size)
        val nav = store.schedules.values.first { "Dreamfall · Navigation" in it }
        assertTrue("--enabled=false" in nav)
        assertEquals("0 5 * * *", nav[nav.indexOf("--cron") + 1])
        assertEquals("skip_if_open", nav[nav.indexOf("--open-card") + 1])
        assertEquals("Dreamfall · Navigation run", nav[nav.indexOf("--title") + 1])
        assertEquals("mock", nav[nav.indexOf("--provider") + 1])
        val record = store.record("p." + p.field("id"))!!
        assertEquals(Dreamfall.stages(p).map { it.field("schedule_id") }.take(2), Dreamfall.stages(record).map { it.field("schedule_id") }.take(2))
        assertEquals("Justin", record.field("created_by"))

        // a new time and logic change both schedules; the earlier run title stays a run, not an idea
        val updated = df.update(p.field("id"), obj("""{"name": "Garden ideas", "enumeration_time": "01:30", "navigation_logic": "Only garden ideas pass."}"""))
        val enumeration = Dreamfall.stage(updated, "enumeration")!!
        assertEquals("Garden ideas · Enumeration run" to listOf("Dreamfall · Enumeration run"), enumeration.field("run_title") to enumeration.strings("old_run_titles"))
        assertEquals("30 1 * * *", store.schedules.getValue(enumeration.field("schedule_id")).let { it[it.indexOf("--cron") + 1] })
        assertTrue("   > Only garden ideas pass." in store.schedules.getValue(Dreamfall.stage(updated, "navigation")!!.field("schedule_id")).let { it[it.indexOf("--prompt") + 1] })

        assertEquals(Dreamfall.stages(p).map { it.field("board_id") }, df.delete(p.field("id")).getValue("boards").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(store.schedules.isEmpty() && store.kv.isEmpty() && store.boards.size == 3, "the boards stay")
    }

    @Test
    fun aFailedCreationRemovesWhatItMade() {
        store.board("p_1", "Enumeration")
        val adopted = store.board("p_1", "Mine")
        val other = store.board("p_2", "Theirs")
        val before = store.boards.keys.toSet()
        assertFailsWith<DreamfallError> { created("boards" to mapOf("navigation" to other)) }.also { assertEquals("board $other is not a board of this project", it.message) }
        assertTrue(store.kv.isEmpty())
        val made = store.boards.keys - before
        assertTrue(made.isNotEmpty() && made.all { store.boards.getValue(it)["retired"].truthy() }, "the boards it made are retired")
        assertTrue(before.none { store.boards.getValue(it)["retired"].truthy() }, "the others stay")
        val p = created("boards" to mapOf("navigation" to adopted))
        assertEquals(adopted, Dreamfall.stage(p, "navigation")!!.field("board_id"))
        assertEquals("Dreamfall · Enumeration", store.boards.getValue(Dreamfall.stage(p, "enumeration")!!.field("board_id")).field("name"), "a taken name gets the board's")
    }

    private class Ran(val code: Int, val out: String, val err: String)

    private fun command(card: String, vararg args: String): Ran {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = DreamfallCommand(df, card, PrintStream(out, true), PrintStream(err, true)).run(args.toList())
        return Ran(code, out.toString(), err.toString())
    }

    @Test
    fun theStageAgentsWorkThroughTheCommandAndCannotTouchThePersonsStage() {
        val p = created()
        val (e, n, i) = Dreamfall.stages(p).map { it.field("board_id") }
        val run = store.card(e, "Dreamfall · Enumeration run", lane = "running")
        assertEquals(0, command(run, "run", "begin").code)
        for ((cat, title) in listOf("Heating" to "Shutters as night insulation", "Energy" to "Base-load creep detector", "Safety" to "Leak watch")) {
            val r = command(run, "add", "--category", cat, "--title", title, "--brief", "**Idea:** $title.")
            assertEquals(0, r.code, r.err)
            assertTrue(r.out.startsWith("added: c_"))
        }
        val dup = command(run, "add", "--category", "Heating", "--title", "shutters as NIGHT insulation", "--brief", "x")
        assertEquals(3, dup.code)
        assertTrue("exists" in dup.err)
        assertEquals(2, command(run, "add", "--title", "x").code, "--category is required")
        assertEquals("Run $run done: created 3.\n", command(run, "run", "end", "--note", "3 ideas").out)
        assertEquals("done", store.cards.getValue(run).field("lane"))
        val shutters = store.cards.values.first { "Shutters" in it.field("title") }
        assertEquals("[Heating] Shutters as night insulation", shutters.field("title"))
        assertTrue("Found by the Enumeration agent of the Dreamfall board Dreamfall, run `$run`" in shutters.field("initial_prompt"))
        assertEquals("agent", store.record("c." + shutters.field("id"))!!.obj("created_by")!!.field("actor"))

        // Navigation waits while an Enumeration run works
        val second = store.card(e, "Dreamfall · Enumeration run", lane = "running")
        assertEquals(0, command(second, "run", "begin").code)
        val nav = store.card(n, "Dreamfall · Navigation run", lane = "running")
        val wait = command(nav, "run", "begin")
        assertEquals(Dreamfall.WAIT_EXIT to true, wait.code to ("still working" in wait.err))
        assertEquals(0, command(second, "run", "end").code)
        assertEquals(0, command(nav, "run", "begin").code)
        val queue = Json.parseToJsonElement(command(nav, "queue", "--json").out).jsonArray.map { it.jsonObject }
        assertEquals(3, queue.size)
        val id = { t: String -> queue.first { t in it.getValue("title").jsonPrimitive.content }.getValue("id").jsonPrimitive.content }
        assertTrue(command(nav, "promote", id("Shutters"), "--score", "8", "--rationale", "Cheap and saves heat.").out.startsWith("promoted"))
        assertTrue(command(nav, "promote", id("Shutters"), "--score", "8", "--rationale", "again").out.startsWith("already promoted"))
        assertEquals(0, command(nav, "release", id("Base-load"), "--score", "3", "--rationale", "Too vague.").code)
        assertEquals(1, command(nav, "release", id("Shutters"), "--rationale", "flip").code, "promoted already")
        assertEquals(1, command(nav, "promote", id("Base-load"), "--rationale", "flip").code, "released already")
        assertEquals(2, command(nav, "release", id("Leak"), "--score", "x", "--rationale", "r").code)
        assertEquals(0, command(nav, "release", id("Leak"), "--rationale", "Not now.").code)
        assertEquals("The Enumeration queue is empty.\n", command(nav, "queue").out)
        assertTrue(command(nav, "run", "end").out.startsWith("Run $nav done: promoted 1, released 2."))
        val copy = store.cards.values.single { it.field("board_id") == n && "Shutters" in it.field("title") }
        assertTrue("**Promoted from Enumeration** by Navigation agent" in copy.field("initial_prompt"))
        val source = store.record("c." + id("Shutters"))!!
        assertEquals(copy.field("id"), source.field("transferred_to"))
        assertEquals(8, source.obj("verdict")!!["score"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, source.objs("events").count { it.field("kind") == "transferred" }, "the rerun left no trace")
        val ideas = Json.parseToJsonElement(command(nav, "ideas", "--json").out).jsonArray
        assertEquals(4, ideas.size, "three ideas and the copy, no run cards")

        // the person's stage
        val sneaky = store.card(i, "Sneaked in")
        for (args in listOf(arrayOf("promote", copy.field("id"), "--rationale", "x"), arrayOf("run", "begin"), arrayOf("add", "--category", "A", "--title", "B", "--brief", "c"))) {
            val r = command(sneaky, *args)
            assertEquals(13 to true, r.code to ("person's stage" in r.err), args.joinToString(" "))
        }
        assertEquals(1, command("", "show").code)
        assertTrue("\"your_stage\": \"implementation\"" in command(sneaky, "show").out)
    }

    @Test
    fun thePersonPicksParksRestoresAndStarts() {
        val p = created("implementation_instructions" to "Add a chart.", "implementation_mail" to true)
        val pid = p.field("id")
        val (e, n, i) = Dreamfall.stages(p).map { it.field("board_id") }
        val idea = store.card(e, "[Energy] Solar forecast")
        val promoted = df.act(pid, obj("""{"action": "promote", "card": "$idea", "note": "I like it"}"""), "Justin")
        val copy = promoted.field("id")
        assertEquals(n, store.cards.getValue(copy).field("board_id"))
        assertEquals(mapOf(idea to "done", copy to "todo"), promoted.getValue("lanes").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(setOf("c.$idea", "c.$copy"), promoted.getValue("records").jsonObject.keys, "the records for the live view")

        // a known copy is reused without listing the board
        store.kv.remove("c.$idea")
        store.cards[idea] = store.cards.getValue(idea).updated("lane" to "todo")
        store.calls.clear()
        val again = df.act(pid, obj("""{"action": "promote", "card": "$idea"}"""), "Justin") { board -> if (board == n) mapOf(idea to copy) else null }
        assertEquals(copy, again.field("id"))
        assertFalse(store.calls.any { it.take(2) == listOf("card", "list") || it.take(2) == listOf("card", "create") })

        val parked = df.act(pid, obj("""{"action": "park", "card": "$copy"}"""), "Justin")
        assertEquals("review", store.cards.getValue(copy).field("lane"))
        assertEquals("park", parked.getValue("records").jsonObject.getValue("c.$copy").jsonObject.obj("verdict")!!.field("kind"))
        df.act(pid, obj("""{"action": "restore", "card": "$copy"}"""), "Justin")
        assertEquals("todo", store.cards.getValue(copy).field("lane"))
        assertEquals("only waiting or parked ideas can be picked", assertFailsWith<DreamfallError> {
            store.cards[copy] = store.cards.getValue(copy).updated("lane" to "running")
            df.act(pid, obj("""{"action": "pick", "card": "$copy"}"""), "Justin")
        }.message)
        store.cards[copy] = store.cards.getValue(copy).updated("lane" to "todo")
        assertEquals("promote is not possible from Navigation", assertFailsWith<DreamfallError> { df.act(pid, obj("""{"action": "promote", "card": "$copy"}""")) }.message)

        val picked = df.act(pid, obj("""{"action": "pick", "card": "$copy", "note": "Before winter.", "workspace": "worktree"}"""), "Justin")
        val draft = store.cards.getValue(picked.field("id"))
        assertEquals(i to "todo", draft.field("board_id") to draft.field("lane"))
        assertTrue(draft["mail_access"].truthy())
        assertTrue("**Picked from Navigation** by Justin" in draft.field("initial_prompt") && ": Before winter." in draft.field("initial_prompt"))
        assertTrue("## For every implementation on this Dreamfall board\n\nAdd a chart." in draft.field("initial_prompt"))
        val chain = store.record("c." + picked.field("id"))!!
        assertEquals(copy to "implementation", chain.field("source") to chain.field("stage"))
        assertEquals("worktree", store.calls.last { it.take(2) == listOf("card", "create") }.let { it[it.indexOf("--workspace") + 1] })

        val started = df.act(pid, obj("""{"action": "start", "card": "${picked.field("id")}"}"""), "Justin")
        assertEquals("running", started.getValue("lanes").jsonObject.getValue(picked.field("id")).jsonPrimitive.content)
        assertEquals("started", store.record("c." + picked.field("id"))!!.objs("events").last().field("kind"))
        assertEquals("action must be promote, release, pick, park, restore or start", assertFailsWith<DreamfallError> { df.act(pid, obj("""{"action": "x", "card": "$idea"}""")) }.message)
    }

    @Test
    fun theOperatorAnswersThePagesActionsAndRefusalsAre400() {
        val server = java.net.InetSocketAddress("127.0.0.1", 0).let { addr ->
            OperatorServer(addr, routes(store, Settings(kotlin.io.path.createTempFile().toFile(), OllamaEntity(null)) { "" }, Projects(store, kotlin.io.path.createTempDirectory().toFile(), { null }))).start()
        }
        try {
            val http = java.net.http.HttpClient.newHttpClient()
            fun post(path: String, body: String): Pair<Int, JsonObject> {
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${server.address.port}/api/$path"))
                    .header("X-Remote-User-Display-Name", "Justin").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
                val answer = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                return answer.statusCode() to obj(answer.body())
            }
            val (status, made) = post("dreamfall", """{"project": "p_1", "categories": "Energy", "enabled": false}""")
            assertEquals(200, status, "$made")
            val pid = made.field("id")
            assertEquals("Justin", made.obj("pipeline")!!.field("created_by"))
            assertEquals(400 to "project required", post("dreamfall", "{}").let { it.first to it.second.field("error") })
            assertEquals(200 to "started", post("dreamfall/$pid/run", """{"stage": "enumeration"}""").let { it.first to it.second.field("run") })

            assertEquals(400 to "implementation has no agent schedule", post("dreamfall/$pid/run", """{"stage": "implementation"}""").let { it.first to it.second.field("error") })
            assertEquals(400, post("dreamfall/$pid/act", """{"action": "pick", "card": "nonsense"}""").first)
            val p = made.obj("pipeline")!!
            val card = store.card(Dreamfall.stage(p, "implementation")!!.field("board_id"), "Mine")
            val (s2, recorded) = post("dreamfall/$pid/record", """{"card": "$card", "board": "${Dreamfall.stage(p, "implementation")!!.field("board_id")}", "title": "[Garden] Mine"}""")
            assertEquals(200, s2)
            assertEquals("human" to "Justin", recorded.obj("record")!!.obj("created_by")!!.let { it.field("actor") to it.field("name") })
            assertEquals("Garden", recorded.obj("record")!!.field("category"))
            assertEquals(200, post("dreamfall/$pid/delete", "{}").first)
        } finally {
            server.stop(0)
        }
    }
}
