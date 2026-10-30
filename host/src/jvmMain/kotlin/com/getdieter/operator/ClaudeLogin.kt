package com.getdieter.operator

import java.io.File
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The add-on's own Claude Code sign-in: `claude auth login` in a terminal (util-linux `script` gives it one; the CLI
 * wants a tty) prints the sign-in link and waits for the code the person gets on claude.com, then writes
 * [home]/.claude/.credentials.json. One attempt at a time. A sign-in or sign-out calls [changed] (the daemon starts
 * again with a rewritten env file, in which the sign-in takes precedence over the options' token).
 */
class ClaudeLogin(
    private val home: File,
    private val options: () -> JsonObject?,
    private val changed: () -> Unit,
    private val command: List<String> = listOf("script", "-q", "-f", "-e", "-c", "claude auth login --claudeai", "/dev/null"),
    private val waitForUrl: Duration = 20.seconds,
    private val waitForCode: Duration = 45.seconds,
) {
    val credentials = File(home, ".claude/.credentials.json")
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var input: OutputStream? = null
    @Volatile private var output = ""
    @Volatile private var url: String? = null
    @Volatile private var done: JsonObject? = null

    /** {signed_in, method (login | token), email, plan, expires_at}: the sign-in, else the options' token. */
    fun auth(): JsonObject {
        val login = signIn()
        return buildJsonObject {
            when {
                login != null -> {
                    put("signed_in", true); put("method", "login"); put("email", null as String?)
                    put("plan", (login["subscriptionType"] as? JsonPrimitive)?.contentOrNull)
                    put("expires_at", (login["expiresAt"] as? JsonPrimitive)?.longOrNull)
                }
                ((options()?.get("claude_code_oauth_token") as? JsonPrimitive)?.contentOrNull ?: "").isNotBlank() -> {
                    put("signed_in", true); put("method", "token"); put("email", null as String?); put("plan", null as String?); put("expires_at", null as Long?)
                }
                else -> {
                    put("signed_in", false); put("method", null as String?); put("email", null as String?); put("plan", null as String?); put("expires_at", null as Long?)
                }
            }
        }
    }

    /** The sign-in's claudeAiOauth entry when it holds an access token. */
    private fun signIn(): JsonObject? = runCatching {
        val o = (Json.parseToJsonElement(credentials.readText()) as JsonObject)["claudeAiOauth"] as? JsonObject
        o?.takeIf { (it["accessToken"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
    }.getOrNull()

    /** {running, url, done, auth}. */
    fun status(): JsonObject = buildJsonObject {
        put("running", process != null && done == null)
        put("url", url)
        put("done", done ?: JsonNull)
        put("auth", auth())
    }

    /** Starts a sign-in (a running one is stopped first); the status once the link is printed (≤ 20 s). */
    fun start(): JsonObject {
        synchronized(lock) {
            cancel()
            output = ""; url = null; done = null
            val builder = ProcessBuilder(command).directory(home).redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", home.path); put("TERM", "dumb"); put("BROWSER", "/bin/false"); put("NO_COLOR", "1")
                remove("CLAUDE_CODE_OAUTH_TOKEN")
            }
            val p = builder.start()
            process = p
            input = p.outputStream
            Thread.ofVirtual().start { read(p) }
        }
        val end = System.currentTimeMillis() + waitForUrl.inWholeMilliseconds
        while (System.currentTimeMillis() < end && url == null && done == null) Thread.sleep(200)
        return status()
    }

    private fun read(p: Process) {
        val buffer = ByteArray(4096)
        p.inputStream.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                output += buffer.decodeToString(0, n)
                if (url == null) url = LINK.find(output)?.value
            }
        }
        val exit = p.waitFor()
        val clean = output.replace(Regex("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)|\u001b\\[[0-9;?]*[A-Za-z]"), "")
        val tail = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(4).joinToString(" ")
        val ok = exit == 0 && signIn() != null
        val result = buildJsonObject {
            put("ok", ok)
            put("error", if (ok) null else tail.replace(Regex("\\bcode=\\S+"), "code=…").takeLast(200).ifEmpty { "claude auth login exited with $exit" })
        }
        synchronized(lock) {
            if (process !== p) return // stopped for a new attempt, which reports for itself
            done = result
            process = null
            input = null
        }

        if (ok) {
            Log.line("claude: signed in via claude auth login")
            changed()
        }
    }

    /** Hands the code from claude.com to the CLI and waits until it is done (≤ 45 s): {ok, error}. */
    fun code(code: String): JsonObject {
        val stream = input
        if (stream == null || done != null) return failed("no sign-in in progress; start again")
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return failed("code required")
        try {
            stream.write((trimmed + "\n").toByteArray())
            stream.flush()
        } catch (e: java.io.IOException) {
            return failed("could not hand the code to claude: ${e.message}")
        }
        val end = System.currentTimeMillis() + waitForCode.inWholeMilliseconds
        while (System.currentTimeMillis() < end && done == null) Thread.sleep(300)
        return done ?: failed("claude did not finish the sign-in in time")
    }

    /** Stops a running sign-in, the CLI under `script` with it. */
    fun cancel() {
        process?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
    }

    /** Stops a running sign-in and removes the sign-in; the auth that is left (the options' token, if any). */
    fun logout(): JsonObject {
        cancel()
        credentials.delete()
        Log.line("claude: signed out")
        changed()
        return auth()
    }

    private fun failed(error: String) = buildJsonObject { put("ok", false); put("error", error) }

    companion object {
        val LINK = Regex("https://claude\\.com/[^\\s\u001b\u0007]+")
    }
}
