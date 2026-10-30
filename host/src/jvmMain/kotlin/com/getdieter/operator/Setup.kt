package com.getdieter.operator

import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the add-on's options configure for the daemon and the agents (HOME=/data, so it persists): provider tokens
 * in the daemon's env file, the commit identity and GitHub token for git and gh, Claude Code's note about the
 * container, Antigravity's credentials. Each step can run again; the env file is rewritten before every daemon start.
 */
class Setup(
    private val options: JsonObject,
    private val home: File = File("/data"),
    private val supervisorToken: String? = null,
    private val gh: String? = which("gh"),
) {
    /** Provider credentials for the daemon and its harnesses, handed to it with --env-file. */
    val envFile = File(home, "dieter.env")
    private val claudeCredentials = File(home, ".claude/.credentials.json")
    private val gitCredentials = File(home, ".git-credentials")   // git's token store when gh is missing or its sign-in failed
    private val ghHosts = File(home, ".config/gh/hosts.yml")       // gh's sign-in; git reads the token from gh
    private val claudeHint = File(home, ".claude/CLAUDE.md")
    private val antigravityToken = File(home, ".gemini/antigravity-cli/antigravity-oauth-token")

    private fun option(key: String) = ((options[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)?.trim().orEmpty()

    /** The container's own Claude Code login (`claude auth login` on the page). */
    fun claudeSignedIn() = runCatching {
        val oauth = Json.parseToJsonElement(claudeCredentials.readText()).jsonObject["claudeAiOauth"]?.jsonObject
        !oauth?.get("accessToken")?.jsonPrimitive?.content.isNullOrEmpty()
    }.getOrDefault(false)

    /** Provider tokens from the options -> /data/dieter.env (the Claude Code harness reads CLAUDE_CODE_OAUTH_TOKEN,
     *  Codex OPENAI_API_KEY); a page sign-in takes Claude's place of the option token. */
    fun writeEnvFile() {
        var lines = PROVIDER_ENV.mapNotNull { (opt, env) -> option(opt).takeIf { it.isNotEmpty() }?.let { "$env=${token(it, env)}" } }
        if (claudeSignedIn()) {
            lines = lines.filterNot { it.startsWith("CLAUDE_CODE_OAUTH_TOKEN=") }
            Log.line("env: Claude Code uses the page sign-in ($claudeCredentials)")
        }
        if (lines.isNotEmpty()) {
            writePrivate(envFile, lines.joinToString("\n") + "\n")
            Log.line("env: providers configured: " + lines.joinToString(", ") { it.substringBefore("=") })
        } else envFile.delete()
    }

    /** The provider variables the env file holds, for the daemon's own environment too. */
    fun envFileVariables(): Map<String, String> =
        if (!envFile.exists()) emptyMap()
        else envFile.readLines().filter { "=" in it && !it.startsWith("#") }.associate { it.substringBefore("=") to it.substringAfter("=") }

    /**
     * Commit identity and GitHub token for the agents and the daemon. The token signs `gh` in (hosts.yml, 0600) and
     * git takes it from gh (`gh auth setup-git`), so the daemon's pull-request operations and the agents' `gh` calls
     * work with the one token from the options. Without gh, or when its sign-in fails (no network at start), git
     * falls back to the plain credential store.
     */
    fun git() {
        for ((key, opt) in listOf("user.name" to "git_user", "user.email" to "git_email")) {
            option(opt).takeIf { it.isNotEmpty() }?.let { git(listOf("config", "--global", key, it)) }
        }
        val token = option("github_token")
        var signedIn = false
        if (token.isNotEmpty() && gh != null) {
            // --insecure-storage: the container has no keyring; the file is the agents' user's anyway
            var (rc, out) = gh(listOf("auth", "login", "--hostname", "github.com", "--with-token", "--insecure-storage"), token + "\n")
            if (rc == 0) gh(listOf("auth", "setup-git", "--hostname", "github.com")).let { rc = it.first; out = it.second }
            if (rc == 0) {
                signedIn = true
                Log.line("git: gh signed in to github.com, git uses its token")
            } else Log.line("git: gh sign-in failed, git keeps the credential store: $out")
        } else if (token.isEmpty() && ghHosts.exists()) gh(listOf("auth", "logout", "--hostname", "github.com"))
        // one helper at a time: gh's url-specific entries (an empty one resets git's helper list) or the store
        val unset = if (signedIn) listOf("credential.helper") else listOf("credential.https://github.com.helper", "credential.https://gist.github.com.helper")
        for (key in unset) Command.run(listOf("git", "config", "--global", "--unset-all", key), timeout = 30.seconds)
        if (token.isNotEmpty() && !signedIn) {
            writePrivate(gitCredentials, "https://x-access-token:$token@github.com\n")
            git(listOf("config", "--global", "credential.helper", "store --file $gitCredentials"))
        } else gitCredentials.delete()
    }

    /** The GitHub CLI without prompts: (exit status, output with tokens redacted). */
    private fun gh(args: List<String>, input: String? = null): Pair<Int, String> {
        val env = mapOf("GH_PROMPT_DISABLED" to "1", "GIT_TERMINAL_PROMPT" to "0", "NO_COLOR" to "1")
        val done = Command.run(listOf(gh ?: return 127 to "gh not installed") + args, timeout = 60.seconds, input = input, env = env)
            ?: return -1 to "timeout"
        return done.exit to TOKEN.replace((done.stdout + done.stderr).trim(), "<token>").take(500)
    }

    /** Tells Claude Code (global memory ~/.claude/CLAUDE.md) what the container offers. Written when missing or still
     *  one of the add-on's earlier texts; a file the user edited or emptied is kept. */
    fun claudeHint() {
        // the agents' Home Assistant access (option agents_home_assistant) decides the note's first section
        val text = if (supervisorToken.isNullOrEmpty()) HINT_WITHOUT_HA else HINT
        if (claudeHint.exists()) {
            val current = sha256(claudeHint.readBytes())
            val ours = HINT_EARLIER + sha256(HINT.toByteArray()) + sha256(HINT_WITHOUT_HA.toByteArray())
            if (current == sha256(text.toByteArray()) || current !in ours) return
        }
        claudeHint.parentFile.mkdirs()
        claudeHint.writeText(text)
        Log.line("wrote $claudeHint")
    }

    /** Antigravity CLI credentials from the option (the JSON of ~/.gemini/antigravity-cli/antigravity-oauth-token).
     *  The MCP server through which agy reaches Dieter's host tools is registered by the runtime's Antigravity
     *  adapter itself (`agy mcp add dieter …` on the first turn that offers them). */
    fun antigravity() {
        val token = option("antigravity_token").ifEmpty { return }
        writePrivate(antigravityToken, if (token.endsWith("\n")) token else token + "\n")
        Log.line("env: Antigravity credentials installed")
    }

    companion object {
        val PROVIDER_ENV = linkedMapOf("claude_code_oauth_token" to "CLAUDE_CODE_OAUTH_TOKEN", "openai_api_key" to "OPENAI_API_KEY")
        private val TOKEN = Regex("(gh[pousr]_|github_pat_)[A-Za-z0-9_]+")

        /** Tolerates a pasted line like "claude: sk-ant-oat01-…" or surrounding quotes. */
        fun token(raw: String, env: String): String {
            val pattern = if (env == "CLAUDE_CODE_OAUTH_TOKEN") Regex("sk-ant-oat01-[A-Za-z0-9_-]{40,}") else Regex("sk-[A-Za-z0-9_-]{20,}")
            return pattern.find(raw)?.value ?: raw.trim { it in "\"' " }
        }

        val HINT: String by lazy { Setup::class.java.getResource("/operator/claude-hint.md")!!.readText() }

        /** The note when the agents have no Home Assistant access: its Home Assistant section says so. */
        val HINT_WITHOUT_HA: String by lazy {
            HINT.substring(0, HINT.indexOf("# Home Assistant")) +
                "# Home Assistant\n\nThis container is the Dieter add-on inside Home Assistant. The agents have no access " +
                "to Home Assistant here: the add-on's option \"Home Assistant for agents\" is off, so `hass-cli` and " +
                "`ha-logs` are not signed in. When a task needs Home Assistant, say so in your reply; do not look for " +
                "a token.\n\n" + HINT.substring(HINT.indexOf("# Images in the chat"))
        }

        /** Earlier texts of that file: a file with one of these is the add-on's and gets replaced, an edited one is kept. */
        val HINT_EARLIER = setOf(
            "b0b13e392971aa06e72ca1d0ac265f15e563c90f653210dff669f46224b250c6",
            "f6634308d5b763f0261f9f53999c661316086d8730469c9f07109559eb0c8de9",
            "07ac39b466d4bb34ba050f376d5b198d9c251a59d2f76255502db31cb9d1c0ea",
            "9df41f218d9be1b87009933d094274a6fb0fd79e09f379358410c5f35992a6eb",
            "8254ab58ecb155c5bc4ebae1055d9dcfba7a79e238ef6f5c9742e5b789607c31",
            "07d978e621ce25f0353eb7ea9211143c1f6dcf9e79e38f0ab3175e4e3eb76f64",   // 0.6.431.12: Markdown images shown
        )

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun which(program: String): String? =
            System.getenv("PATH").orEmpty().split(':').map { File(it, program) }.firstOrNull { it.canExecute() }?.path
    }
}
