package com.getdieter.web

import com.getdieter.addon.AddonClient
import com.getdieter.addon.core.AddonFailure
import com.getdieter.addon.core.AddonSubscription
import com.getdieter.client.v1.Command
import com.getdieter.client.v1.Failure
import com.getdieter.client.v1.Reply
import com.getdieter.client.v1.Result
import com.getdieter.client.v1.SessionSlice
import com.getdieter.client.v1.Slice
import com.getdieter.client.v1.Tone
import com.getdieter.client.v1.Update
import com.getdieter.core.client.ClientFailure
import com.getdieter.core.client.ClientSubscription
import com.getdieter.mobile.store.MobileCore
import com.getdieter.web.v1.AddonDispatch
import com.getdieter.web.v1.AddonObserve
import com.getdieter.web.v1.ClientFrame
import com.getdieter.web.v1.Dispatch
import com.getdieter.web.v1.Hello
import com.getdieter.web.v1.HostFrame
import com.getdieter.web.v1.Observe
import com.getdieter.web.v1.Ping
import com.getdieter.web.v1.Unobserve
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The UI's core, running in the add-on's web host and reached over one WebSocket
 * (proto/dieter/web/v1/host.proto), and on the same socket the add-on core ([AddonClient]) for the
 * add-on's own features. The browser keeps no account state of its own: sign-in, device key and
 * caches live in the host, so every browser and the Companion app see the same session.
 *
 * Reconnects with backoff (and at once when the page becomes visible again), observes every live
 * slice again after a reconnect (the host answers with a fresh snapshot), and while the socket is
 * down shows the session as reconnecting. Commands in flight when the socket drops fail as
 * transient: the host keeps running them, so the action may still have happened.
 *
 * Wasm runs on one thread: socket callbacks and coroutines share the event loop, no locking.
 */
class RemoteCore(private val url: String, private val scope: CoroutineScope) : MobileCore, AddonClient {
    private class Observer(val slice: Slice, val scope: String, val receive: (Update) -> Unit) {
        var session: SessionSlice? = null
    }

    private class AddonObserver(
        val slice: com.getdieter.addon.v1.Slice,
        val scope: String,
        val receive: (com.getdieter.addon.v1.Update) -> Unit,
    )

    private var socket: JsAny? = null
    private var open = false
    private var nextId = 1L
    private val pending = mutableMapOf<Long, CompletableDeferred<Reply>>()
    private val observers = mutableMapOf<Long, Observer>()
    private val addonPending = mutableMapOf<Long, CompletableDeferred<com.getdieter.addon.v1.Reply>>()
    private val addonObservers = mutableMapOf<Long, AddonObserver>()
    private val connected = MutableStateFlow(false)
    private val mutableHello = MutableStateFlow<Hello?>(null)
    private var failures = 0
    private var retry: Job? = null
    private var lastPong = 0.0
    private var started = false
    /** The tab's last SetForeground: a new connection starts as not shown, so it is sent again. */
    private var foreground: Command? = null

    /** The host's greeting on the current connection; null while disconnected. */
    val hello: StateFlow<Hello?> = mutableHello

    fun start() {
        if (started) return
        started = true
        connect()
        onPageVisible { if (!open && socket == null) reconnectNow() }
        scope.launch {
            while (true) {
                delay(KEEPALIVE_MS)
                if (!open) continue
                if (lastPong > 0 && nowMs() - lastPong > KEEPALIVE_MS + PONG_GRACE_MS) {
                    socket?.let(::wsClose) // a silent proxy: start over
                    continue
                }
                send(ClientFrame(ping = Ping(nextId++)))
            }
        }
    }

    override suspend fun dispatch(command: Command): Result {
        if (!open && withTimeoutOrNull(CONNECT_WAIT_MS) { connected.first { it } } == null)
            throw ClientFailure(
                Failure(Failure.Kind.KIND_TRANSIENT, "Not connected to the Dieter add-on. Dieter keeps trying.")
            )
        if (command.set_foreground != null) foreground = command
        val id = nextId++
        val reply = CompletableDeferred<Reply>()
        pending[id] = reply
        // where the gateway sends this browser back after GitHub sign-in: the page's own address (the core decides)
        val sent = command.begin_sign_in?.let { command.copy(begin_sign_in = it.copy(redirect_uri = hostSignInCallback())) } ?: command
        val answer =
            try {
                send(ClientFrame(dispatch = Dispatch(id, sent)))
                reply.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                pending.remove(id)
            }
        answer.failure?.let { throw ClientFailure(it) }
        return answer.result ?: Result()
    }

