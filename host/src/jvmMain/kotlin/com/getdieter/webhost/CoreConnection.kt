package com.getdieter.webhost

import com.getdieter.addon.core.AddonCore
import com.getdieter.addon.core.AddonFailure
import com.getdieter.addon.core.AddonSubscription
import com.getdieter.client.v1.Command
import com.getdieter.client.v1.Failure
import com.getdieter.client.v1.Reply
import com.getdieter.client.v1.Slice
import com.getdieter.client.v1.Update
import com.getdieter.core.CoreRuntime
import com.getdieter.core.client.ClientApi
import com.getdieter.core.client.ClientFailure
import com.getdieter.core.client.ClientSubscription
import com.getdieter.web.v1.AddonReplied
import com.getdieter.web.v1.AddonUpdated
import com.getdieter.web.v1.ClientFrame
import com.getdieter.web.v1.Hello
import com.getdieter.web.v1.HostFrame
import com.getdieter.web.v1.Pong
import com.getdieter.web.v1.Replied
import com.getdieter.web.v1.Updated
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * One browser tab: decodes [ClientFrame]s and runs them on the shared cores (Dieter's and the add-on's),
 * the way the Apple byte bridge does (failures travel inside the reply). Commands keep running when the
 * tab goes away (a half-sent message must not be cut off); observations end with the connection.
 */
internal class CoreConnection(
    private val api: ClientApi,
    private val runtime: CoreRuntime,
    private val addon: AddonCore,
    private val commands: CoroutineScope,
    private val ws: WebSocket,
    private val hello: Hello,
    private val foreground: Foreground,
) {
    private val subscriptions = ConcurrentHashMap<Long, ClientSubscription>()
    private val addonSubscriptions = ConcurrentHashMap<Long, AddonSubscription>()

    fun run() {
        send(HostFrame(hello = hello))
        try {
            while (true) {
                val bytes = ws.receive() ?: break
                val frame =
                    try {
                        ClientFrame.ADAPTER.decode(bytes)
                    } catch (error: Exception) {
                        Log.line("unreadable frame (${bytes.size} bytes): $error")
                        continue
                    }
                frame.dispatch?.let { dispatch(it.id, it.command ?: Command()) }
                frame.observe?.let { observe(it.id, it.slice, it.scope) }
                frame.unobserve?.let {
                    subscriptions.remove(it.id)?.let(::closeOnCore)
                    addonSubscriptions.remove(it.id)?.close()
                }
                frame.ping?.let { send(HostFrame(pong = Pong(it.id))) }
                frame.addon_dispatch?.let { addonDispatch(it.id, it.command ?: com.getdieter.addon.v1.Command()) }
                frame.addon_observe?.let { addonObserve(it.id, it.slice, it.scope) }
            }
        } finally {
            foreground.set(this, false)
            subscriptions.values.forEach(::closeOnCore)
            subscriptions.clear()
            addonSubscriptions.values.forEach { it.close() }
            addonSubscriptions.clear()
        }
    }

    private fun addonDispatch(id: Long, command: com.getdieter.addon.v1.Command) {
        commands.launch {
            val reply =
                try {
                    com.getdieter.addon.v1.Reply(result = addon.dispatch(command, hello.user_name))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AddonFailure) {
                    com.getdieter.addon.v1.Reply(failure = failure.failure)
                } catch (error: Throwable) {
                    Log.line("add-on command failed: $error")
                    com.getdieter.addon.v1.Reply(
                        failure = com.getdieter.addon.v1.Failure(com.getdieter.addon.v1.Failure.Kind.KIND_FAILED, "The add-on could not do that.")
                    )
                }
            send(HostFrame(addon_replied = AddonReplied(id, reply)))
        }
    }

    private fun addonObserve(id: Long, slice: com.getdieter.addon.v1.Slice, scope: String) {
        val subscription =
            try {
                addon.observe(slice, scope) { update -> send(HostFrame(addon_updated = AddonUpdated(id, update))) }
            } catch (failure: AddonFailure) {
                Log.line("add-on slice $slice: ${failure.message}")
                return
            }
        addonSubscriptions.put(id, subscription)?.close()
    }

    private fun dispatch(id: Long, command: Command) {
        commands.launch {
            val reply =
                try {
                    val shown = command.set_foreground
                    Reply(
                        result =
                            when {
                                // many tabs share this core: the host runs it while any of them is shown
                                shown != null -> foreground.set(this@CoreConnection, shown.foreground).let { com.getdieter.client.v1.Result(done = com.getdieter.client.v1.Done()) }
                                else -> api.dispatch(command)
                            }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ClientFailure) {
                    Reply(failure = failure.failure)
                } catch (error: Throwable) {
                    Reply(failure = Failure(Failure.Kind.KIND_INVALID, error.message ?: "The command failed."))
                }
            send(HostFrame(replied = Replied(id, reply)))
        }
    }

    /** On the core's thread, in order with unobserve, so a quick close never leaks a subscription. */
    private fun observe(id: Long, slice: Slice, scope: String) {
        val subscription =
            runBlocking(runtime.dispatcher) {
                try {
                    api.observe(slice, scope) { update -> send(HostFrame(updated = Updated(id, update))) }
                } catch (failure: ClientFailure) {
                    failed(id, slice, scope, failure.failure)
                } catch (invalid: IllegalArgumentException) {
                    failed(id, slice, scope, Failure(Failure.Kind.KIND_INVALID, invalid.message ?: "The slice could not be observed."))
                }
            }
        subscriptions.put(id, subscription)?.let(::closeOnCore)
    }

    private fun failed(id: Long, slice: Slice, scope: String, failure: Failure): ClientSubscription {
        send(HostFrame(updated = Updated(id, Update(slice = slice, scope = scope, sequence = 1, failure = failure))))
        return ClientSubscription {}
    }

    private fun closeOnCore(subscription: ClientSubscription) {
        runBlocking(runtime.dispatcher) { runCatching { subscription.close() } }
    }

    private fun send(frame: HostFrame) {
        if (!ws.closed) ws.send(HostFrame.ADAPTER.encode(frame))
    }
}
