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
        return strays.isNotEmpty()
    }

    companion object {
        /** `… daemon start …`, the arguments after the program. */
        fun isDaemonStart(args: List<String>) = args.windowed(2).any { it == listOf("daemon", "start") }
    }
}