    override fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): ClientSubscription {
        val id = nextId++
        observers[id] = Observer(slice, scope, receive)
        if (open) send(ClientFrame(observe = Observe(id, slice, scope)))
        else if (slice == Slice.SLICE_SESSION) this.scope.launch { synthesizeSession(id) }
        return ClientSubscription {
            if (observers.remove(id) != null && open) send(ClientFrame(unobserve = Unobserve(id)))
        }
    }

    override suspend fun dispatch(command: com.getdieter.addon.v1.Command): com.getdieter.addon.v1.Result {
        if (!open && withTimeoutOrNull(CONNECT_WAIT_MS) { connected.first { it } } == null)
            throw AddonFailure(addonUnreachable("Not connected to the Dieter add-on. Dieter keeps trying."))
        val id = nextId++
        val reply = CompletableDeferred<com.getdieter.addon.v1.Reply>()
        addonPending[id] = reply
        val answer =
            try {
                send(ClientFrame(addon_dispatch = AddonDispatch(id, command)))
                reply.await()
            } finally {
                addonPending.remove(id)
            }
        answer.failure?.let { throw AddonFailure(it) }
        return answer.result ?: com.getdieter.addon.v1.Result()
    }

    override fun observe(
        slice: com.getdieter.addon.v1.Slice,
        scope: String,
        receive: (com.getdieter.addon.v1.Update) -> Unit,
    ): AddonSubscription {
        val id = nextId++
        addonObservers[id] = AddonObserver(slice, scope, receive)
        if (open) send(ClientFrame(addon_observe = AddonObserve(id, slice, scope)))
        return AddonSubscription {
            if (addonObservers.remove(id) != null && open) send(ClientFrame(unobserve = Unobserve(id)))
        }
    }

    private fun connect() {
        retry = null
        socket = wsOpen(url, ::opened, ::received, ::closed)
    }

    private fun reconnectNow() {
        retry?.cancel()
        connect()
    }

    private fun opened() {
        open = true
        failures = 0
        lastPong = nowMs()
        foreground?.let { send(ClientFrame(dispatch = Dispatch(nextId++, it))) }   // reply ignored
        observers.forEach { (id, o) -> send(ClientFrame(observe = Observe(id, o.slice, o.scope))) }
        addonObservers.forEach { (id, o) -> send(ClientFrame(addon_observe = AddonObserve(id, o.slice, o.scope))) }
        connected.value = true
    }

    private fun received(data: String) {
        val frame =
            try {
                HostFrame.ADAPTER.decode(data.latin1Bytes())
            } catch (error: Exception) {
                consoleWarn("dieter: unreadable frame from the web host: $error")
                return
            }
        frame.hello?.let { mutableHello.value = it }
        frame.replied?.let { replied -> pending.remove(replied.id)?.complete(replied.reply ?: Reply()) }
        frame.updated?.let { updated ->
            val observer = observers[updated.id] ?: return@let
            val update = updated.update ?: return@let
            update.session?.let { observer.session = it }
            observer.receive(update)
        }
        frame.addon_replied?.let { replied ->
            addonPending.remove(replied.id)?.complete(replied.reply ?: com.getdieter.addon.v1.Reply())
        }
        frame.addon_updated?.let { updated ->
            val update = updated.update ?: return@let
            addonObservers[updated.id]?.receive?.invoke(update)
        }
        if (frame.pong != null) lastPong = nowMs()
    }

    private fun closed(code: Int) {
        val wasOpen = open
        open = false
        socket = null
        connected.value = false
        mutableHello.value = null
        val dropped = Reply(failure = Failure(Failure.Kind.KIND_TRANSIENT, DROPPED))
        pending.values.toList().forEach { it.complete(dropped) }
        pending.clear()
        val addonDropped = com.getdieter.addon.v1.Reply(failure = addonUnreachable(DROPPED))
        addonPending.values.toList().forEach { it.complete(addonDropped) }
        addonPending.clear()
        if (wasOpen || failures == 0) observers.keys.toList().forEach(::synthesizeSession)
        failures++
        val wait = minOf(MAX_BACKOFF_MS, 500L shl minOf(failures - 1, 5))
        retry = scope.launch {
            delay(wait)
            if (socket == null) connect()
        }
        if (code != 1000) consoleWarn("dieter: web host connection closed ($code), retrying in ${wait} ms")
    }

    /** What the session shows while the add-on's host is out of reach: reconnecting, not signed out. */
    private fun synthesizeSession(id: Long) {
        val observer = observers[id] ?: return
        if (observer.slice != Slice.SLICE_SESSION) return
        val last = observer.session
        // A signed-out session stays signed out (the sign-in screen), anything else reads as reconnecting.
        if (last?.phase == SessionSlice.Phase.PHASE_AUTH_REQUIRED) return
        val shown =
            (last ?: SessionSlice()).copy(
                phase = SessionSlice.Phase.PHASE_RECONNECTING,
                phase_label = "Connecting to the add-on",
                tone = Tone.TONE_WARNING,
                synced = false,
            )
        observer.receive(Update(slice = Slice.SLICE_SESSION, session = shown))
    }

    private fun addonUnreachable(message: String) =
        com.getdieter.addon.v1.Failure(com.getdieter.addon.v1.Failure.Kind.KIND_UNREACHABLE, message)

    private fun send(frame: ClientFrame) {
        val ws = socket ?: return
        if (!wsSend(ws, ClientFrame.ADAPTER.encode(frame).latin1String())) consoleWarn("dieter: frame not sent")
    }

    companion object {
        private const val KEEPALIVE_MS = 25_000L
        private const val PONG_GRACE_MS = 10_000L
        private const val CONNECT_WAIT_MS = 15_000L
        private const val MAX_BACKOFF_MS = 15_000L
        const val DROPPED =
            "The connection to the Dieter add-on dropped. The action may still have happened; check before repeating it."
    }
}

