package com.getdieter.operator

import java.io.File
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
 *   DIETER_DAEMON_ADDR     the daemon's API, default 127.0.0.1:4242; BRIDGE_PORT  the bridge's port, default 8099
 * In the add-on it also starts the daemon and keeps it running, after staging the harness runtime and writing what
 * the options configure (Setup).
 */
fun main() {
    val env = System.getenv()
    val (host, port) = (env["DIETER_OPERATOR_ADDR"] ?: "127.0.0.1:8096").let { it.substringBeforeLast(":") to it.substringAfterLast(":").toInt() }
    val dieter = ProcessDieter(env["DIETER_BIN"] ?: "/usr/local/bin/dieter")
    val home = File(env["DIETER_HOME"] ?: "/data/dieter")
    val settings = Settings(File(env["BRIDGE_SETTINGS_FILE"] ?: "/data/bridge_settings.json"), OllamaEntity(HomeAssistant.supervisor(env))) {
        Settings.localDaemonId(home)
    }
    val optionsFile = File(env["DIETER_OPTIONS_FILE"] ?: "/data/options.json")
    val options = {
        if (!optionsFile.exists()) null
        else runCatching { Json.parseToJsonElement(optionsFile.readText()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }
    val projects = Projects(dieter, File(env["DIETER_PROJECTS_DIR"] ?: "/data/projects"), options)
    val supervision = options()?.let { Supervision(it, env, home) }
    val server = OperatorServer(InetSocketAddress(host, port), routes(dieter, settings, projects, supervision?.daemon)).start()
    Log.line("listening on $host:${server.address.port}")
    supervision?.let { Thread.ofPlatform().name("supervisor").start(it::run) }
}

/** The add-on's start: the setup the options ask for, the harness runtime, then the daemon for good. */
class Supervision(options: JsonObject, env: Map<String, String>, home: File) {
    private val setup = Setup(options, File(env["HOME"] ?: "/data"), env["SUPERVISOR_TOKEN"])
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

    companion object {
        /**
         * What the daemon gets on top of the operator's environment: harness workers relay the agents' questions to
         * the bridge, and Home Assistant for the agents (hass-cli takes HASS_SERVER/HASS_TOKEN: core's REST API
         * through the Supervisor's proxy; the token reaches only core, hassio_api is off). The daemon forwards only
         * whitelisted variables to its workers, DIETER_HARNESS_ENV names the extra ones. The env file's variables go
         * into the daemon's own environment too, so harness processes inherit the tokens either way.
         */
        fun daemonEnvironment(env: Map<String, String>, runtimeDir: File?, envFile: Map<String, String>): Map<String, String> {
            val out = linkedMapOf("DIETER_QUESTIONS_URL" to "http://127.0.0.1:${env["BRIDGE_PORT"] ?: "8099"}/api/questions")
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

/** Every path the operator answers, under /api/ like the bridge's. */
fun routes(dieter: Dieter, settings: Settings, projects: Projects, daemon: DaemonSupervisor? = null): Routes {
    val prompts = Prompts(dieter)
    return Routes()
        .get("prompts") { prompts.view() }
        .post("prompts") { prompts.update(it.body) }
        .get("settings") { settings.view() }
        .post("settings") { settings.update(it.body) }
        .post("boards/order") { settings.order(it.body) }
        .post("project") { projects.add(it.body) }
        .post("daemon/restart") {
            // after a Claude sign-in or a gateway enrollment (the bridge): the daemon starts again with them
            val restarted = (daemon ?: throw BadRequest("the daemon is supervised in the add-on only")).restart()
            buildJsonObject { put("ok", true); put("restarted", restarted) }
        }
}
