package com.getdieter.webhost

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.Failure
import com.getdieter.client.v1.Reply
import com.getdieter.client.v1.Slice
import com.getdieter.client.v1.Update
import com.getdieter.core.CoreRuntime
import com.getdieter.core.client.ClientApi
import com.getdieter.core.client.ClientFailure
import com.getdieter.core.client.ClientSubscription
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
 * One browser tab: decodes [ClientFrame]s and runs them on the shared core, the way the Apple byte
 * bridge does (failures travel inside the reply). Commands keep running when the tab goes away (a
 * half-sent message must not be cut off); observations end with the connection.
 */
internal class CoreConnection(
    private val api: ClientApi,
    private val runtime: CoreRuntime,
    private val commands: CoroutineScope,
    private val ws: WebSocket,
    private val hello: Hello,
    private val signIn: SignInRelay,
    private val foreground: Foreground,
) {
    private val subscriptions = ConcurrentHashMap<Long, ClientSubscription>()
    @Volatile private var pageCallback: String? = null

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
                frame.context?.let { pageCallback = it.sign_in_callback.ifBlank { null } }
                frame.dispatch?.let { dispatch(it.id, it.command ?: Command()) }
                frame.observe?.let { observe(it.id, it.slice, it.scope) }
                frame.unobserve?.let { subscriptions.remove(it.id)?.let(::closeOnCore) }
                frame.ping?.let { send(HostFrame(pong = Pong(it.id))) }
            }
        } finally {
            foreground.set(this, false)
            subscriptions.values.forEach(::closeOnCore)
            subscriptions.clear()
        }
    }

    private fun dispatch(id: Long, command: Command) {
        commands.launch {
            val reply =
                try {
                    val begin = command.begin_sign_in
                    val shown = command.set_foreground
                    Reply(
                        result =
                            when {
                                begin != null -> signIn.begin(begin.gateway_url, pageCallback)
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