// Bytes cross the JS boundary as strings of char codes 0–255 (cheaper than element-wise array access).
internal fun String.latin1Bytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

internal fun ByteArray.latin1String(): String = CharArray(size) { (this[it].toInt() and 0xFF).toChar() }.concatToString()

@JsFun(
    """(path, onOpen, onMessage, onClose) => {
  const u = new URL(path, window.location.href);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.search = ''; u.hash = '';
  const ws = new WebSocket(u.href);
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => onOpen();
  ws.onmessage = (e) => {
    if (typeof e.data === 'string') return;
    const a = new Uint8Array(e.data);
    let s = '';
    for (let i = 0; i < a.length; i += 8192) s += String.fromCharCode.apply(null, a.subarray(i, i + 8192));
    onMessage(s);
  };
  let closed = false;
  ws.onclose = (e) => { if (!closed) { closed = true; onClose(e.code); } };
  ws.onerror = () => {};
  return ws;
}"""
)
private external fun wsOpen(path: String, onOpen: () -> Unit, onMessage: (String) -> Unit, onClose: (Int) -> Unit): JsAny

@JsFun(
    """(ws, s) => {
  if (ws.readyState !== 1) return false;
  const a = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
  ws.send(a);
  return true;
}"""
)
private external fun wsSend(ws: JsAny, data: String): Boolean

@JsFun("(ws) => { try { ws.close(4000, 'keepalive') } catch (e) {} }")
private external fun wsClose(ws: JsAny)

@JsFun("(cb) => document.addEventListener('visibilitychange', () => { if (!document.hidden) cb() })")
private external fun onPageVisible(callback: () -> Unit)

@JsFun("() => (window.dieterHost && window.dieterHost.signInCallback) ? window.dieterHost.signInCallback() : ''")
private external fun hostSignInCallback(): String

@JsFun("() => performance.now()")
private external fun nowMs(): Double

@JsFun("(m) => console.warn(m)")
internal external fun consoleWarn(message: String)
