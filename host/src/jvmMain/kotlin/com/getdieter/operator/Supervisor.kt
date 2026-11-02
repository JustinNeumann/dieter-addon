package com.getdieter.operator

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `dieter daemon start` in the foreground, started again when it exits; [restart] stops it so the loop starts it with
 * fresh credentials. Before each start [beforeStart] runs (the env file is rewritten) and [command] and [environment]
 * are read again.
 */
class DaemonSupervisor(
    private val bin: String,
    private val command: () -> List<String>,
    private val environment: () -> Map<String, String>,
    private val beforeStart: () -> Unit = {},
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
    private val now: () -> Long = System::currentTimeMillis,
    private val start: (ProcessBuilder) -> Process = { it.start() },
    private val store: File? = null,
    private val userHome: File? = null,
) {
    @Volatile private var process: Process? = null

    /** Stops the running daemon; the loop starts it again. False when none runs (the loop is waiting to start one). */
    fun restart(): Boolean {
        val running = process?.takeIf { it.isAlive } ?: return false
        Log.line("daemon: restarting (credentials changed)")
        running.destroy()
        return true
    }

    /** Runs the daemon until [rounds] starts are used up (forever by default). */
    fun loop(rounds: Int = Int.MAX_VALUE) {
        var backoff = 2
        repeat(rounds) {
            runCatching(beforeStart).onFailure { Log.line("env: ${it.message}") }
            val args = command()
            Log.line("daemon: starting (${args.drop(1).joinToString(" ")})")
            val started = now()
            val result = runCatching {
                val builder = ProcessBuilder(args)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                builder.environment().putAll(environment())
                val p = start(builder)
                process = p
                p.waitFor().toString()
            }.getOrElse { it.toString() }
            process = null
            runCatching { reapOrphans() }.onFailure { Log.line("daemon: orphan cleanup failed: ${it.message}") }
            val ran = (now() - started) / 1000
            backoff = if (ran > 60) 2 else minOf(backoff * 2, 60)
            Log.line("daemon: exited ($result) after ${ran}s, restart in ${backoff}s")
            sleep(backoff.seconds)
        }
    }

    /**
     * Daemons this user still runs from an earlier operator (it restarted; its child outlived it): stopped, so the
     * loop's daemon gets the store and the port. True when one was found.
     */
    fun stopStrays(): Boolean {
        val me = ProcessHandle.current()
        val path = runCatching { File(bin).canonicalPath }.getOrDefault(bin)
        val strays = ProcessHandle.allProcesses().filter { h ->
            val info = h.info()
            h.pid() != me.pid() && info.user() == me.info().user() &&
                info.command().map { runCatching { File(it).canonicalPath }.getOrDefault(it) == path }.orElse(false) &&
                info.arguments().map { isDaemonStart(it.toList()) }.orElse(false)
        }.toList()
        for (stray in strays) {
            Log.line("daemon: stopping the daemon an earlier operator left running (pid ${stray.pid()})")
            stray.destroy()
            // the daemon takes ~20 s to wind down its workers; only one that hangs is killed
            if (runCatching { stray.onExit().get(45, TimeUnit.SECONDS) }.isFailure) stray.destroyForcibly()
        }
        runCatching { reapOrphans() }.onFailure { Log.line("daemon: orphan cleanup failed: ${it.message}") }
        return strays.isNotEmpty()
    }

    /**
     * Reaps orphaned harness workers, provider bridges (`bridge.mjs`), MCP proxies (`antigravity-host-tools.mjs`), and
     * headless agent CLIs (`agy`, `claude`, `codex`) left behind when the daemon exited abnormally (e.g. SIGKILL / OOM
     * exit 137). Signals both recorded worker/bridge process groups (`-pid` from `.dieter-worker-*.pid` and
     * `bridge-meta.json`, which catches children whose group leader already exited on EPIPE) and matching live process
     * trees scoped to [store] and [userHome].
     */
    fun reapOrphans(): Int {
        if (store == null && userHome == null) return 0
        val me = ProcessHandle.current()
        val myAncestors = generateSequence(me) { it.parent().orElse(null) }.map { it.pid() }.toSet()
        val storePath = store?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val homePath = userHome?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val isContainerData = homePath == "/data" || storePath == "/data/dieter"

        val pidFiles = mutableListOf<File>()
        val bridgeMetaFiles = mutableListOf<File>()
        if (store != null) {
            File(store, "runtime/sessions").listFiles()?.forEach { projDir ->
                projDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(".dieter-worker-") && f.name.endsWith(".pid")) pidFiles += f
                }
                File(projDir, ".agent-runs").listFiles()?.forEach { runDir ->
                    val meta = File(runDir, "bridge/bridge-meta.json")
                    if (meta.isFile) bridgeMetaFiles += meta
                }
            }
        }
        if (userHome != null) {
            File(userHome, ".ai-sdk-harness/.agent-runs").listFiles()?.forEach { runDir ->
                val meta = File(runDir, "bridge/bridge-meta.json")
                if (meta.isFile) bridgeMetaFiles += meta
            }
            File(userHome, ".ai-sdk/harness-acp").listFiles()?.forEach { providerDir ->
                providerDir.listFiles()?.forEach { keyDir ->
                    val meta = File(keyDir, "bridge/bridge-meta.json")
                    if (meta.isFile) bridgeMetaFiles += meta
                }
            }
        }

        val recordedPids = linkedSetOf<Long>()
        for (file in pidFiles + bridgeMetaFiles) {
            val text = runCatching { file.readText() }.getOrDefault("")
            PID_FIELD.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 1L }?.let { recordedPids += it }
        }

        val handles = linkedMapOf<Long, ProcessHandle>()
        fun addWithDescendants(h: ProcessHandle) {
            if (h.pid() in myAncestors || !h.isAlive) return
            handles[h.pid()] = h
            h.descendants().forEach { d ->
                if (d.pid() !in myAncestors && d.isAlive) handles[d.pid()] = d
            }
        }

        for (pid in recordedPids) {
            ProcessHandle.of(pid).ifPresent { h ->
                if (h.info().user() == me.info().user()) {
                    // If the bridge was spawned by `/bin/sh -lc ... --bridge-state-dir`, include that shell group leader too
                    h.parent().ifPresent { p ->
                        val pArgs = p.info().arguments().map { it.toList() }.orElse(emptyList())
                        if (p.pid() !in myAncestors && p.info().user() == me.info().user() && pArgs.any { "--bridge-state-dir" in it }) {
                            addWithDescendants(p)
                        }
                    }
                    addWithDescendants(h)
                }
            }
        }

        ProcessHandle.allProcesses().forEach { h ->
            val info = h.info()
            if (h.pid() in myAncestors || info.user() != me.info().user()) return@forEach
            val args = info.arguments().map { it.toList() }.orElse(emptyList())
            val isInitChild = h.parent().map { it.pid() == 1L }.orElse(true)
            if (isOrphanWorker(args, storePath, homePath, isContainerData && isInitChild)) {
                addWithDescendants(h)
            }
        }

        val activePgids = recordedPids.filter { it !in myAncestors && processGroupAlive(it) }
        if (handles.isEmpty() && activePgids.isEmpty()) {
            bridgeMetaFiles.forEach { it.delete() }
            return 0
        }

        val summaryPids = (handles.keys + activePgids).sorted()
        Log.line("daemon: stopping ${summaryPids.size} orphaned worker process(es)/group(s) (pids ${summaryPids.joinToString(", ")})")

        activePgids.forEach { signalProcessGroup(it, "TERM") }
        handles.values.forEach { runCatching { it.destroy() } }

        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (handles.values.none { it.isAlive } && activePgids.none { processGroupAlive(it) }) break
            Thread.sleep(50)
        }

        activePgids.filter { processGroupAlive(it) }.forEach { signalProcessGroup(it, "KILL") }
        handles.values.filter { it.isAlive }.forEach {
            runCatching { it.destroyForcibly() }
            runCatching { it.onExit().get(1, TimeUnit.SECONDS) }
        }
        bridgeMetaFiles.forEach { it.delete() }
        return summaryPids.size
    }

    companion object {
        private val PID_FIELD = Regex(""""pid"\s*:\s*(\d+)""")

        /** `… daemon start …`, the arguments after the program. */
        fun isDaemonStart(args: List<String>) = args.windowed(2).any { it == listOf("daemon", "start") }

        /** True when [args] belong to a harness worker, provider bridge, MCP proxy, or headless stream-json agent CLI. */
        fun isWorkerOrAgentArgs(args: List<String>): Boolean =
            args.any { it.startsWith("--board-worker-token=") } ||
                args.any { "--bridge-state-dir" in it } ||
                args.any { it == "antigravity-host-tools.mjs" || it.endsWith("/antigravity-host-tools.mjs") } ||
                args.windowed(2).any { it == listOf("--output-format", "stream-json") }

        fun isOrphanWorker(args: List<String>, storePath: String?, homePath: String?, isContainerOrphan: Boolean): Boolean {
            if (!isWorkerOrAgentArgs(args)) return false
            if (isContainerOrphan) return true
            return args.any { arg ->
                (storePath != null && storePath.isNotEmpty() && storePath in arg) ||
                    (homePath != null && homePath.isNotEmpty() && homePath in arg)
            }
        }

        private fun processGroupAlive(pgid: Long): Boolean =
            runCatching {
                val p = ProcessBuilder("kill", "-0", "--", "-$pgid")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)

        private fun signalProcessGroup(pgid: Long, signal: String) {
            runCatching {
                ProcessBuilder("kill", "-$signal", "--", "-$pgid")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(2, TimeUnit.SECONDS)
            }
        }
    }
}
