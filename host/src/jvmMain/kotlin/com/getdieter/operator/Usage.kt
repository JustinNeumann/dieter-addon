package com.getdieter.operator

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the slow poll reports beside the daemon's own figures (bridge/dieter_bridge.py until 0.6.431.21): the usage of
 * the providers the daemon's `quota list` lacks (it is gateway-scoped and knows no Antigravity), probed here: Claude by
 * Dieter's quota-claude.mjs of the staged harness runtime (the page sign-in, else the options' token through
 * [tokenProbe]), Antigravity by `agy --print=/quota`; each at most every five minutes. In the add-on only.
 */
class Usage(
    private val home: File,
    private val runtimeDir: File,
    private val tokenProbe: File,
    private val options: () -> JsonObject?,
    private val envFile: () -> Map<String, String>,
) {
    private val credentials = File(home, ".claude/.credentials.json")
    private val antigravityToken = File(home, ".gemini/antigravity-cli/antigravity-oauth-token")
    private class Cached(val at: Long, val generation: Int, val item: JsonObject?)
    private val cache = ConcurrentHashMap<String, Cached>()
    /** bumped when provider credentials change: a poll that started earlier must not refill the cache */
    private val generation = AtomicInteger()

    /** After a Claude sign-in or sign-out: probe again. */
    fun forget() {
        generation.incrementAndGet()
        cache.clear()
    }

    /** Quota items of the providers [have] lacks. */
    fun local(have: Set<String>): List<JsonObject> {
        val items = mutableListOf<JsonObject?>()
        if ("anthropic_claude" !in have) items += cached("anthropic_claude", ::claude)
        if ("antigravity" !in have && antigravityToken.exists()) items += cached("antigravity", ::antigravity)
        return items.filterNotNull()
    }

    /** The LLM backends this add-on has credentials for (the Inbox shows one usage bar per backend). */
    fun backends(claudeSignedIn: Boolean): JsonArray = buildJsonArray(
        listOfNotNull(
            ("anthropic_claude" to "Claude Code").takeIf { claudeSignedIn },
            ("antigravity" to "Antigravity").takeIf { antigravityToken.exists() },
            ("openai_codex" to "OpenAI").takeIf { (options()?.get("openai_api_key") as? JsonPrimitive)?.contentOrNull.orEmpty().isNotBlank() },
        ),
    )

    private fun buildJsonArray(pairs: List<Pair<String, String>>) =
        JsonArray(pairs.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } })

    private fun cached(provider: String, probe: () -> JsonObject?): JsonObject? {
        val now = System.currentTimeMillis()
        val gen = generation.get()
        cache[provider]?.takeIf { now - it.at < TTL_MS && it.generation == gen }?.let { return it.item }
        val item = probe()
        if (generation.get() == gen) cache[provider] = Cached(now, gen, item)
        return item
    }

    private fun signIn(): JsonObject? = runCatching {
        ((Json.parseToJsonElement(credentials.readText()) as JsonObject)["claudeAiOauth"] as? JsonObject)
            ?.takeIf { (it["accessToken"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
    }.getOrNull()

    private fun claude(): JsonObject? {
        val env = HashMap(envFile()).apply { put("HOME", home.path) }
        val probe = File(runtimeDir, "quota-claude.mjs")
        if (signIn() != null) {
            env.remove("CLAUDE_CODE_OAUTH_TOKEN")   // the probe reads the sign-in's credentials file instead
            refreshIfDue()
        }
        if ((env["CLAUDE_CODE_OAUTH_TOKEN"].isNullOrEmpty() && signIn() == null) || !probe.exists()) return null
        try {
            val done = Command.run(listOf("node", probe.path), cwd = probe.parentFile, timeout = 40.seconds, env = env) ?: run {
                Log.line("quota: claude probe timed out")
                return null
            }
            var a = runCatching { Json.parseToJsonElement(done.stdout.trim().ifEmpty { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            if (done.exit != 0 || done.stdout.isBlank()) Log.line("quota: claude probe exited ${done.exit} ${done.stderr.trim().lines().lastOrNull().orEmpty().ifEmpty { "(no output)" }.take(160)}")
            if (a.objs("windows").isEmpty() && signIn() == null) {
                // setup-token logins are "unsupported" for Dieter's probe: fetch the usage API directly
                val t = Command.run(listOf("node", tokenProbe.path, probe.path), cwd = tokenProbe.parentFile, timeout = 40.seconds, env = env)
                val err = t?.stderr?.lines()?.firstOrNull { "Error" in it } ?: t?.stderr?.trim()?.lines()?.lastOrNull() ?: "timeout"
                a = if (t?.exit == 0) runCatching { Json.parseToJsonElement(t.stdout.trim().ifEmpty { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                else buildJsonObject { put("availability", err.trim().take(120)) }
            }
            if (a.objs("windows").isNotEmpty()) {
                return buildJsonObject {
                    put("provider", "anthropic_claude")
                    put("plan", a["plan"] ?: JsonNull)
                    put("email", a["displayEmail"] ?: JsonNull)
                    put("available", a.field("availability") == "available")
                    putJsonArray("windows") {
                        for (w in a.objs("windows")) add(window(w["id"], w["label"], w["usedPercent"], w["remainingPercent"], w["resetsAt"]))
                    }
                    put("refreshed_at", now())
                }
            }
            if (a.field("availability").isNotEmpty())
                Log.line("quota: claude: ${a.field("availability")}" + a.field("displayEmail").let { if (it.isEmpty()) "" else " ($it)" })
            else if (signIn() != null) Log.line("quota: claude: the probe returned no windows for the page sign-in")
        } catch (e: Exception) {
            Log.line("quota: claude probe: $e")
        }
        return null
    }


    /**
     * Renews the sign-in's access token shortly before it expires (what the CLI does on its own runs), so the probe keeps
     * working while no Claude Code turn runs. Writes the file back.
     */
    private fun refreshIfDue(marginS: Long = 300) {
        val o = signIn() ?: return
        val refresh = o.field("refreshToken").ifEmpty { return }
        val expires = ((o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L) / 1000
        if (expires - System.currentTimeMillis() / 1000 > marginS) return
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refresh)
            put("client_id", CLAUDE_OAUTH_CLIENT)
        }
        val t = try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(CLAUDE_TOKEN_URL)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("User-Agent", "dieter-bridge/1")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            Json.parseToJsonElement(response.body()) as JsonObject
        } catch (e: Exception) {
            Log.line("claude: token refresh failed: ${e.message.orEmpty().take(120)}")
            return
        }
        val file = Json.parseToJsonElement(credentials.readText()) as JsonObject
        val oauth = file.obj("claudeAiOauth") ?: return
        val lifetime = (t["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        val renewed = oauth.updated(
            "accessToken" to t.field("access_token"),
            "refreshToken" to t.field("refresh_token").ifEmpty { refresh },
            "expiresAt" to (System.currentTimeMillis() / 1000 + lifetime) * 1000,
        )
        writePrivate(credentials, file.updated("claudeAiOauth" to renewed).toString())
        Log.line("claude: access token renewed")
    }

    /**
     * Antigravity's quota groups: within a group the models share a weekly and a 5-hour window. A group names its model
     * families in the description ("Models within this group: Gemini Flash, Gemini Pro"), Argon only itself.
     */
    private fun antigravity(): JsonObject? {
        try {
            val done = Command.run(listOf("agy", "--print=/quota", "--output-format", "json"), cwd = home, timeout = 60.seconds, env = mapOf("HOME" to home.path))
                ?: run {
                    Log.line("quota: agy: timeout")
                    return null
                }
            val groups = runCatching { (Json.parseToJsonElement(done.stdout) as JsonObject).obj("command")?.obj("data")?.objs("groups") }.getOrNull().orEmpty()
            val windows = agyWindows(groups)
            if (windows.isNotEmpty()) {
                return buildJsonObject {
                    put("provider", "antigravity")
                    put("plan", null as String?)
                    put("email", null as String?)
                    put("available", windows.all { ((it["remaining"] as JsonPrimitive).doubleOrNull ?: 0.0) > 0 })
                    put("windows", JsonArray(windows))
                    put("refreshed_at", now())
                }
            }
            Log.line("quota: agy: " + (done.stderr.ifBlank { done.stdout }.trim().ifEmpty { "no quota groups (exit ${done.exit})" }).takeLast(160))
        } catch (e: Exception) {
            Log.line("quota: agy: $e")
        }
        return null
    }

    companion object {
        const val TTL_MS = 300_000L
        const val CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val CLAUDE_OAUTH_CLIENT = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"   // Claude Code's public OAuth client id
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
        private val GROUP_MODELS = Regex("Models within this group:\\s*(.+)")
        private val NOT_ID = Regex("[^a-z0-9]+")
        private val WORDS = Regex("[a-z0-9.]+")

        fun now(): String = ZonedDateTime.now().format(STAMP)

        private fun window(id: JsonElement?, label: JsonElement?, used: JsonElement?, remaining: JsonElement?, resets: JsonElement?) = buildJsonObject {
            put("id", id ?: JsonNull)
            put("label", label ?: JsonNull)
            put("used", used ?: JsonNull)
            put("remaining", remaining ?: JsonNull)
            put("resets_at", resets ?: JsonNull)
        }

        /** `dieter quota list` -> one item per provider account. */
        fun normQuota(q: JsonElement?): List<JsonObject> {
            val out = mutableListOf<JsonObject>()
            for (group in (q as? JsonObject)?.objs("groups").orEmpty()) {
                val provider = group.field("provider").replace("PROVIDER_QUOTA_PROVIDER_", "").lowercase()
                for (a in group.objs("accounts")) {
                    out += buildJsonObject {
                        put("provider", provider)
                        put("plan", a["plan"] ?: JsonNull)
                        put("email", a["displayEmail"] ?: JsonNull)
                        put("available", "AVAILABLE" in a.field("availability"))
                        putJsonArray("windows") { for (w in a.objs("windows")) add(window(w["id"], w["label"], w["usedPercent"], w["remainingPercent"], w["resetsAt"])) }
                        put("refreshed_at", a["refreshedAt"] ?: JsonNull)
                    }
                }
            }
            return out
        }

        /** Antigravity's `/quota` groups -> windows, each naming the model families it covers. */
        fun agyWindows(groups: List<JsonObject>): List<JsonObject> {
            val windows = mutableListOf<JsonObject>()
            for (g in groups) {
                val name = g.field("name").trim()
                val families = GROUP_MODELS.find(g.field("description"))?.groupValues?.get(1)?.trim()?.trimEnd('.')
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(name)
                for (b in g.objs("buckets")) {
                    val fraction = (b["remaining_fraction"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    val remaining = (fraction.coerceIn(0.0, 1.0) * 100).roundHalfEven()
                    val span = when (b.field("window")) {
                        "weekly" -> "Week"
                        "5h" -> "5h"
                        else -> b.field("name").replace(" Limit Remaining", "").replace(" Limit", "")
                    }
                    windows += buildJsonObject {
                        put("id", b.field("id").ifEmpty { "$name $span".lowercase().replace(NOT_ID, "_").trim('_') })
                        put("label", "$name · $span")
                        put("used", 100 - remaining)
                        put("remaining", remaining)
                        put("resets_at", b["reset_time"] ?: JsonNull)
                        put("group", name)
                        putJsonArray("models") { families.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
            return windows
        }

        /** Python's round(): halves to the even neighbour. */
        private fun Double.roundHalfEven(): Int = java.math.BigDecimal(this).setScale(0, java.math.RoundingMode.HALF_EVEN).toInt()

        private fun words(s: String) = WORDS.findAll(s.lowercase()).map { it.value }.toSet()

        /**
         * Each Antigravity model with its tightest quota window: those of the groups naming its family (all words of
         * "Gemini Flash" in "Gemini 3.8 Flash (High)" / gemini-3.8-flash-high).
         */
        fun agyModelQuota(harnesses: List<JsonObject>, quota: List<JsonObject>): List<JsonObject> {
            val windows = quota.firstOrNull { it.field("provider") == "antigravity" }?.objs("windows").orEmpty()
            return harnesses.map { h ->
                if (h.field("id") != "antigravity") return@map h
                h.updated("models" to h.objs("models").map { m ->
                    val have = words(m.field("name")) + words(m.field("id"))
                    val mine = windows.filter { w -> w.strings("models").any { f -> words(f).isNotEmpty() && have.containsAll(words(f)) } }
                    val low = mine.minByOrNull { (it["remaining"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
                    m.updated("quota" to (low?.let { w -> JsonObject(listOf("remaining", "label", "resets_at", "group").associateWith { w[it] ?: JsonNull }) } ?: JsonNull))
                })
            }
        }
    }
}
