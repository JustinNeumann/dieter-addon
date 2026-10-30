package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The add-on's own SSH key: one ed25519 key in [home]/.ssh (survives updates), made on the first start. People
 * authorize its public key on the machines Dieter should reach (Git remotes, other daemons); the first connection to a
 * host records its key (accept-new), later ones check it. ssh and git find it through HOME=[home], without any env.
 */
class Ssh(
    private val home: File,
    private val run: (List<String>, Duration, Map<String, String>) -> Command? = { cmd, timeout, env -> Command.run(cmd, timeout = timeout, env = env) },
) {
    private val dir = File(home, ".ssh")
    val key = File(dir, "id_ed25519")

    /** The key (made once) and the config that makes ssh use it. */
    fun setup() {
        dir.mkdirs()
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        if (!key.exists()) {
            exec(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "dieter@home-assistant", "-f", key.path), 60.seconds)
            Log.line("ssh: generated ${key.path}")
        }
        val config = File(dir, "config")
        val want = "# written by the Dieter add-on\n" +
            "Host *\n" +
            "  IdentityFile ${key.path}\n" +
            "  IdentitiesOnly yes\n" +
            "  StrictHostKeyChecking accept-new\n" +
            "  UserKnownHostsFile ${dir.path}/known_hosts\n" +
            "  ServerAliveInterval 30\n"
        if (!config.exists() || config.readText() != want) writePrivate(config, want)
    }

    /** {public_key, fingerprint}: both null before the key exists. */
    fun publicKey(): JsonObject {
        val public = File(key.path + ".pub")
        if (!public.exists()) return buildJsonObject { put("public_key", null as String?); put("fingerprint", null as String?) }
        val listed = run(listOf("ssh-keygen", "-l", "-f", public.path), 10.seconds, emptyMap())
        val fingerprint = listed?.takeIf { it.exit == 0 }?.stdout?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
        return buildJsonObject {
            put("public_key", public.readText().trim())
            put("fingerprint", fingerprint)
        }
    }

    /** `ssh -o BatchMode=yes TARGET uname -n` with the add-on's key: {ok, output} when the key is authorized, else {ok: false, error}. */
    fun test(target: String): JsonObject {
        val t = target.trim()
        if (!TARGET.matches(t)) return failed("target must be user@host or host (optionally :port)")
        val port = Regex(":(\\d{1,5})$").find(t)?.groupValues?.get(1)
        val host = if (port != null) t.substringBeforeLast(":") else t
        val cmd = listOf("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8") + (if (port != null) listOf("-p", port) else emptyList()) +
            listOf(host, "uname", "-n")
        val done = run(cmd, 30.seconds, mapOf("HOME" to home.path)) ?: return failed("timeout after 30 s")
        if (done.exit == 0) return buildJsonObject { put("ok", true); put("output", done.stdout.trim().take(200)) }
        return failed((done.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "ssh failed").take(300))
    }

    private fun exec(cmd: List<String>, timeout: Duration) {
        val done = run(cmd, timeout, emptyMap()) ?: throw IllegalStateException("${cmd.first()}: timeout")
        check(done.exit == 0) { "${cmd.first()}: " + done.stderr.ifBlank { done.stdout }.trim().take(300) }
    }

    private fun failed(error: String) = buildJsonObject { put("ok", false); put("error", JsonPrimitive(error)) }

    companion object {
        val TARGET = Regex("^(?:[A-Za-z0-9._-]{1,64}@)?[A-Za-z0-9][A-Za-z0-9.:-]{0,253}(?::\\d{1,5})?$")
    }
}
