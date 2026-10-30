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
 */
internal class Foreground(
    private val api: ClientApi,
    private val scope: CoroutineScope,
    private val grace: Long = 120_000,
) {
    private val shown = mutableSetOf<Any>()
    private val lock = Mutex()
    private var running = false
    private var stop: Job? = null

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
                        delay(grace)
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
        runCatching { api.dispatch(Command(set_foreground = SetForeground(active))) }
            .onFailure { Log.line("foreground $active failed: $it") }
        running = active
        Log.line(if (active) "a tab is shown: connecting" else "no tab shown: disconnected")
    }
}
