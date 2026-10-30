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
        val done = Command.run(listOf(bin) + args, timeout = timeout) ?: throw CliError("dieter ${args.take(2).joinToString(" ")}: timeout")
        if (done.exit != 0) throw CliError(done.stderr.ifBlank { done.stdout }.trim().take(500).ifEmpty { "exit ${done.exit}" })
        val text = done.stdout.trim()
        if (text.isEmpty() || text == "null") return null
        return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }.takeUnless { it is JsonNull }
    }
}

/** A finished command: its exit status and both outputs. */
class Command(val exit: Int, val stdout: String, val stderr: String) {
    companion object {
        /** Runs [cmd] in [cwd] with [input] on stdin (else none) and [env] added; null when it outlives [timeout] (killed then). */
        fun run(cmd: List<String>, cwd: java.io.File? = null, timeout: Duration, input: String? = null, env: Map<String, String> = emptyMap()): Command? {
            val builder = ProcessBuilder(cmd).directory(cwd)
            builder.environment().putAll(env)
            if (input == null) builder.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            val process = builder.start()
            // both streams on their own threads, so a command that hangs with its output open still times out
            val out = Collected(process.inputStream)
            val err = Collected(process.errorStream)
            if (input != null) process.outputStream.use { it.write(input.toByteArray()) }
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            return Command(process.exitValue(), out.text(), err.text())
        }
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

/** [cmd] in [cwd]: its output; a failure or timeout throws CliError "<program>: <its message>". */
fun checked(cmd: List<String>, cwd: java.io.File? = null, timeout: Duration): String {
    val program = cmd.first().substringAfterLast('/')
    val done = Command.run(cmd, cwd, timeout) ?: throw CliError("$program: timeout")
    if (done.exit != 0) throw CliError("$program: " + done.stderr.ifBlank { done.stdout }.trim().take(500).ifEmpty { "exit ${done.exit}" })
    return done.stdout
}

/** `git ARGS` in [cwd]: its output; a failure is answered with 502 "git: …", as by the bridge. */
fun git(args: List<String>, cwd: java.io.File? = null, timeout: Duration = 600.seconds): String = checked(listOf("git") + args, cwd, timeout)

/** `rm -rf PATH` (does not follow links into other trees). */
fun removeTree(path: java.io.File, timeout: Duration = 600.seconds) {
    checked(listOf("rm", "-rf", path.path), timeout = timeout)
}

/** [text] into [file], readable by its owner only (missing folders too), written whole and renamed into place. */
fun writePrivate(file: java.io.File, text: String) {
    java.nio.file.Files.createDirectories(
        file.parentFile.toPath(),
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),
    )
    val tmp = java.nio.file.Files.createTempFile(file.parentFile.toPath(), ".${file.name}.", ".tmp")   // created 0600
    try {
        java.nio.file.Files.writeString(tmp, text)
        java.nio.file.Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } finally {
        java.nio.file.Files.deleteIfExists(tmp)
    }
}
