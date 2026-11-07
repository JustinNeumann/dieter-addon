package com.getdieter.webhost

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.SetForeground
import com.getdieter.core.client.ClientApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The core keeps its gateway and machine streams open only while the app is in the foreground. Here
 * the "app" is every open tab: the core runs while at least one tab is shown and stops [grace] after
 * the last one was hidden or closed (switching tabs or locking the phone briefly does not reconnect).
 *
 * With [grace] [ALWAYS] (the add-on's default) the host holds a tab of its own from [start] on, so the
 * core stays connected and a page opens on live data instead of reconnecting first: the gateway and the
 * daemon are on the same machine, and Home Assistant reloads the panel's frame every time it is opened.
 */
internal class Foreground(
    private val api: ClientApi,
    private val scope: CoroutineScope,
    private val grace: Long = ALWAYS,
) {
    private val shown = mutableSetOf<Any>()
    private val lock = Mutex()
    private var running = false
    private var stop: Job? = null

    /** Connects the core now and keeps it connected, when [grace] is [ALWAYS]. */
    fun start() {
        if (grace >= 0) return
        Log.line("keeping the core connected while no tab is shown (DIETER_WEB_HOST_IDLE_MS sets a grace instead)")
        set(this, true)
    }

    fun set(tab: Any, visible: Boolean) {
        val any = synchronized(shown) {
            if (visible) shown.add(tab) else shown.remove(tab)
            shown.isNotEmpty()
        }
        scope.launch {
            lock.withLock {
                if (any) {
                    stop?.cancel()
                    stop = null
                    if (!running) apply(true)
                } else if (running && stop == null) {
                    stop = scope.launch {
                        delay(grace.coerceAtLeast(0))
                        lock.withLock {
                            if (synchronized(shown) { shown.isEmpty() } && running) apply(false)
                            stop = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun apply(active: Boolean) {
        val began = System.nanoTime()
        runCatching { api.dispatch(Command(set_foreground = SetForeground(active))) }
            .onFailure { Log.line("foreground $active failed: $it") }
        running = active
        val ms = (System.nanoTime() - began) / 1_000_000
        Log.line(if (active) "core connecting (took $ms ms)" else "no tab shown: disconnected")
    }

    companion object {
        /** A [grace] that never stops the core. */
        const val ALWAYS = -1L
    }
}
