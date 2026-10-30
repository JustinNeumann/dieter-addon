package com.getdieter.operator

import java.io.File
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The add-on's operator: the half of the Python bridge already moved to Kotlin, one area per release
 * (docs/PLAN.md, Step 2). It runs as the add-on's user `dieter`, like the bridge and the agents, and does the
 * daemon's operator work through the `dieter` CLI. It holds no secret: everything it can do, that user can do
 * anyway. The gateway session stays in the web host (user dieter-web). Until it takes over port 8099 the bridge
 * forwards the moved paths to it. Environment:
 *   DIETER_OPERATOR_ADDR   loopback address to listen on, default 127.0.0.1:8096
 *   DIETER_BIN             the Dieter CLI, default /usr/local/bin/dieter (DIETER_HOME and the daemon address
 *                          come from the environment, as for the bridge)
 *   BRIDGE_SETTINGS_FILE   the add-on's settings, default /data/bridge_settings.json
 *   DIETER_OPTIONS_FILE    the add-on's options as the Supervisor writes them, default /data/options.json (missing:
 *                          not an add-on); DIETER_PROJECTS_DIR  where repositories go, default /data/projects
 *   SUPERVISOR_TOKEN       Home Assistant through the Supervisor (set inside the add-on)
 *   DIETER_VERSION         the fork release in the image, whose harness runtime is staged (the Dockerfile sets it)
 *   DIETER_RUNTIME_SRC     the runtime sources of that build, default /opt/dieter-runtime
 *   DIETER_DAEMON_ADDR     the daemon's API, default 127.0.0.1:4242
 * In the add-on it also starts the daemon and keeps it running, after staging the harness runtime and writing what
 * the options configure (Setup), owns the add-on's SSH key, its Claude sign-in and the gateway enrollment, and
 * removes the work folders of finished worktree cards (Housekeeping).
 */
