package com.getdieter.operator

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The add-on's daemon at the gateway. `dieter daemon enroll` blocks until the person authorizes the daemon with
 * GitHub at the gateway: it prints the verification URL and a code, then "Enrolled NAME as ID.". One enrollment at a
 * time; when it succeeds the daemon is started again ([restart]) so it connects to the gateway. [options] are the
 * add-on's (gateway_url, gateway_host_ip, machine_name), [store] the daemon's (DIETER_HOME), [home] the user's HOME.
 */
class Enrollment(
    private val bin: String,
    private val store: File,
    private val home: File,
    private val dieter: Dieter,
    private val options: () -> JsonObject?,
    private val restart: () -> Unit,
    private val start: (ProcessBuilder) -> Process = { it.start() },
    private val waitForUrl: Duration = 25.seconds,

) {
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var output = ""
    @Volatile private var url: String? = null
    @Volatile private var code: String? = null
    @Volatile private var done: JsonObject? = null
    @Volatile private var gateway = ""
    @Volatile private var started = 0L

    /** {running, url, code, done, gateway, started} of the latest enrollment. */
    fun status(): JsonObject = buildJsonObject {
        put("running", process != null && done == null)
        put("url", url)
        put("code", code)
        put("done", done ?: JsonNull)
        put("gateway", gateway)
        put("started", started)
    }

    /** Starts `dieter daemon enroll` for {url, name} (else the options'); the status once the URL is printed (≤ 25 s). */
    fun enroll(body: JsonObject): JsonObject {
        val target = (body.text("url")?.ifBlank { null } ?: option("gateway_url")).trim().trimEnd('/')
        val name = (body.text("name")?.ifBlank { null } ?: option("machine_name")).trim().replace(Regex("[^A-Za-z0-9 ._-]"), "").take(40).ifEmpty { "home-assistant" }
        if (!GATEWAY.matches(target)) throw CliError("the gateway must be an https origin such as https://gateway.example.net:4443")
        synchronized(lock) {
            if (process != null && done == null) return status()
            output = ""; url = null; code = null; done = null; gateway = target; started = System.currentTimeMillis()
            val builder = ProcessBuilder(bin, "--store", store.path, "daemon", "enroll", "--gateway", target, "--name", name, "--no-open")
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            builder.environment()["HOME"] = home.path
            val p = start(builder)
            process = p
            Thread.ofVirtual().start { read(p) }
        }
        val end = System.currentTimeMillis() + waitForUrl.inWholeMilliseconds
        while (System.currentTimeMillis() < end && url == null && done == null) Thread.sleep(200)
        return status()
    }

    private fun read(p: Process) {
        p.inputStream.bufferedReader().forEachLine { line ->
            output += line + "\n"
            if (url == null && "Authorize this daemon" in output)
                url = Regex("(https://\\S+)").find(output.substringAfterLast("Authorize this daemon with GitHub:"))?.groupValues?.get(1)
            if (code == null) code = Regex("Code:\\s*([A-Za-z0-9-]{4,})").find(output)?.groupValues?.get(1)
        }
        val exit = p.waitFor()
        val enrolled = Regex("Enrolled (.+?) as (\\S+?)\\.").find(output)
        val ok = exit == 0 && "Enrolled " in output
        val tail = output.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" ").takeLast(300)
        val result = buildJsonObject {
            put("ok", ok)
            put("error", if (ok) null else tail.ifEmpty { "enroll exited with $exit" })
            put("daemon_name", enrolled?.groupValues?.get(1).orEmpty())
            put("daemon_id", enrolled?.groupValues?.get(2).orEmpty())
        }
        synchronized(lock) {
            if (process !== p) return // a newer enrollment reports for itself
            done = result
            process = null
        }

        if (ok) {
            Log.line("gateway: enrolled as ${enrolled?.groupValues?.get(2).orEmpty()} at $gateway")
            restart()
        }
    }

    /** Stops a running enrollment. */
    fun cancel() {
        process?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
    }

    /**
     * `dieter daemon unenroll`: drops the enrollment key and credential (the daemon was removed from the account's
     * roster, so it cannot be approved again); the next enrollment makes a new key. Projects stay.
     */
    fun unenroll(): JsonObject {
        synchronized(lock) { if (process != null && done == null) throw CliError("an enrollment is in progress") }
        dieter.run(listOf("--store", store.path, "daemon", "unenroll"), 60.seconds)
        url = null; code = null; output = ""; done = null
        Log.line("gateway: unenrolled")
        restart()
        return buildJsonObject { put("ok", true) }
    }

    /** The enrollment as `dieter daemon status` and `device status` report it, with the options and [status]. */
    fun gateway(): JsonObject {
        val out = linkedMapOf<String, JsonElement>(
            "option_url" to JsonPrimitive(option("gateway_url").trim()),
            "host_ip" to JsonPrimitive(option("gateway_host_ip").trim()),
            "name" to JsonPrimitive(option("machine_name").trim().ifEmpty { "home-assistant" }),
            "enrolled" to JsonPrimitive(false),
        )
        try {
            val st = dieter.run(listOf("--store", store.path, "daemon", "status", "--format", "json"), 20.seconds) as? JsonObject
            if (st != null) {
                out["enrolled"] = JsonPrimitive(st.flag("enrolled"))
                out["daemon_id"] = JsonPrimitive(st.text("daemonId").orEmpty())
                out["daemon_name"] = JsonPrimitive(st.text("daemonName").orEmpty())
                out["url"] = JsonPrimitive(st.text("gatewayUrl").orEmpty())
                out["state"] = JsonPrimitive(st.text("gatewayState").orEmpty())
                out["last_error"] = JsonPrimitive(st.text("gatewayLastError").orEmpty())
                out["connected_at"] = st["gatewayConnectedAt"] ?: JsonNull
            }
        } catch (e: CliError) {
            out["error"] = JsonPrimitive(e.message)
        }
        if (out["enrolled"] == JsonPrimitive(true)) {
            runCatching {
                val trust = (dieter.run(listOf("--store", store.path, "device", "status", "--format", "json"), 20.seconds) as? JsonObject)?.text("state").orEmpty()
                out["trust"] = JsonPrimitive(trust.removePrefix("TRUST_STATE_"))
            }
        }
        out["enroll"] = status()
        return JsonObject(out)
    }

    private fun option(key: String) = options()?.text(key).orEmpty()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    companion object {
        val GATEWAY = Regex("^https://[A-Za-z0-9.-]+(:\\d{2,5})?$")
    }
}
