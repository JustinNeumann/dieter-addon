package com.getdieter.operator

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The CLI failed (exit status, timeout); its message is the CLI's own. Answered with 502, as by the bridge. */
class CliError(message: String) : Exception(message)

/** A request the operator refuses; answered with 400 and the message. */
class BadRequest(message: String) : Exception(message)

/** `dieter ARGS`: the parsed JSON it prints, its text when that is no JSON, or null for no output. */
fun interface Dieter {
    fun run(args: List<String>, timeout: Duration): JsonElement?
}

fun Dieter.run(vararg args: String, timeout: Duration = 30.seconds) = run(args.toList(), timeout)

/** CLI commands answer with either a bare list or {key: [...]}. */
fun JsonElement?.unwrap(key: String): List<JsonObject> {
    val list = (this as? JsonObject)?.get(key) ?: this
    return (list as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}

class ProcessDieter(private val bin: String) : Dieter {
    override fun run(args: List<String>, timeout: Duration): JsonElement? {
        val process = ProcessBuilder(listOf(bin) + args).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
        // both streams on their own threads, so a CLI that hangs with its output open still times out
        val out = Collected(process.inputStream)
        val err = Collected(process.errorStream)
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw CliError("dieter ${args.take(2).joinToString(" ")}: timeout")
        }
        val stdout = out.text()
        if (process.exitValue() != 0)
            throw CliError(err.text().ifBlank { stdout }.trim().take(500).ifEmpty { "exit ${process.exitValue()}" })
        val text = stdout.trim()
        if (text.isEmpty() || text == "null") return null
        return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }.takeUnless { it is JsonNull }
    }

    private class Collected(stream: java.io.InputStream) {
        @Volatile private var bytes = ByteArray(0)
        private val reader = Thread.ofVirtual().start { bytes = stream.readBytes() }

        fun text(): String {
            reader.join()
            return bytes.decodeToString()
        }
    }
}