fun main() {
    val env = System.getenv()
    val (host, port) = (env["DIETER_OPERATOR_ADDR"] ?: "127.0.0.1:8096").let { it.substringBeforeLast(":") to it.substringAfterLast(":").toInt() }
    val bin = env["DIETER_BIN"] ?: "/usr/local/bin/dieter"
    val dieter = ProcessDieter(bin)
    val home = File(env["DIETER_HOME"] ?: "/data/dieter")
    val ha = HomeAssistant.supervisor(env)
    val settings = Settings(File(env["BRIDGE_SETTINGS_FILE"] ?: "/data/bridge_settings.json"), OllamaEntity(ha)) {
        Settings.localDaemonId(home)
    }
    val optionsFile = File(env["DIETER_OPTIONS_FILE"] ?: "/data/options.json")
    val options = {
        if (!optionsFile.exists()) null
        else runCatching { Json.parseToJsonElement(optionsFile.readText()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }
    val projects = Projects(dieter, File(env["DIETER_PROJECTS_DIR"] ?: "/data/projects"), options)
    val supervision = options()?.let { Supervision(it, env, home) }
    val access = supervision?.let { s ->
        val restart = { s.daemon.restart(); Unit }
        Access(
            s.ssh,
            ClaudeLogin(s.userHome, options, restart),
            Enrollment(bin, home, s.userHome, dieter, options, restart),
        )
    }
    val uploads = Uploads(File(home, "uploads"))
    val titles = Titles(dieter, ha, settings::titleEntity)
    // the work folders of finished worktree cards, in the add-on only (the daemon never removes them)
    val housekeeping = supervision?.let {
        val dreamfall = Dreamfall(dieter)
        Housekeeping(Sweeper(dieter, Settings.RETENTION_DEFAULT_DAYS, implementationBoards = dreamfall::implementationBoards)) { settings.read().retentionDays }
    }
    // the usage the daemon's `quota list` lacks: probed in the add-on only
    val usage = supervision?.let { s ->
        Usage(
            s.userHome,
            File(home, "harness-staged/${env["DIETER_VERSION"].orEmpty()}/runtime"),
            File(env["DIETER_QUOTA_TOKEN_PROBE"] ?: "/opt/operator/quota_claude_token.mjs"),
            options,
            s::envFileVariables,
        )
    }
    val questions = Questions()
    val board = Board(
        dieter, home, addon = supervision != null, questions,
        boardOrder = { settings.read().boardOrder },
        haEvent = { name, data ->
            ha?.let { h -> runCatching { h.post("events/$name", data, java.time.Duration.ofSeconds(10)) }.onFailure { Log.line("ha event $name: ${it.message}") } }
        },
        usage = usage,
        claudeAuth = access?.claude?.let { c -> c::auth },
    )
    val cards = Cards(dieter, board, Dreamfall(dieter), titles, { settings.view()["titles_daemon_id"].truthy() }, home, addon = supervision != null)
    val server = OperatorServer(
        InetSocketAddress(host, port),
        routes(dieter, settings, projects, supervision?.daemon, access, questions, uploads, titles, housekeeping, Live(board, cards, usage, ha)),
    ).start()
    Log.line("listening on $host:${server.address.port}")
    supervision?.let { Thread.ofPlatform().name("supervisor").start(it::run) }
    housekeeping?.let { Thread.ofPlatform().name("housekeeping").daemon().start { it.loop() } }
    Thread.ofPlatform().name("board").start(board::loop)
    Thread.ofVirtual().name("watch").start { board.watch.loop(bin) }
    Thread.ofVirtual().name("dreamfall-kv").start { board.dreamfall.watch(bin) }
    supervision?.let {
        Thread.ofVirtual().name("startup").start {
            // the first start downloads the harness runtime (≈ 775 MB) before the daemon comes up
            if (Board.waitHealthy(dieter, 6 * 3_600_000L)) {
                setupProjects(projects, options())
                board.pollSlow()
                board.refresh()
            }
        }
    }
}

/** Projects from the add-on options (a convenience; more can be added from the page any time), cloned and registered. */
private fun setupProjects(projects: Projects, options: JsonObject?) {
    for (p in (options?.get("projects") as? kotlinx.serialization.json.JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
        val body = buildJsonObject { for (k in listOf("git_url", "name", "branch")) put(k, p.field(k)) }
        try {
            projects.add(body)
        } catch (e: Exception) {
            Log.line("projects: ${p.field("name").ifEmpty { p.field("git_url") }}: ${e.message}")
        }
    }
}

/** The add-on's start: the setup the options ask for, the harness runtime, then the daemon for good. */
class Supervision(options: JsonObject, env: Map<String, String>, home: File) {
    val userHome = File(env["HOME"] ?: "/data")
    val ssh = Ssh(userHome)
    private val setup = Setup(options, userHome, env["SUPERVISOR_TOKEN"])
    private val harness = env["DIETER_VERSION"]?.takeIf { it.isNotBlank() }?.let {
        Harness(home, it, Harness.machine(), File(env["DIETER_RUNTIME_SRC"] ?: "/opt/dieter-runtime"))
    }
    private val bin = env["DIETER_BIN"] ?: "/usr/local/bin/dieter"
    private val addr = env["DIETER_DAEMON_ADDR"] ?: "127.0.0.1:4242"
    val daemon = DaemonSupervisor(
        bin,
        command = {
            listOf(bin, "--store", home.path, "daemon", "start", "--addr", addr) +
                if (setup.envFile.exists()) listOf("--env-file", setup.envFile.path) else emptyList()
        },
        environment = { daemonEnvironment(env, harness?.runtimeDir, setup.envFileVariables()) },
        beforeStart = setup::writeEnvFile,
    )

    fun run() {
        daemon.stopStrays()
        step("ssh") { ssh.setup() }
        step("setup") { setup.git() }
        step("claude hint") { setup.claudeHint() }
        step("env") { setup.antigravity() }
        if (harness == null) Log.line("harness: DIETER_VERSION is not set, the daemon downloads its runtime itself")
        else {
            step("harness: staging failed, the daemon will download it itself") { harness.stage(); harness.buildRuntimeDir() }
            step("harness: removing old staged runtimes failed") { harness.prune() }
            step("harness: seeding the managed Bun failed, OMP discovery will download it itself") { harness.seedBun() }
        }
        daemon.loop()
    }

    private fun step(what: String, block: () -> Unit) = runCatching(block).onFailure { Log.line("$what: ${it.message}") }

    /** The provider variables of the daemon's env file (the usage probes take them too). */
    fun envFileVariables() = setup.envFileVariables()

    companion object {
        /**
         * What the daemon gets on top of the operator's environment: harness workers relay the agents' questions to
         * the operator, and Home Assistant for the agents (hass-cli takes HASS_SERVER/HASS_TOKEN: core's REST API
         * through the Supervisor's proxy; the token reaches only core, hassio_api is off). The daemon forwards only
         * whitelisted variables to its workers, DIETER_HARNESS_ENV names the extra ones. The env file's variables go
         * into the daemon's own environment too, so harness processes inherit the tokens either way.
         */
        fun daemonEnvironment(env: Map<String, String>, runtimeDir: File?, envFile: Map<String, String>): Map<String, String> {
            val out = linkedMapOf("DIETER_QUESTIONS_URL" to "http://${env["DIETER_OPERATOR_ADDR"] ?: "127.0.0.1:8096"}/api/questions")
            val token = env["SUPERVISOR_TOKEN"].orEmpty()
            if (token.isNotEmpty()) {
                out["HASS_SERVER"] = "http://supervisor/core"
                out["HASS_TOKEN"] = token
            }
            out["DIETER_HARNESS_ENV"] = (listOf(env["DIETER_HARNESS_ENV"].orEmpty()) + out.keys.toList()).filter { it.isNotEmpty() }.joinToString(",")
            if (runtimeDir != null && File(runtimeDir, "runner.mjs").exists()) out["DIETER_HARNESS_RUNTIME_DIR"] = runtimeDir.path
            return out + envFile
        }
    }
}

/** The add-on's ways in and out: its SSH key, its Claude sign-in and its daemon's gateway enrollment. */
class Access(val ssh: Ssh, val claude: ClaudeLogin, val enrollment: Enrollment)

/** The board's state and what Home Assistant does with cards; [usage] and [ha] in the add-on only. */
class Live(val board: Board, val cards: Cards, val usage: Usage? = null, val ha: HomeAssistant? = null)

/** Every path the operator answers, under /api/ like the bridge's; [access] only in the add-on, [live] where the board's state is kept. */
fun routes(
    dieter: Dieter,
    settings: Settings,
    projects: Projects,
    daemon: DaemonSupervisor? = null,
    access: Access? = null,
    questions: Questions = Questions(),
    uploads: Uploads? = null,
    titles: Titles = Titles(dieter, null) { null },
    housekeeping: Housekeeping? = null,
    live: Live? = null,
): Routes {
    val prompts = Prompts(dieter)
    val dreamfall = Dreamfall(dieter)
    val board = live?.board
    // after a sign-in, sign-out or enrollment the daemon starts again: its new state once it is healthy
    fun afterRestart(probe: Boolean) {
        if (board == null) return
        if (probe) live.usage?.forget()
        Thread.ofVirtual().start {
            Thread.sleep(3000)
            Board.waitHealthy(dieter, 180_000)
            board.pollSlow()
            board.refresh()
        }
    }
    // Home Assistant's dieter_question for a question an agent asked (the card's title from the state)
    fun questionEvent(body: JsonObject) {
        val input = body.obj("input") ?: JsonObject(emptyMap())
        val session = body.field("session")
        val card = board?.snapshot()?.second?.let { it.objs("cards") + it.objs("chats") }?.firstOrNull { it.field("id") == session }
        val asked = input.objs("questions")
        val event = buildJsonObject {
            put("id", body.field("id")); put("card_id", session); put("title", card?.field("title")?.ifEmpty { null } ?: session)
            put("question", asked.firstOrNull()?.field("question").orEmpty().take(300)); put("count", asked.size)
        }
        live?.ha?.let { ha -> Thread.ofVirtual().start { runCatching { ha.post("events/dieter_question", event, java.time.Duration.ofSeconds(10)) }.onFailure { Log.line("ha event dieter_question: ${it.message}") } } }
    }
    // a Dreamfall change shows in the next state at once: created or removed boards and a stage run need a poll
    fun shown(answer: JsonObject, vararg records: Pair<String, JsonObject?>, poll: Boolean = false): JsonObject {
        records.forEach { (k, v) -> board?.dreamfall?.putLocal(k, v) }
        if (poll) board?.refresh(wait = true)
        return answer
    }
    // the page's Dreamfall actions; like the bridge, every refusal and CLI failure is a 400 with its message
    fun dreamfall(handle: () -> JsonElement): JsonElement =
        try {
            handle()
        } catch (e: DreamfallError) {
            throw BadRequest(e.message ?: "refused")
        }
    val ok = buildJsonObject { put("ok", true) }
    fun Request.text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val routes = Routes()
        .get("prompts") { prompts.view() }
        .post("prompts") { prompts.update(it.body) }
        .get("settings") { settings.view() }
        .post("settings") { r ->
            val days = settings.read().retentionDays
            settings.update(r.body).also { if (settings.read().retentionDays != days) housekeeping?.wake() }   // sweep now
        }
        .post("titles") { titles.nameLater(it.body) }
        .post("boards/order") { r -> settings.order(r.body).also { a -> if (a["board_order"] is kotlinx.serialization.json.JsonArray) board?.reorderBoards(a.strings("board_order")) } }
        .post("project") { r -> projects.add(r.body).also { board?.refresh(wait = true) } }
        .post("page-load") { PageLoad.log(it.body) }
        // agents' questions: the harness worker registers and polls (DIETER_QUESTIONS_URL), the page answers
        .get("questions") { questions.pending() }
        .post("questions") { r -> questions.register(r.body).also { board?.touch(); questionEvent(r.body) } }
        .get("questions/{id}") { questions.poll(it.params.getValue("id")) ?: throw NotFound("no such question") }
        .post("questions/{id}/answer") { r -> questions.answer(r.params.getValue("id"), r.body).also { board?.touch() } }
        .post("dreamfall") { r ->
            dreamfall {
                val p = dreamfall.create(r.body, r.user)
                shown(buildJsonObject { put("ok", true); put("id", p.field("id")); put("pipeline", p) }, "p.${p.field("id")}" to p, poll = true)
            }
        }
        .post("dreamfall/{pid}/update") { r ->
            val pid = r.params.getValue("pid")
            dreamfall { dreamfall.update(pid, r.body).let { p -> shown(buildJsonObject { put("ok", true); put("pipeline", p) }, "p.$pid" to p) } }
        }
        .post("dreamfall/{pid}/delete") { r -> r.params.getValue("pid").let { pid -> dreamfall { shown(dreamfall.delete(pid), "p.$pid" to null, poll = true) } } }
        .post("dreamfall/{pid}/run") { r -> dreamfall { shown(dreamfall.runNow(r.params.getValue("pid"), r.text("stage")), poll = true) } }
        .post("dreamfall/{pid}/act") { r ->
            // the copies the live view holds: board id -> source card id -> its copy, so a pick lists no boards
            val copies = board?.dreamfall?.knownCopies()
            val answer = dreamfall { dreamfall.act(r.params.getValue("pid"), r.body, r.user, copies?.let { c -> { b: String -> c[b] } }) } as JsonObject
            live?.cards?.applyDecision(answer) ?: answer
        }
        .post("upload") {
            val store = uploads ?: throw BadRequest("uploads are kept in the add-on only")
            store.store(java.net.URLDecoder.decode(it.headers["x-filename"] ?: "file", Charsets.UTF_8), it.raw)
        }
        .post("daemon/restart") {
            // after a Claude sign-in or a gateway enrollment (the bridge): the daemon starts again with them
            val restarted = (daemon ?: throw BadRequest("the daemon is supervised in the add-on only")).restart()
            buildJsonObject { put("ok", true); put("restarted", restarted) }
        }
    if (live != null) {
        val (cards, b) = live.cards to live.board
        fun Request.card(): String = params.getValue("id").also { if (!it.startsWith("c_")) throw NotFound("not found") }
        routes
            // Home Assistant: sensor.dieter, the rest_commands and dieter_task.py (homeassistant/packages/dieter.yaml)
            .get("state") { b.snapshot().let { (rev, d) -> JsonObject(mapOf("rev" to JsonPrimitive(rev)) + d) } }
            .get("ha") { cards.haSummary() }
            .get("focus") { cards.focus(it.query["consume"]?.firstOrNull() == "1") }
            .post("focus") { cards.setFocus(it.body) }
            .stream("events") { r, sse ->
                val fields = r.query["fields"].orEmpty().joinToString(",").split(",").filter { FIELD.matches(it) }
                b.stream(fields.ifEmpty { null }, sse)
            }
            .get("schedules") { cards.schedules(it.query["project"]?.firstOrNull().orEmpty()) }
            .get("dreamfall/card/{id}") { cards.dreamfallDetail(it.params.getValue("id")) }
            .post("card/{id}/move") { cards.move(it.card(), it.body) }
            .post("card/{id}/start") { cards.start(it.card()) }
            .post("card/{id}/read") { cards.read(it.card(), it.body) }
        for (kind in listOf("card", "chat")) {
            routes
                .post(kind) { cards.create(kind, it.body, it.user) }
                .get("$kind/{id}") { r -> r.params.getValue("id").let { if (it.startsWith("c_")) cards.conversation(kind, it) else throw BadRequest("bad id") } }
                .post("$kind/{id}/send") { cards.send(kind, it.card(), it.body) }
                .post("$kind/{id}/update") { cards.update(kind, it.card(), it.body) }
                .post("$kind/{id}/cancel") { cards.stop(kind, it.card(), "cancel") }
                .post("$kind/{id}/archive") { cards.stop(kind, it.card(), "archive") }
        }
    }
    if (access == null) return routes
    val (ssh, claude, enrollment) = Triple(access.ssh, access.claude, access.enrollment)
    var followed: String? = null   // the enrollment whose success was followed up already
    return routes
        .get("ssh") { ssh.publicKey() }
        .post("ssh/test") { ssh.test(it.text("target")) }
        .get("claude/login") { claude.status() }
        .post("claude/login") { claude.start() }
        .post("claude/login/code") { JsonObject(claude.code(it.text("code")) + ("auth" to claude.auth())).also { a -> if (a["ok"].truthy()) afterRestart(probe = true) } }
        .post("claude/login/cancel") { claude.cancel(); ok }
        .post("claude/logout") { buildJsonObject { put("ok", true); put("auth", claude.logout()) }.also { afterRestart(probe = true) } }
        .get("gateway") {
            enrollment.gateway().also { g ->
                val enroll = g.obj("enroll")
                if (enroll?.obj("done")?.get("ok").truthy() && enroll?.field("started") != followed) {
                    followed = enroll?.field("started")
                    afterRestart(probe = false)
                }
            }
        }
        .post("gateway/enroll") { enrollment.enroll(it.body) }
        .post("gateway/enroll/cancel") { enrollment.cancel(); ok }
        .post("gateway/unenroll") { enrollment.unenroll().also { afterRestart(probe = false) } }
}

/** `/api/events?fields=`: the keys a subscriber asks for. */
private val FIELD = Regex("^[a-z_]{1,32}$")
