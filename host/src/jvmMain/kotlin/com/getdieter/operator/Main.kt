package com.getdieter.operator

import java.net.InetSocketAddress

/**
 * The add-on's operator: the half of the Python bridge already moved to Kotlin, one area per release
 * (docs/PLAN.md, Step 2). It runs as the add-on's user `dieter`, like the bridge and the agents, and does the
 * daemon's operator work through the `dieter` CLI. It holds no secret: everything it can do, that user can do
 * anyway. The gateway session stays in the web host (user dieter-web). Until it takes over port 8099 the bridge
 * forwards the moved paths to it. Environment:
 *   DIETER_OPERATOR_ADDR   loopback address to listen on, default 127.0.0.1:8096
 *   DIETER_BIN             the Dieter CLI, default /usr/local/bin/dieter (DIETER_HOME and the daemon address
 *                          come from the environment, as for the bridge)
 */
fun main() {
    val env = System.getenv()
    val (host, port) = (env["DIETER_OPERATOR_ADDR"] ?: "127.0.0.1:8096").let { it.substringBeforeLast(":") to it.substringAfterLast(":").toInt() }
    val dieter = ProcessDieter(env["DIETER_BIN"] ?: "/usr/local/bin/dieter")
    val server = OperatorServer(InetSocketAddress(host, port), routes(dieter)).start()
    Log.line("listening on $host:${server.address.port}")
}

/** Every path the operator answers, under /api/ like the bridge's. */
fun routes(dieter: Dieter): Routes {
    val prompts = Prompts(dieter)
    return Routes()
        .get("prompts") { prompts.view() }
        .post("prompts") { prompts.update(it.body) }
}
