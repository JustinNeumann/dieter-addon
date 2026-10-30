package com.getdieter.operator

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDate
import kotlin.system.exitProcess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `dreamfall`: the command the Dreamfall stage agents run from their run cards (DIETER_CARD_ID), one JVM per call
 * (`/usr/local/bin/dreamfall` starts it from the operator's jar). It creates, transfers and releases cards through
 * [Dreamfall], keeps the trail and refuses everything a stage's actor may not do (exit status 13); a duplicate idea is
 * 3, `run begin` while the earlier stage still works 75, a wrong call 2 as with argparse before.
 */
class DreamfallCommand(
    private val df: Dreamfall,
    private val me: String,
    private val out: PrintStream,
    private val err: PrintStream,
    private val now: () -> Instant = Instant::now,
) {
    private class Usage(message: String) : Exception(message)

    /** The parsed arguments of one subcommand: positionals, valued options (repeatable) and flags. */
    private class Args(val positional: List<String>, val values: Map<String, List<String>>, val flags: Set<String>) {
        fun value(name: String): String? = values[name]?.lastOrNull()
    }

    private class Spec(val name: String, val help: String, val positional: List<String>, val valued: List<String>, val flags: List<String>, val required: List<String> = emptyList(), val choices: Map<String, List<String>> = emptyMap())

    private val specs = listOf(
        Spec("show", "the Dreamfall board of your run card: goal, categories, stages", emptyList(), emptyList(), emptyList()),
        Spec("run", "`run begin` first, `run end` last", listOf("action"), listOf("--note"), listOf("--failed", "--keep-card"), choices = mapOf("action" to listOf("begin", "end"))),
        Spec("ideas", "every idea the Dreamfall board ever had (for duplicate checks)", emptyList(), listOf("--stage"), listOf("--json"), choices = mapOf("--stage" to Dreamfall.ROLES)),
        Spec("queue", "the ideas waiting for your judgement, with their briefs", emptyList(), emptyList(), listOf("--json")),
        Spec("add", "Enumeration: add one idea as a Todo card", emptyList(), listOf("--title", "--category", "--brief-file", "--brief"), emptyList(), required = listOf("--title", "--category")),
        Spec("promote", "copy an idea to your stage and release the original (Done)", listOf("card"), listOf("--rationale", "--score"), emptyList(), required = listOf("--rationale")),
        Spec("release", "keep an idea out (Review)", listOf("card"), listOf("--rationale", "--score"), emptyList(), required = listOf("--rationale")),
    )

    fun run(argv: List<String>): Int {
        val command = argv.firstOrNull()
        if (command == null || command == "-h" || command == "--help") {
            if (command == null) {
                err.println(usage())
                err.println("dreamfall: error: the following arguments are required: cmd")
                return 2
            }
            out.println(help())
            return 0
        }
        val spec = specs.firstOrNull { it.name == command }
        if (spec == null) {
            err.println(usage())
            err.println("dreamfall: error: argument cmd: invalid choice: '$command' (choose from ${specs.joinToString(", ") { "'${it.name}'" }})")
            return 2
        }
        val args =
            try {
                if (argv.drop(1).any { it == "-h" || it == "--help" }) {
                    out.println(help(spec))
                    return 0
                }
                parse(spec, argv.drop(1))
            } catch (e: Usage) {
                err.println(usage(spec))
                err.println("dreamfall ${spec.name}: error: ${e.message}")
                return 2
            }
        return try {
            when (spec.name) {
                "show" -> show()
                "run" -> run(args)
                "ideas" -> ideas(args)
                "queue" -> queue(args)
                "add" -> add(args)
                "promote" -> promote(args)
                else -> release(args)
            }
            0
        } catch (e: DreamfallError) {
            err.println("dreamfall: ${e.message}")
            e.code
        } catch (e: IOException) {
            err.println("dreamfall: $e")
            1
        }
    }

    private fun parse(spec: Spec, argv: List<String>): Args {
        val positional = mutableListOf<String>()
        val values = mutableMapOf<String, MutableList<String>>()
        val flags = mutableSetOf<String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i++]
            if (!a.startsWith("--") || a == "--") {
                positional += a
                continue
            }
            val name = a.substringBefore("=")
            when (name) {
                in spec.flags -> {
                    if ("=" in a) throw Usage("argument $name: ignored explicit argument '${a.substringAfter("=")}'")
                    flags.add(name)
                }
                in spec.valued -> {
                    val v = if ("=" in a) a.substringAfter("=") else argv.getOrNull(i++) ?: throw Usage("argument $name: expected one argument")
                    spec.choices[name]?.let { if (v !in it) throw Usage("argument $name: invalid choice: '$v' (choose from ${it.joinToString(", ") { c -> "'$c'" }})") }
                    values.getOrPut(name) { mutableListOf() }.add(v)
                }
                else -> throw Usage("unrecognized arguments: $a")
            }
        }
        if (positional.size < spec.positional.size) throw Usage("the following arguments are required: " + spec.positional.drop(positional.size).joinToString(", "))
        if (positional.size > spec.positional.size) throw Usage("unrecognized arguments: " + positional.drop(spec.positional.size).joinToString(" "))
        spec.positional.zip(positional).forEach { (p, v) -> spec.choices[p]?.let { if (v !in it) throw Usage("argument $p: invalid choice: '$v' (choose from ${it.joinToString(", ") { c -> "'$c'" }})") } }
        val missing = spec.required.filter { it !in values }
        if (missing.isNotEmpty()) throw Usage("the following arguments are required: " + missing.joinToString(", "))
        values["--score"]?.lastOrNull()?.let { if (it.trim().toIntOrNull() == null) throw Usage("argument --score: invalid int value: '$it'") }
        return Args(positional, values, flags)
    }

    private fun usage() = "usage: dreamfall [-h] {${specs.joinToString(",") { it.name }}} ..."

    private fun usage(spec: Spec): String {
        val parts = mutableListOf("usage: dreamfall ${spec.name} [-h]")
        for (v in spec.valued) parts += if (v in spec.required) "$v ${meta(v)}" else "[$v ${meta(v)}]"
        for (f in spec.flags) parts += "[$f]"
        parts += spec.positional.map { p -> spec.choices[p]?.joinToString(",", "{", "}") ?: p }
        return parts.joinToString(" ")
    }

    private fun meta(option: String) = Dreamfall.ROLES.takeIf { option == "--stage" }?.joinToString(",", "{", "}") ?: option.removePrefix("--").replace("-", "_").uppercase()

    private fun help(): String = buildString {
        appendLine(usage())
        appendLine()
        appendLine("Dreamfall boards: the commands of the stage agents. Run from a Dreamfall run card (DIETER_CARD_ID). Every step is")
        appendLine("recorded on the cards' trail; reruns do not duplicate.")
        appendLine()
        appendLine("positional arguments:")
        appendLine("  {${specs.joinToString(",") { it.name }}}")
        for (s in specs) appendLine("    ${s.name.padEnd(20)}${s.help}")
        appendLine()
        appendLine("options:")
        append("  -h, --help            show this help message and exit")
    }

    private fun help(spec: Spec): String = buildString {
        appendLine(usage(spec))
        appendLine()
        appendLine(spec.help)
        if (spec.positional.isNotEmpty()) {
            appendLine()
            appendLine("positional arguments:")
            spec.positional.forEach { appendLine("  ${spec.choices[it]?.joinToString(",", "{", "}") ?: it}") }
        }
        appendLine()
        appendLine("options:")
        appendLine("  -h, --help            show this help message and exit")
        for (v in spec.valued) appendLine("  $v ${meta(v)}")
        for (f in spec.flags) appendLine("  $f")
    }.trimEnd()

    // ------------------------------------------------------------------ the calling agent
    /** The calling agent: its run card, the Dreamfall board that card's board belongs to, and its stage. */
    private inner class Ctx(requireAgent: Boolean = true) {
        val card: DfCard
        val p: JsonObject
        val stage: JsonObject
        val name: String
        val model: String

        init {
            if (!Dreamfall.CARD.matches(me)) throw DreamfallError("run this from a Dreamfall run card (DIETER_CARD_ID is not set)")
            card = df.cardShow(me)
            p = df.pipelineForBoard(card.boardId) ?: throw DreamfallError("card $me is not on a stage board of a Dreamfall board")
            stage = Dreamfall.stageOfBoard(p, card.boardId)!!
            if (requireAgent && stage.field("actor") != "agent") {
                throw DreamfallError("${stage.field("name")} is the person's stage: agents do not create, move or judge cards there", 13)
            }
            name = "${stage.field("name")} agent"
            model = listOf(card.provider, card.model).filter { it.isNotEmpty() }.joinToString(" · ")
        }

        val role get() = stage.field("role")

        fun up(): JsonObject? = Dreamfall.stages(p).getOrNull(Dreamfall.stageIndex(p, role) - 1)

        fun require(role: String, what: String) {
            if (this.role != role) throw DreamfallError("$what belongs to the ${Dreamfall.stage(p, role)?.field("name")} run, not to ${stage.field("name")}", 13)
        }

        fun bump(field: String, n: Int = 1) {
            df.kvUpdate("r." + card.id) { found ->
                val cur = found ?: buildJsonObject {
                    put("pipeline", p.field("id"))
                    put("stage", role)
                    put("card", card.id)
                    put("started_at", df.nowIso())
                    put("status", "running")
                    put("counts", JsonObject(emptyMap()))
                }
                val counts = cur.obj("counts") ?: JsonObject(emptyMap())
                cur.updated("counts" to counts.updated(field to ((counts[field] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) + n))
            }
        }
    }

    private class Idea(val card: DfCard, val stage: String, val stageName: String)

    private fun allIdeas(p: JsonObject, roles: Set<String>? = null): List<Idea> =
        Dreamfall.stages(p).filter { roles == null || it.field("role") in roles }.flatMap { s ->
            (df.boardCards(s.field("board_id")) + df.boardCards(s.field("board_id"), archived = true))
                .filter { !Dreamfall.isRunCard(p, it) }
                .map { Idea(it, s.field("role"), s.field("name")) }
        }

    // ------------------------------------------------------------------ the subcommands
    private fun show() {
        val ctx = Ctx(requireAgent = false)
        val p = ctx.p
        out.println(pretty(2).encodeToString(JsonElement.serializer(), buildJsonObject {
            put("dreamfall", p.field("id"))
            put("name", p.field("name"))
            put("goal", p["goal"] ?: JsonNull)
            put("your_stage", ctx.role)
            put("your_card", ctx.card.id)
            put("categories", p["categories"] ?: JsonNull)
            put("categories_file", p.field("categories_file").ifEmpty { null })
            put("ideas_per_category", p["per_category"] ?: JsonNull)
            put("navigation_logic", Dreamfall.navigationLogic(p))
            put("stages", json(Dreamfall.stages(p).map { s -> mapOf("role" to s.field("role"), "name" to s.field("name"), "board" to s.field("board_id"), "actor" to s.field("actor")) }))
        }))
    }

    private fun run(a: Args) {
        val ctx = Ctx()
        val p = ctx.p
        val s = ctx.stage
        val pid = p.field("id")
        val cardId = ctx.card.id
        if (a.positional[0] == "begin") {
            val up = ctx.up()
            if (up != null && up.field("actor") == "agent") {
                val last = df.kvGet("l.$pid.${up.field("role")}").first
                val running = df.boardCards(up.field("board_id"), lane = "running").filter { Dreamfall.isRunCard(p, it) }
                val recent = last != null && last.field("status") == "running" && now().epochSecond - Dreamfall.parseTs(last.field("started_at")) < 6 * 3600
                if (recent || running.isNotEmpty()) {
                    val who = last?.field("card").orEmpty().ifEmpty { running.firstOrNull()?.id.orEmpty() }
                    throw DreamfallError("the ${up.field("name")} run ($who) is still working. Wait 5 minutes and call `dreamfall run begin` again.", Dreamfall.WAIT_EXIT)
                }
            }
            val startedAt = df.nowIso()
            val rec = buildJsonObject {
                put("pipeline", pid)
                put("stage", ctx.role)
                put("card", cardId)
                put("started_at", startedAt)
                put("status", "running")
                put("counts", JsonObject(emptyMap()))
                put("model", ctx.model)
            }
            df.kvUpdate("r.$cardId") { cur -> if (cur == null) rec else rec.updated("counts" to (cur["counts"].takeIf { it.truthy() } ?: JsonObject(emptyMap()))) }
            putLatest(pid, ctx.role, mapOf("card" to cardId, "status" to "running", "started_at" to startedAt))
            out.println("Run $cardId of ${s.field("name")} begins (${p.field("name")}).")
            if (ctx.role == "enumeration") out.println("Next: learn the project, `dreamfall ideas`, then `dreamfall add …` per idea, finally `dreamfall run end`.")
            else out.println("Next: `dreamfall queue`, then `dreamfall promote` or `dreamfall release` for every idea, finally `dreamfall run end`.")
            return
        }
        val status = if ("--failed" in a.flags) "failed" else "done"
        val rec = df.kvUpdate("r.$cardId") { found ->
            val cur = found ?: buildJsonObject {
                put("pipeline", pid)
                put("stage", ctx.role)
                put("card", cardId)
                put("started_at", df.nowIso())
                put("counts", JsonObject(emptyMap()))
            }
            cur.updated("status" to status, "finished_at" to df.nowIso(), "note" to Dreamfall.clip(a.value("--note")))
        }!!
        putLatest(pid, ctx.role, mapOf("card" to cardId, "status" to status, "started_at" to (rec["started_at"] ?: JsonNull), "finished_at" to rec["finished_at"]))
        if (ctx.role == "navigation" && status == "done") {
            val first = Dreamfall.stage(p, "enumeration")!!
            val left = df.boardCards(first.field("board_id"), lane = "todo").filter { !Dreamfall.isRunCard(p, it) }
            if (left.isNotEmpty()) out.println("Note: ${left.size} idea(s) still wait in the ${first.field("name")} queue (they arrived after this run began or were skipped).")
        }
        if ("--keep-card" !in a.flags) df.move(cardId, "done")
        val counts = rec.obj("counts").orEmpty().entries.sortedBy { it.key }.joinToString(", ") { (k, v) -> "$k ${(v as? JsonPrimitive)?.content ?: v}" }
        out.println("Run $cardId $status: " + counts.ifEmpty { "nothing recorded" } + ".")
    }

    private fun putLatest(pid: String, role: String, value: Map<String, Any?>) {
        df.kvUpdate("l.$pid.$role") { JsonObject(value.mapValues { (_, v) -> json(v) } + mapOf("pipeline" to JsonPrimitive(pid), "stage" to JsonPrimitive(role))) }
    }

    private fun ideas(a: Args) {
        val ctx = Ctx(requireAgent = false)
        val rows = allIdeas(ctx.p, a.values["--stage"]?.toSet())
        if ("--json" in a.flags) {
            out.println(pretty(1).encodeToString(JsonElement.serializer(), JsonArray(rows.map {
                buildJsonObject {
                    put("id", it.card.id)
                    put("title", it.card.title)
                    put("stage", it.stage)
                    put("lane", it.card.lane.ifEmpty { null })
                    put("archived", it.card.archived)
                }
            })))
            return
        }
        out.println("${rows.size} ideas in ${ctx.p.field("name")} (stage / state: title):")
        for (r in rows.sortedWith(compareBy({ Dreamfall.ROLES.indexOf(it.stage) }, { it.card.title.lowercase() }))) {
            val state = if (r.card.archived) "archived" else Dreamfall.STAGES.getValue(r.stage).lanes[r.card.lane] ?: r.card.lane
            out.println("- ${r.stageName} / $state: ${r.card.title}")
        }
    }

    private fun queue(a: Args) {
        val ctx = Ctx()
        val up = ctx.up() ?: throw DreamfallError("${ctx.stage.field("name")} is the first stage: it has no queue to judge", 13)
        val rows = df.boardCards(up.field("board_id"), lane = "todo").filter { !Dreamfall.isRunCard(ctx.p, it) }
        if ("--json" in a.flags) {
            out.println(pretty(1).encodeToString(JsonElement.serializer(), JsonArray(rows.map {
                buildJsonObject {
                    put("id", it.id)
                    put("title", it.title)
                    put("prompt", it.prompt)
                }
            })))
            return
        }
        if (rows.isEmpty()) {
            out.println("The ${up.field("name")} queue is empty.")
            return
        }
        out.println("${rows.size} idea(s) wait in the ${up.field("name")} queue:\n")
        for (c in rows) out.println("## ${c.title}\ncard: ${c.id}\n\n${c.prompt.trim()}\n\n")
    }

    private fun add(a: Args) {
        val ctx = Ctx()
        ctx.require("enumeration", "adding ideas")
        val p = ctx.p
        val s = ctx.stage
        val category = Dreamfall.clip(a.value("--category"), 60).trim('[', ']', ' ')
        val title = Dreamfall.clip(a.value("--title"), 160)
        if (title.isEmpty() || category.isEmpty()) throw DreamfallError("--title and --category are required")
        val full = "[$category] ${Dreamfall.splitCategory(title).second}"
        var brief = a.value("--brief-file")?.let { File(it).readText() } ?: a.value("--brief").orEmpty()
        if (brief.isBlank()) throw DreamfallError("a brief is required (--brief-file FILE or --brief TEXT)")
        val key = Dreamfall.titleKey(full)
        allIdeas(p).firstOrNull { Dreamfall.titleKey(it.card.title) == key }?.let {
            throw DreamfallError("exists: ${it.card.id} (${it.stageName}): ${it.card.title}. Think of a different idea.", 3)
        }
        brief = "${brief.trimEnd()}\n\n---\nFound by the ${s.field("name")} agent of the Dreamfall board ${p.field("name")}, run `${ctx.card.id}`, ${LocalDate.now()}" +
            (if (ctx.model.isNotEmpty()) " (${ctx.model})" else "") + ".\n"
        val agent = p.obj("agent") ?: JsonObject(emptyMap())
        val args = mutableListOf("card", "create", "--project", p.field("project_id"), "--board", s.field("board_id"), "--lane", "todo", "--title", full,
            "--workspace", "project", "--format", "json")
        for (k in listOf("provider", "model", "effort")) agent.field(k).ifEmpty { ctx.card.agent(k) }.takeIf { it.isNotEmpty() }?.let { args += listOf("--$k", it) }
        if (ctx.card.checkoutId.isNotEmpty()) args += listOf("--checkout", ctx.card.checkoutId)
        val cid = df.createCard(args, brief, "dreamfall-idea-")
        df.recordCreated(p, cid, s.field("board_id"), full, "agent", ctx.name, run = ctx.card.id, category = category)
        ctx.bump("created")
        out.println("added: $cid $full")
    }

    private fun judgedCard(ctx: Ctx, id: String): Pair<DfCard, JsonObject> {
        val up = ctx.up() ?: throw DreamfallError("${ctx.stage.field("name")} judges no earlier stage", 13)
        val c = df.cardShow(id)
        if (c.boardId != up.field("board_id")) throw DreamfallError("$id is not an idea of the ${up.field("name")} board", 13)
        if (Dreamfall.isRunCard(ctx.p, c)) throw DreamfallError("$id is a run card, not an idea", 13)
        return c to up
    }

    private fun score(a: Args): Int? {
        val score = a.value("--score")?.trim()?.toInt() ?: return null
        if (score !in 0..10) throw DreamfallError("--score must be between 0 and 10")
        return score
    }

    private fun promote(a: Args) {
        val ctx = Ctx()
        val (c, up) = judgedCard(ctx, a.positional[0])
        val rationale = a.value("--rationale").orEmpty()
        if (rationale.isBlank()) throw DreamfallError("--rationale is required: one sentence why this idea goes on")
        val rec = df.kvGet("c." + c.id).first
        if (c.lane == "review" && rec?.obj("verdict")?.field("kind") == "release") throw DreamfallError("${c.id} was released already; it is not promoted any more")
        if (c.lane !in setOf("todo", "done")) throw DreamfallError("${c.id} is not waiting in the ${up.field("name")} queue (lane ${c.lane})")
        val moved = df.transfer(ctx.p, c, ctx.role, "agent", ctx.name, "Promoted", rationale = rationale, score = score(a), run = ctx.card.id)
        if (moved.completed) ctx.bump("promoted")
        out.println((if (moved.completed) "promoted" else "already promoted") + ": ${c.id} -> ${moved.id} (${ctx.stage.field("name")})")
    }

    private fun release(a: Args) {
        val ctx = Ctx()
        val (c, up) = judgedCard(ctx, a.positional[0])
        val rationale = a.value("--rationale").orEmpty()
        if (rationale.isBlank()) throw DreamfallError("--rationale is required: one sentence why this idea stops here")
        val rec = df.kvGet("c." + c.id).first
        if (c.lane == "done" || rec?.field("transferred_to").orEmpty().isNotEmpty()) throw DreamfallError("${c.id} was promoted already")
        val already = c.lane == "review"
        df.release(ctx.p, c, "agent", ctx.name, kind = "release", rationale = rationale, score = score(a), run = ctx.card.id)
        if (!already) ctx.bump("released")
        out.println((if (already) "already released" else "released") + ": ${c.id} (${up.field("name")} · Review)")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun pretty(indent: Int) = Json {
        prettyPrint = true
        prettyPrintIndent = " ".repeat(indent)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // UTF-8 whatever the locale (the titles carry "·" and "…"), as Python wrote it
            val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
            val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
            val dieter = ProcessDieter(System.getenv("DIETER_BIN") ?: "/usr/local/bin/dieter")
            exitProcess(DreamfallCommand(Dreamfall(dieter), System.getenv("DIETER_CARD_ID").orEmpty(), out, err).run(args.toList()))
        }
    }
}
