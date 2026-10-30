package com.getdieter.webhost

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** The add-on's front door (bridge/dieter_bridge.py until 0.6.431.23): the page's files, access, the operator's API. */
class FrontDoorTest {
    private val web = Files.createTempDirectory("web").toFile().apply {
        File(this, "index.html").writeText("<!doctype html>page")
        File(this, "dieter.0123456789abcdef.js").writeText("js")
        File(this, "dieter.0123456789abcdef.js.br").writeText("br")
        File(this, "dieter.0123456789abcdef.js.gz").writeText("gz")
        File(this, "0123456789abcdef0123.wasm.gz").writeText("wasm")
    }
    private val secretFile = File(web.parentFile, "outside-${web.name}.txt").apply { writeText("not for the page") }

    /** The operator's stand-in: echoes what it got, and an event stream that stays open. */
    private val seen = LinkedBlockingQueue<String>()
    private val operator = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            seen += "${ex.requestMethod} ${ex.requestURI} user=${ex.requestHeaders.getFirst("X-Remote-User-Display-Name")} " +
                "file=${ex.requestHeaders.getFirst("X-Filename")} type=${ex.requestHeaders.getFirst("Content-Type")} body=$body"
            if (ex.requestURI.path == "/api/events") {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.write("event: state\ndata: {\"rev\":1}\n\n".toByteArray())
                ex.responseBody.flush()
                Thread.sleep(30_000)
                ex.close()
                return@createContext
            }
            val answer = if (ex.requestURI.path == "/api/missing") 404 to """{"error":"not found"}""" else 200 to """{"ok":true}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(answer.first, answer.second.length.toLong())
            ex.responseBody.write(answer.second.toByteArray())
            ex.close()
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }

    @Volatile private var allowed = true
    @Volatile private var ingress = false
    private val door = FrontDoor(
        "127.0.0.1", 0, web, java.net.URI.create("http://127.0.0.1:${operator.address.port}/"), { allowed }, { ingress },
        onSignIn = { query -> HttpReply(200, "text/html; charset=utf-8", "signed in with $query") },
    ) { ws -> ws.send("core".toByteArray()) }
    private val port = door.start()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()

    @AfterTest
    fun stop() {
        door.stop()
        operator.stop(0)
        secretFile.delete()
    }

    private fun get(path: String, vararg headers: Pair<String, String>): Response =
        http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()

    @Test
    fun thePagesFiles() {
        get("/next/").use {
            assertEquals(200, it.code)
            assertEquals("no-cache", it.header("Cache-Control"))
            assertEquals("<!doctype html>page", it.body.string())
            val etag = it.header("ETag")!!
            get("/next/", "If-None-Match" to etag).use { again -> assertEquals(304, again.code) }
        }
        get("/next/dieter.0123456789abcdef.js", "Accept-Encoding" to "gzip, br").use {
            assertEquals("public, max-age=31536000, immutable", it.header("Cache-Control"))
            assertEquals("br", it.header("Content-Encoding"))
        }
        // behind Ingress (it drops Accept-Encoding and compresses itself) the plain file
        get("/next/dieter.0123456789abcdef.js", "Accept-Encoding" to "br", "X-Ingress-Path" to "/api/hassio_ingress/t").use {
            assertNull(it.header("Content-Encoding"))
            assertEquals("text/javascript; charset=utf-8", it.header("Content-Type"))
        }
        get("/next/0123456789abcdef0123.wasm.gz").use { assertEquals("application/octet-stream", it.header("Content-Type")) }
        get("/next?route=x").use { assertEquals(302 to "next/?route=x", it.code to it.header("Location")) }
        // a client that does not resolve ".." itself (OkHttp would)
        assertEquals("HTTP/1.1 404 Not Found", raw("GET /next/../outside-${web.name}.txt HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals("HTTP/1.1 404 Not Found", raw("GET /next/%2e%2e/outside-${web.name}.txt HTTP/1.1\r\nHost: x\r\n\r\n"))
        get("/").use { assertTrue("next/" in it.body.string() && it.header("Content-Type")!!.startsWith("text/html")) }
        get("/elsewhere").use { assertEquals(404, it.code) }
        assertEquals(setOf("br", "gzip"), FrontDoor.acceptedEncodings("gzip;q=0.5, br, deflate;q=0"))
    }

    @Test
    fun accessAndThePagesCoreFromIngressOnly() {
        allowed = false
        get("/api/state").use { assertEquals(403, it.code) }
        allowed = true
        get("/next/signin?code=a").use { assertEquals(403, it.code) }   // e.g. an agent on loopback
        val frames = LinkedBlockingQueue<String>()
        fun core() = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/next/core").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString) { frames += bytes.utf8() }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) { frames += "failed ${response?.code}" }
        })
        core()
        assertEquals("failed 403", frames.poll(10, TimeUnit.SECONDS))
        ingress = true
        core()
        assertEquals("core", frames.poll(10, TimeUnit.SECONDS))
        get("/next/signin?code=a&state=b").use {
            assertEquals("signed in with code=a&state=b", it.body.string())
            assertEquals("no-store", it.header("Cache-Control"))
        }
    }

    @Test
    fun theOperatorAnswersTheApi() {
        get("/api/card/c_1?x=1", "X-Remote-User-Display-Name" to "Ada").use { assertEquals(200 to """{"ok":true}""", it.code to it.body.string()) }
        assertEquals("GET /api/card/c_1?x=1 user=Ada file=null type=null body=", seen.poll(5, TimeUnit.SECONDS))
        get("/api/missing").use { assertEquals(404, it.code) }
        seen.clear()
        // an upload, chunked as the Supervisor forwards it
        val chunked = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) { sink.write(ByteArray(3) { 'x'.code.toByte() }) }
        }
        val upload = Request.Builder().url("http://127.0.0.1:$port/api/upload").post(chunked).header("X-Filename", "a.png").build()
        http.newCall(upload).execute().use { assertEquals(200, it.code) }
        assertEquals("POST /api/upload user=null file=a.png type=application/octet-stream body=xxx", seen.poll(5, TimeUnit.SECONDS))
        val tooLarge = Request.Builder().url("http://127.0.0.1:$port/api/upload").post(ByteArray(FrontDoor.BODY_MAX + 1).toRequestBody()).build()
        http.newCall(tooLarge).execute().use { assertEquals(400, it.code) }
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/next/").post("{}".toRequestBody()).build()).execute().use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/api/x").put("{}".toRequestBody()).build()).execute().use { assertEquals(501, it.code) }
    }

    @Test
    fun anEventStreamComesAsItComes() {
        get("/api/events?fields=rev").use {
            assertEquals("text/event-stream", it.header("Content-Type"))
            val source = it.body.source()
            assertEquals("event: state", source.readUtf8LineStrict())   // while the operator keeps the stream open
            assertEquals("data: {\"rev\":1}", source.readUtf8LineStrict())
        }
    }

    @Test
    fun anOperatorThatIsNotRunningIs503() {
        operator.stop(0)
        get("/api/state").use { assertEquals(503, it.code) }
    }

    @Test
    fun theWebSocketEchoKeepsTextAndBinary() {
        val got = LinkedBlockingQueue<String>()
        val ws = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/api/ws/echo").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { got += "text $text" }
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString) { got += "binary ${bytes.size}" }
        })
        ws.send("ping")
        assertEquals("text ping", got.poll(5, TimeUnit.SECONDS))
        ws.send(ByteArray(100_000).toByteString())
        assertEquals("binary 100000", got.poll(5, TimeUnit.SECONDS))
        ws.close(1000, null)
    }

    private fun raw(request: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(request.toByteArray())
        s.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun aCurlStyleUploadAsksToContinue() {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            out.write("POST /api/upload HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n".toByteArray())
            out.flush()
            val input = s.getInputStream().bufferedReader()
            assertEquals("HTTP/1.1 100 Continue", input.readLine())
            input.readLine()
            out.write("ok".toByteArray())
            out.flush()
            assertEquals("HTTP/1.1 200 OK", input.readLine())
        }
    }
}
