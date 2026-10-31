package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class StartupTest {
    private val home = kotlin.io.path.createTempDirectory("startup").toFile()

    private fun options(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun `the env file holds the providers' tokens, cleaned up, and the page sign-in replaces Claude's option token`() {
        val claude = "sk-ant-oat01-" + "a".repeat(48)
        val setup = Setup(options("""{"claude_code_oauth_token":"claude: $claude","openai_api_key":"'sk-${"b".repeat(24)}'"}"""), home)
        setup.writeEnvFile()
        assertEquals("CLAUDE_CODE_OAUTH_TOKEN=$claude\nOPENAI_API_KEY=sk-${"b".repeat(24)}\n", setup.envFile.readText())
        assertEquals("rw-------", mode(setup.envFile))
        assertEquals(mapOf("CLAUDE_CODE_OAUTH_TOKEN" to claude, "OPENAI_API_KEY" to "sk-${"b".repeat(24)}"), setup.envFileVariables())

        File(home, ".claude").mkdirs()
        File(home, ".claude/.credentials.json").writeText("""{"claudeAiOauth":{"accessToken":"x"}}""")
        setup.writeEnvFile()
        assertEquals("OPENAI_API_KEY=sk-${"b".repeat(24)}\n", setup.envFile.readText())

        Setup(options("""{"claude_code_oauth_token":"","openai_api_key":null}"""), home).writeEnvFile()
        assertFalse(setup.envFile.exists())
    }

    @Test
    fun `Claude's note is written in the add-on when missing, and one the user edited or emptied is kept`() {
        val note = File(home, ".claude/CLAUDE.md")
        Setup(JsonObject(emptyMap()), home, supervisorToken = null).claudeHint()
        assertFalse(note.exists(), "outside the add-on (no Supervisor token, no relay)")
        // 0.6.431.27's note for agents without Home Assistant is the add-on's: replaced
        note.parentFile.mkdirs()
        val hint30 = javaClass.getResource("/operator/claude-hint-0.6.431.30.md")!!.readText()
        val earlier = hint30.substring(0, hint30.indexOf("# Home Assistant")) +
            "# Home Assistant\n\nThis container is the Dieter add-on inside Home Assistant. The agents have no access to Home " +
            "Assistant here: the add-on's option \"Home Assistant for agents\" is off, so `hass-cli` and `ha-logs` are not " +
            "signed in. When a task needs Home Assistant, say so in your reply; do not look for a token.\n\n" +
            hint30.substring(hint30.indexOf("# Images in the chat"))
        note.writeText(earlier)
        val perTask = Setup(JsonObject(emptyMap()), home, supervisorToken = null, agentRelay = true)
        perTask.claudeHint()
        assertEquals(Setup.HINT_PER_TASK, note.readText(), "option off: conversations with Home Assistant access")
        assertTrue("Allow Home Assistant access" in note.readText() && "hass-cli state list" in note.readText())
        val setup = Setup(JsonObject(emptyMap()), home, supervisorToken = "t")
        setup.claudeHint()
        assertEquals(Setup.HINT, note.readText(), "the option turned on: every agent")
        assertTrue("A Markdown image in your reply stays a link" in Setup.HINT)
        perTask.claudeHint()
        assertEquals(Setup.HINT_PER_TASK, note.readText(), "and off again")
        setup.claudeHint()
        // 0.6.431.30's note (merging allowed when a task said so) is replaced by the one that leaves merging to the person
        note.writeText(hint30)
        setup.claudeHint()
        assertEquals(Setup.HINT, note.readText(), "0.6.431.30's note replaced")
        assertTrue("Merging is the person's, even when a task asks for it" in Setup.HINT && "merge_pr" in Setup.HINT)

        note.writeText("# mine\n")
        setup.claudeHint()
        assertEquals("# mine\n", note.readText(), "edited by the user: kept")
        note.writeText("")
        setup.claudeHint()
        assertEquals("", note.readText(), "emptied by the user: kept")
    }

    @Test
    fun `Antigravity's credentials are written private, with a final newline`() {
        Setup(options("""{"antigravity_token":"{\"a\":1}"}"""), home).antigravity()
        val file = File(home, ".gemini/antigravity-cli/antigravity-oauth-token")
        assertEquals("{\"a\":1}\n", file.readText())
        assertEquals("rw-------", mode(file))
        assertEquals("rwx------", mode(file.parentFile))
        assertEquals("rwx------", mode(File(home, ".gemini")))
    }

    /** An archive like the release's: harness/node_modules and the Bun package. */
    private fun archive(): Pair<File, String> {
        val src = File(home, "src").apply { File(this, "harness/node_modules/x").mkdirs(); File(this, "harness/node_modules/x/index.js").writeText("1") }
        val tgz = File(home, "archive.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", tgz.path, "-C", src.path, "harness").start().waitFor())
        return tgz to Harness.sha256(tgz)
    }

    private fun sources() = File(home, "sources").apply {
        mkdirs()
        File(this, "runner.mjs").writeText("export {}\n")
        File(this, "package.json").writeText("{}\n")
        File(this, "README").writeText("not copied")
    }

    @Test
    fun `the runtime is downloaded once, checked, unpacked, and the sources laid over it with the bridge's digest`() {
        val (tgz, sha) = archive()
        val downloads = mutableListOf<String>()
        val harness = Harness(File(home, "dieter"), "1.2.3", "x86_64", sources(), pins = mapOf("x86_64" to sha))
        val fetch: (String, File) -> Unit = { url, to -> downloads += url; tgz.copyTo(to, overwrite = true) }
        harness.stage(fetch)
        harness.stage(fetch)
        assertEquals(listOf("https://github.com/JustinNeumann/dieter/releases/download/v1.2.3/dieter-harness-runtime-linux-amd64.tar.gz"), downloads)
        assertEquals(sha, File(harness.dir, ".staged").readText())
        assertFalse(File(harness.dir.path + ".download.tar.gz").exists())

        harness.buildRuntimeDir()
        assertTrue(File(harness.runtimeDir, "node_modules/x/index.js").exists(), "the archive's node_modules moved in")
        assertEquals(setOf("node_modules", "runner.mjs", "package.json"), harness.runtimeDir.list()!!.toSet())
        // the digest the Python bridge wrote (sha256 over name NUL content NUL, sorted): an update does not lay them again
        assertEquals("eedc0958607a54911310c8c36001463b5630cbf1ecfd0407341914d67e146fdf", File(harness.dir, ".runtime-sources").readText())
    }

    @Test
    fun `a wrong archive is refused, an equal one of an earlier version is taken over, earlier versions are removed`() {
        val (tgz, sha) = archive()
        val dieter = File(home, "dieter")
        val wrong = Harness(dieter, "1.0.0", "aarch64", sources(), pins = mapOf("aarch64" to "0".repeat(64)))
        assertFailsWith<IllegalStateException> { wrong.stage { _, to -> tgz.copyTo(to, overwrite = true) } }
        assertFalse(File(wrong.dir, ".staged").exists())
        assertFalse(File(wrong.dir.path + ".download.tar.gz").exists())

        val old = Harness(dieter, "1.0.0", "aarch64", sources(), pins = mapOf("aarch64" to sha))
        old.stage { _, to -> tgz.copyTo(to, overwrite = true) }
        val new = Harness(dieter, "1.0.1", "aarch64", sources(), pins = mapOf("aarch64" to sha))
        new.stage { _, _ -> error("no download for the same archive") }
        assertTrue(File(new.dir, ".staged").exists())
        assertFalse(old.dir.exists())

        File(dieter, "harness-staged/0.9.0").mkdirs()
        new.prune()
        assertEquals(listOf("1.0.1"), File(dieter, "harness-staged").list()!!.toList())
        assertEquals("x86_64", Harness.machine("amd64"))
        assertEquals("aarch64", Harness.machine("aarch64"))
    }

    @Test
    fun `the daemon gets the operator's question relay, Home Assistant, the runtime and the env file's tokens`() {
        val runtime = File(home, "rt").apply { mkdirs(); File(this, "runner.mjs").writeText("") }
        val env = Supervision.daemonEnvironment(mapOf("SUPERVISOR_TOKEN" to "s", "DIETER_HARNESS_ENV" to "X"), runtime, mapOf("OPENAI_API_KEY" to "k"))
        assertEquals(
            mapOf(
                "DIETER_QUESTIONS_URL" to "http://127.0.0.1:8096/api/questions", "HASS_SERVER" to "http://supervisor/core", "HASS_TOKEN" to "s",
                "DIETER_HARNESS_ENV" to "X,DIETER_QUESTIONS_URL,HASS_SERVER,HASS_TOKEN", "DIETER_HARNESS_RUNTIME_DIR" to runtime.path, "OPENAI_API_KEY" to "k",
            ),
            env,
        )
        val relayed = Supervision.daemonEnvironment(mapOf("DIETER_HA_AGENT_URL" to "http://127.0.0.1:8098"), File(home, "none"), emptyMap())
        assertEquals("http://127.0.0.1:8098", relayed["HASS_SERVER"], "option off: agents reach the relay")
        assertFalse("HASS_TOKEN" in relayed, "and sign in with their turn's token")
        assertTrue("HASS_SERVER" in relayed.getValue("DIETER_HARNESS_ENV").split(","))
        val plain = Supervision.daemonEnvironment(mapOf("DIETER_OPERATOR_ADDR" to "127.0.0.1:4250"), File(home, "none"), emptyMap())
        assertEquals(mapOf("DIETER_QUESTIONS_URL" to "http://127.0.0.1:4250/api/questions", "DIETER_HARNESS_ENV" to "DIETER_QUESTIONS_URL"), plain)
    }

    @Test
    fun `the supervisor starts the daemon again with growing pauses, fresh setup each time, and restarts it on request`() {
        val pauses = mutableListOf<Duration>()
        var setups = 0
        DaemonSupervisor("/bin/sh", command = { listOf("/bin/sh", "-c", "exit 3") }, environment = { emptyMap() }, beforeStart = { setups++ }, sleep = { pauses += it })
            .loop(rounds = 3)
        assertEquals(listOf(4, 8, 16), pauses.map { it.inWholeSeconds.toInt() })
        assertEquals(3, setups)

        val seen = mutableListOf<String>()
        val supervisor = DaemonSupervisor(
            "/bin/sh",
            command = { listOf("/bin/sh", "-c", "echo \$GREETING > ${home.path}/env; sleep 30") },
            environment = { mapOf("GREETING" to "hello") },
            sleep = { seen += "slept" },
        )
        assertFalse(supervisor.restart(), "nothing runs yet")
        val loop = thread { supervisor.loop(rounds = 1) }
        val end = System.currentTimeMillis() + 10_000
        while (!File(home, "env").exists() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals("hello\n", File(home, "env").readText())
        assertTrue(supervisor.restart())
        loop.join(10_000)
        assertFalse(loop.isAlive, "the daemon stopped on restart()")
        assertEquals(listOf("slept"), seen)
    }

    @Test
    fun `only a daemon start is a daemon`() {
        assertTrue(DaemonSupervisor.isDaemonStart(listOf("--store", "/data/dieter", "daemon", "start", "--addr", "127.0.0.1:4242")))
        assertFalse(DaemonSupervisor.isDaemonStart(listOf("--store", "/data/dieter", "daemon", "status")))
        assertFalse(DaemonSupervisor.isDaemonStart(listOf("status", "start")))
    }
}
