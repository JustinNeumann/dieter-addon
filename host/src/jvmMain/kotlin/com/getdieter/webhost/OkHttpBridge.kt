package com.getdieter.webhost

import com.getdieter.addon.core.Bridge
import com.getdieter.addon.core.BridgeResponse
import com.getdieter.addon.core.BridgeUnreachable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The add-on core's way to the add-on's API: the operator's, plain HTTP on loopback (DIETER_WEB_BRIDGE_URL; the
 * name Bridge stays from when the Python bridge answered it).
 */
internal class OkHttpBridge(base: String) : Bridge {
    private val base = base.trimEnd('/') + "/"
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun request(method: String, path: String, body: JsonObject?, user: String, timeout: Duration): BridgeResponse {
        val headers = Headers.Builder().add("Accept", "application/json")
        // the operator records some actions under the Home Assistant user's name, as Ingress forwards it
        if (user.isNotBlank()) headers.addUnsafeNonAscii("X-Remote-User-Display-Name", user.take(60))
        val request =
            Request.Builder()
                .url(base + path)
                .headers(headers.build())
                .method(method, body?.toString()?.toRequestBody(JSON))
                .build()
        val call =
            client.newBuilder()
                .readTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .callTimeout(timeout.inWholeMilliseconds + 5_000, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
        val response = call.await()
        return response.use { BridgeResponse(it.code, parse(it.body.string())) }
    }

    /** `GET events?fields=…` (server-sent events), again after a pause whenever it ends. */
    override fun events(fields: List<String>): Flow<JsonObject> =
        flow {
            var failures = 0
            while (true) {
                val call =
                    client.newBuilder()
                        .readTimeout(45, TimeUnit.SECONDS) // the operator pings every 14 s
                        .build()
                        .newCall(Request.Builder().url(base + "events?fields=" + fields.joinToString(",")).build())
                val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("events answered ${response.code}")
                        val source = response.body.source()
                        var event = ""
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                                line.startsWith("data:") && event == "state" -> {
                                    failures = 0
                                    emit(parse(line.substringAfter(':').trim()))
                                }
                                line.isEmpty() -> event = ""
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (failures++ == 0) Log.line("bridge events: $error")
                } finally {
                    cancel?.dispose()
                }
                delay(minOf(30_000L, 500L shl minOf(failures, 6)))
            }
        }.flowOn(Dispatchers.IO)

    private fun parse(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) = continuation.resume(response)

                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(BridgeUnreachable(e.message ?: e.toString()))
                }
            )
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
