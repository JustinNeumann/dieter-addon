package com.getdieter.operator

import java.io.File
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
    val server = OperatorServer(InetSocketAddress(host, port), routes(dieter, settings, projects)).start()
    Log.line("listening on $host:${server.address.port}")
}

/** Every path the operator answers, under /api/ like the bridge's. */
fun routes(dieter: Dieter, settings: Settings, projects: Projects): Routes {
    val prompts = Prompts(dieter)
    return Routes()
        .get("prompts") { prompts.view() }
        .post("prompts") { prompts.update(it.body) }
        .get("settings") { settings.view() }
        .post("settings") { settings.update(it.body) }
        .post("boards/order") { settings.order(it.body) }
        .post("project") { projects.add(it.body) }
}
