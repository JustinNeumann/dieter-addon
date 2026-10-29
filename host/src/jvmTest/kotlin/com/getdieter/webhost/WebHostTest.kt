package com.getdieter.webhost

import com.getdieter.client.v1.BeginSignIn
import com.getdieter.client.v1.Command
import com.getdieter.client.v1.SetShowReasoning
import com.getdieter.client.v1.Slice
import com.getdieter.web.v1.ClientFrame
import com.getdieter.web.v1.Context
import com.getdieter.web.v1.Dispatch
import com.getdieter.web.v1.HostFrame
import com.getdieter.web.v1.Observe
import com.getdieter.web.v1.Ping
import com.getdieter.web.v1.Unobserve
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** The browser's side of host.proto against a real core (signed out, no network needed). */
class WebHostTest {
    private val secret = "test-secret"
    private lateinit var host: WebHostServer
    private var port = 0
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @BeforeTest
    fun start() {
        val state = Files.createTempDirectory("web-host").toFile()
        host = WebHostServer(state, emptyMap(), secret, "127.0.0.1", 0)
        port = host.start()
    }

    @AfterTest
    fun stop() {
        host.stop()
    }

    private class Client(val frames: LinkedBlockingQueue<HostFrame>, val failures: LinkedBlockingQueue<Response?>) {
        lateinit var socket: WebSocket

        fun send(frame: ClientFrame) = check(socket.send(ClientFrame.ADAPTER.encode(frame).toByteString()))

        fun next(predicate: (HostFrame) -> Boolean = { true }): HostFrame {
            while (true) {
                val frame = assertNotNull(frames.poll(10, TimeUnit.SECONDS), "no frame within 10 s")
                if (predicate(frame)) return frame
            }
        }
    }

