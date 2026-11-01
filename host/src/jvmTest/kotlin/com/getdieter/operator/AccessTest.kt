package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AccessTest {
    private val home = kotlin.io.path.createTempDirectory("access").toFile()

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun JsonObject.text(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun executable(name: String, script: String) = File(home, name).apply {
        writeText("#!/bin/sh\n$script\n")
        setExecutable(true)
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    @Test
    fun theAddOnsSshKeyIsMadeOnceWithAConfigThatUsesIt() {
        val ssh = Ssh(home)
        assertEquals(null, ssh.publicKey().text("public_key"), "no key before the first start")
        ssh.setup()
        val first = ssh.publicKey()
        assertTrue(first.text("public_key")!!.matches(Regex("ssh-ed25519 \\S+ dieter@home-assistant")))
        assertTrue(first.text("fingerprint")!!.startsWith("SHA256:"))
        assertEquals("rwx------", Files.getPosixFilePermissions(File(home, ".ssh").toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
        val config = File(home, ".ssh/config")
        assertTrue(config.readText().contains("IdentityFile ${ssh.key.path}\n  IdentitiesOnly yes\n  StrictHostKeyChecking accept-new"))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(config.toPath())))
        ssh.setup()
        assertEquals(first, ssh.publicKey(), "a second start keeps the key")
    }

    @Test
    fun aConnectionTestReportsTheTargetsAnswerOrRefusesABadTarget() {
        val calls = mutableListOf<List<String>>()
        val ssh = Ssh(home) { cmd, _, env ->
            calls += cmd
            assertEquals(home.path, env["HOME"])
            if (cmd.any { it.endsWith("@pi.local") }) Command(0, "pi\n", "")
            else Command(255, "", "debug\nssh: connect to host 127.0.0.1 port 1: Connection refused\n")
        }
        assertEquals("target must be user@host or host (optionally :port)", ssh.test("rm -rf /").text("error"))
        assertEquals("pi", ssh.test(" hassio@pi.local ").text("output"))
        assertEquals(listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", "hassio@pi.local", "uname", "-n"), calls.last())
        val refused = ssh.test("nobody@127.0.0.1:1")
        assertEquals(false, refused["ok"]!!.jsonPrimitive.boolean)
        assertEquals("ssh: connect to host 127.0.0.1 port 1: Connection refused", refused.text("error"))
        assertEquals(listOf("-p", "1", "nobody@127.0.0.1"), calls.last().subList(5, 8), "the port goes to -p")
    }

    /** `claude auth login` as far as the add-on sees it: the link, then a code on its terminal, then the credentials. */
    private fun fakeClaude() = executable(
        "claude-login",
        """
        printf 'Opening browser...\nBrowser did not open? Use the url below to sign in:\n\nhttps://claude.com/cai/oauth/authorize?code=true&state=s1\n\nPaste code here if prompted > '
        read code
        if [ "${'$'}code" = "good-code" ]; then
          mkdir -p "${'$'}HOME/.claude"
          printf '{"claudeAiOauth":{"accessToken":"at","refreshToken":"rt","expiresAt":1900000000000,"subscriptionType":"max"}}' > "${'$'}HOME/.claude/.credentials.json"
          echo "Login successful."
        else
          echo "OAuth error: Invalid code=${'$'}code"; exit 1
        fi
        """.trimIndent(),
    )

    @Test
    fun claudeSignInHandsTheCodeToTheCliOnItsTerminalAndRestartsTheDaemon() {
        var options: JsonObject? = json("""{"claude_code_oauth_token": "sk-ant-oat01-x"}""")
        var restarts = 0
        val login = ClaudeLogin(home, { options }, { restarts++ }, listOf("script", "-q", "-f", "-e", "-c", fakeClaude().path, "/dev/null"))
        assertEquals("token", login.auth().text("method"), "the options' token before any sign-in")

        val started = login.start()
        assertEquals("https://claude.com/cai/oauth/authorize?code=true&state=s1", started.text("url"))
        assertEquals(true, started["running"]!!.jsonPrimitive.boolean)
        val wrong = login.code("bad-one")
        assertEquals(false, wrong["ok"]!!.jsonPrimitive.boolean)
        assertTrue(wrong.text("error")!!.contains("Invalid code=…"), "the code is not repeated: ${wrong.text("error")}")
        assertEquals("no sign-in in progress; start again", login.code("good-code").text("error"))
        assertEquals(0, restarts)

        login.start()
        assertEquals("code required", login.code("  ").text("error"))
        assertEquals(true, login.code(" good-code ")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, restarts, "the daemon starts again with the sign-in")
        val auth = login.status()["auth"]!!.jsonObject
        assertEquals(listOf("true", "login", "max", "1900000000000"), listOf("signed_in", "method", "plan", "expires_at").map { auth[it]!!.jsonPrimitive.content })
        assertEquals(false, login.status()["running"]!!.jsonPrimitive.boolean)

        assertEquals("token", login.logout().text("method"), "signed out, the options' token is left")
        assertTrue(!login.credentials.exists())
        assertEquals(2, restarts)
        options = null
        assertEquals("false", login.auth()["signed_in"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCancelledSignInStopsTheCliAndANewOneStartsClean() {
        val login = ClaudeLogin(home, { null }, {}, listOf("script", "-q", "-f", "-e", "-c", fakeClaude().path, "/dev/null"))
        login.start()
        login.cancel()
        waitUntil("the cancelled sign-in to stop") { login.status()["running"]!!.jsonPrimitive.content == "false" }
        val again = login.start()
        assertEquals(true, again["running"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, again["done"], "the stopped attempt does not report over the new one")
        login.cancel()
    }

    /** The daemon's CLI: `daemon enroll` prints the link and code and waits for the person, `daemon status` reports. */
    private inner class Cli : Dieter {
        val calls = mutableListOf<List<String>>()
        var status = """{"enrolled": false}"""

        override fun run(args: List<String>, timeout: Duration): JsonElement? {
            calls += args
            return when {
                args.containsAll(listOf("daemon", "status")) -> Json.parseToJsonElement(status)
                args.containsAll(listOf("device", "status")) -> Json.parseToJsonElement("""{"state": "TRUST_STATE_TRUSTED"}""")
                else -> null
            }
        }
    }

    @Test
    fun anEnrollmentShowsTheLinkAndCodeThenRestartsTheDaemonOnceAuthorized() {
        val authorized = File(home, "authorized")
        val bin = executable(
            "dieter",
            """
            echo "args: $*"
            echo "Authorize this daemon with GitHub:"
            echo "  https://gateway.example.net:4443/device"
            echo "Code: ABCD-1234"
            while [ ! -e "${authorized.path}" ]; do sleep 0.1; done
            echo "Enrolled Garden Pi as d_test1."
            """.trimIndent(),
        )
        var restarts = 0
        val cli = Cli()
        val options = json("""{"gateway_url": "https://gateway.example.net:4443/", "machine_name": "Garden Pi!"}""")
        val enrollment = Enrollment(bin.path, File(home, "store"), home, cli, { options }, { restarts++ })

        assertFailsWith<CliError> { enrollment.enroll(json("""{"url": "http://plain.example.net"}""")) }
        val started = enrollment.enroll(json("""{"url": " "}"""))
        assertEquals("https://gateway.example.net:4443/device", started.text("url"))
        assertEquals("ABCD-1234", started.text("code"))
        assertEquals("https://gateway.example.net:4443", started.text("gateway"), "the options' gateway, without the slash")
        assertEquals(started.text("url"), enrollment.enroll(JsonObject(emptyMap())).text("url"), "one enrollment at a time")
        assertFailsWith<CliError> { enrollment.unenroll() }

        authorized.createNewFile()
        waitUntil("the enrollment to finish") { enrollment.status()["done"] != JsonNull }
        val done = enrollment.status()["done"]!!.jsonObject
        assertEquals(listOf("true", "Garden Pi", "d_test1"), listOf("ok", "daemon_name", "daemon_id").map { done[it]!!.jsonPrimitive.content })
        assertEquals(1, restarts)

        cli.status = """{"enrolled": true, "daemonId": "d_test1", "daemonName": "Garden Pi", "gatewayUrl": "https://gateway.example.net:4443", "gatewayState": "connected", "gatewayLastError": "", "gatewayConnectedAt": "2026-10-30T15:00:00Z"}"""
        val gateway = enrollment.gateway()
        assertEquals("Garden Pi!", gateway.text("name"), "the name as the options give it; enroll cleaned it for the CLI")

        assertEquals(listOf("true", "d_test1", "connected", "TRUSTED"), listOf("enrolled", "daemon_id", "state", "trust").map { gateway[it]!!.jsonPrimitive.content })
        assertEquals("true", gateway["enroll"]!!.jsonObject["done"]!!.jsonObject["ok"]!!.jsonPrimitive.content)

        assertEquals(true, enrollment.unenroll()["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("--store", File(home, "store").path, "daemon", "unenroll"), cli.calls.last { "unenroll" in it })
        assertEquals(2, restarts)
        assertNull(enrollment.status().text("url"))
    }

    @Test
    fun gatewayModeDefaultsToEmbeddedAndSwitchesTwoWaysWithoutLosingState() {
        val store = File(home, "mode-store")
        assertEquals("embedded", GatewayMode.of(json("""{}""")))
        assertEquals("embedded", GatewayMode.of(json("""{"gateway_mode": "embedded", "gateway_url": "https://gw.example"}""")))
        assertEquals("external", GatewayMode.of(json("""{"gateway_mode": "external"}""")))
        assertEquals("external", GatewayMode.of(json("""{"gateway_url": "https://gw.example"}""")))

        // Populate embedded state
        GatewayMode.switchTo(store, "embedded")
        File(store, "daemon").mkdirs()
        File(store, "peers").mkdirs()
        File(store, "checkouts").mkdirs()
        File(store, "daemon/identity.json").writeText("""{"id":"d_emb","gatewayUrl":"http://127.0.0.1:4243"}""")
        File(store, "daemon/identity-key.pem").writeText("emb-key")
        File(store, "daemon/roster.pb").writeText("emb-roster")
        File(store, "daemon/trust.json").writeText("""{"syncedAt":"2026-11-01T00:00:00Z"}""")
        File(store, "peers/identity.json").writeText("""{"account":"emb-acc","daemonId":"d_emb"}""")
        File(store, "checkouts/c1.json").writeText("""{"projectId":"p_emb"}""")

        // Switch to external: embedded files are archived and active store is clean for external enrollment
        assertTrue(GatewayMode.switchTo(store, "external"))
        assertTrue(!File(store, "daemon/identity.json").exists())
        assertTrue(!File(store, "checkouts/c1.json").exists())
        File(store, "daemon").mkdirs()
        File(store, "peers").mkdirs()
        File(store, "checkouts").mkdirs()
        File(store, "daemon/identity.json").writeText("""{"id":"d_ext","gatewayUrl":"https://gw.example:4443"}""")
        File(store, "peers/identity.json").writeText("""{"account":"ext-acc","daemonId":"d_ext"}""")
        File(store, "checkouts/c1.json").writeText("""{"projectId":"p_ext"}""")

        // Switch back to embedded: embedded files are restored, external files are archived
        assertTrue(GatewayMode.switchTo(store, "embedded"))
        assertTrue(File(store, "daemon/identity.json").readText().contains("d_emb"))
        assertTrue(File(store, "peers/identity.json").readText().contains("emb-acc"))
        assertTrue(File(store, "checkouts/c1.json").readText().contains("p_emb"))

        // Switch to external again: external files are restored intact
        assertTrue(GatewayMode.switchTo(store, "external"))
        assertTrue(File(store, "daemon/identity.json").readText().contains("d_ext"))
        assertTrue(File(store, "peers/identity.json").readText().contains("ext-acc"))
        assertTrue(File(store, "checkouts/c1.json").readText().contains("p_ext"))
    }

    @Test
    fun embeddedModeApprovesLocalPairingAndRefusesManualEnrollOrUnenroll() {
        val cli = Cli()
        val options = json("""{"gateway_mode": "embedded", "machine_name": "Home Assistant"}""")
        val enrollment = Enrollment("/bin/false", File(home, "emb-store"), home, cli, { options }, {})
        assertEquals("embedded", enrollment.gateway().text("mode"))
        assertFailsWith<CliError> { enrollment.enroll(json("""{"url": "https://gw.example:4443"}""")) }
        assertFailsWith<CliError> { enrollment.unenroll() }
        assertEquals(true, enrollment.approveEmbedded("ABCD-EFGH-JKMN-PQRS")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("--store", File(home, "emb-store").path, "device", "approve", "--format", "json", "ABCD-EFGH-JKMN-PQRS"),
            cli.calls.last(),
        )
    }
}