    private fun connect(secret: String? = this.secret, path: String = "/next/core"): Client {
        val client = Client(LinkedBlockingQueue(), LinkedBlockingQueue())
        val request =
            Request.Builder().url("ws://127.0.0.1:$port$path").apply {
                secret?.let { header("X-Dieter-Web-Secret", it) }
                header("X-Remote-User-Display-Name", "Ada")
            }.build()
        client.socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        client.frames.put(HostFrame.ADAPTER.decode(bytes))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        client.failures.put(response ?: Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(599).message(t.toString()).build())
                    }
                },
            )
        return client
    }

    @Test
    fun helloDispatchObserveAndPing() {
        val client = connect()
        val hello = assertNotNull(client.next().hello)
        assertEquals("Ada", hello.user_name)
        assertTrue(hello.core_version.isNotEmpty())

        client.send(ClientFrame(observe = Observe(id = 7, slice = Slice.SLICE_SESSION)))
        val first = assertNotNull(client.next { it.updated?.id == 7L }.updated?.update)
        assertEquals(1L, first.sequence)
        assertEquals(false, assertNotNull(first.session).show_reasoning)

        client.send(ClientFrame(dispatch = Dispatch(id = 1, command = Command(set_show_reasoning = SetShowReasoning(show = true)))))
        // The reply and the update it causes may come in either order.
        var replied: com.getdieter.web.v1.Replied? = null
        var changed: com.getdieter.web.v1.Updated? = null
        while (replied == null || changed == null) {
            val frame = client.next()
            frame.replied?.let { replied = it }
            frame.updated?.takeIf { it.update?.session?.show_reasoning == true }?.let { changed = it }
        }
        assertEquals(1L, replied!!.id)
        assertNotNull(assertNotNull(replied!!.reply).result?.done)
        assertEquals(7L, changed!!.id)
        assertTrue(changed!!.update!!.sequence > 1)

        client.send(ClientFrame(ping = Ping(id = 3)))
        assertEquals(3L, client.next { it.pong != null }.pong!!.id)

        client.socket.close(1000, null)
    }

    @Test
    fun aFailingCommandRepliesWithItsFailure() {
        val client = connect()
        client.next { it.hello != null }
        client.send(ClientFrame(dispatch = Dispatch(id = 9, command = Command())))
        val reply = assertNotNull(client.next { it.replied != null }.replied).reply!!
        assertNull(reply.result)
        assertTrue(assertNotNull(reply.failure).message.isNotEmpty())
        client.socket.close(1000, null)
    }

    @Test
    fun unobserveStopsUpdates() {
        val client = connect()
        client.next { it.hello != null }
        client.send(ClientFrame(observe = Observe(id = 1, slice = Slice.SLICE_SESSION)))
        client.next { it.updated?.id == 1L }
        client.send(ClientFrame(unobserve = Unobserve(id = 1)))
        client.send(ClientFrame(dispatch = Dispatch(id = 2, command = Command(set_show_reasoning = SetShowReasoning(show = true)))))
        client.next { it.replied?.id == 2L }
        client.send(ClientFrame(ping = Ping(id = 4)))
        client.next { it.pong?.id == 4L }
        val late = client.frames.poll(500, TimeUnit.MILLISECONDS)
        assertNull(late?.updated, "update after unobserve: $late")
        client.socket.close(1000, null)
    }

    @Test
    fun theSecretIsRequired() {
        for (secret in listOf(null, "wrong")) {
            val failure = connect(secret).failures.poll(10, TimeUnit.SECONDS)
            assertEquals(403, failure?.code, "secret $secret")
        }
        assertEquals(404, connect(path = "/elsewhere").failures.poll(10, TimeUnit.SECONDS)?.code)
    }

    @Test
    fun signInStartsWithThePagesCallback() {
        val client = connect()
        client.next { it.hello != null }
        val callback = "https://ha.example/app/0000_dieter/next/signin"
        client.send(ClientFrame(context = Context(sign_in_callback = callback)))
        client.send(ClientFrame(dispatch = Dispatch(id = 1, command = Command(begin_sign_in = BeginSignIn("https://gateway.example")))))
        val reply = client.next { it.replied?.id == 1L }.replied!!.reply!!
        val url = assertNotNull(reply.result?.sign_in_started?.authorize_url, "reply: $reply")
        assertTrue(url.startsWith("https://gateway.example/auth/github/start?"), url)
        assertTrue("native_redirect_uri=" + java.net.URLEncoder.encode(callback, "UTF-8") in url, url)
        assertTrue("native_code_challenge=" in url, url)

        // an http gateway is refused by the core
        client.send(ClientFrame(dispatch = Dispatch(id = 2, command = Command(begin_sign_in = BeginSignIn("http://gateway.example")))))
        val refused = client.next { it.replied?.id == 2L }.replied!!.reply!!
        assertTrue(assertNotNull(refused.failure).message.contains("HTTPS"), refused.toString())
        client.socket.close(1000, null)
    }

    @Test
    fun signInWithoutAnAddressFailsClearly() {
        val client = connect()
        client.next { it.hello != null }
        client.send(ClientFrame(context = Context(sign_in_callback = "https://ha.example/somewhere?x=1")))
        client.send(ClientFrame(dispatch = Dispatch(id = 1, command = Command(begin_sign_in = BeginSignIn("https://gateway.example")))))
        val reply = client.next { it.replied?.id == 1L }.replied!!.reply!!
        assertEquals(com.getdieter.client.v1.Failure.Kind.KIND_INVALID, reply.failure?.kind)
        assertTrue(reply.failure!!.message.contains("through Home Assistant"), reply.failure!!.message)
        client.socket.close(1000, null)
    }

    @Test
    fun theCallbackRouteNeedsAnAttemptAndTheSecret() {
        fun get(secret: String?): Pair<Int, String> {
            val request = Request.Builder().url("http://127.0.0.1:$port/next/signin?code=abc&state=xyz")
                .apply { secret?.let { header("X-Dieter-Web-Secret", it) } }.build()
            return http.newCall(request).execute().use { it.code to it.body.string() }
        }
        assertEquals(403, get(null).first)
        val (status, body) = get(secret)
        assertEquals(200, status)
        assertTrue("Sign-in failed" in body && "No sign-in is in progress" in body, body)
    }
}
