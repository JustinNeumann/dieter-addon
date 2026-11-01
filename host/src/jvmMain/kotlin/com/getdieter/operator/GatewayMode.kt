package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The add-on's two-way switchable gateway mode (`embedded` vs `external`).
 *
 * - In `embedded` mode (the default), `run.sh` starts the local `dieter-gateway` on loopback (`127.0.0.1:4243`),
 *   [prepareDaemon] enrolls `/data/dieter`, binds its peer store and initializes its device roster before the daemon
 *   starts, and [approveEmbeddedClient] approves the local web host's pairing request automatically.
 * - In `external` mode, the add-on connects to the user's remote gateway (`gateway_url` or enrolled from the UI).
 * - [switchTo] preserves each mode's daemon identity, device roster, peer binding and checkout attachments under
 *   `/data/dieter/gateway-modes/<mode>/`, so switching `embedded <-> external` in the add-on options never loses
 *   either mode's enrollment or local state.
 */
object GatewayMode {
    const val EMBEDDED = "embedded"
    const val EXTERNAL = "external"
    const val DEFAULT_EMBEDDED_URL = "http://127.0.0.1:4243"

    private val MODE_PATHS = listOf(
        "daemon/identity.json",
        "daemon/identity-key.pem",
        "daemon/roster.pb",
        "daemon/trust.json",
        "peers/identity.json",
        "checkouts",
    )

    /** Resolves `embedded` or `external` from the add-on's [options]. */
    fun of(options: JsonObject?): String {
        if (options == null) return EXTERNAL
        return when (options.text("gateway_mode").trim().lowercase()) {
            EMBEDDED -> EMBEDDED
            EXTERNAL -> EXTERNAL
            else -> if (options.text("gateway_url").trim().isNotEmpty()) EXTERNAL else EMBEDDED
        }
    }

    fun machineName(options: JsonObject?): String =
        options?.text("machine_name").orEmpty().trim().replace(Regex("[^A-Za-z0-9 ._-]"), "").take(40).ifEmpty { "home-assistant" }

    fun gatewayUrl(env: Map<String, String> = System.getenv()): String =
        env["DIETER_EMBEDDED_GATEWAY_URL"]?.trim()?.ifEmpty { null } ?: DEFAULT_EMBEDDED_URL

    /**
     * Switches [store]'s active daemon identity, trust, peer binding and checkouts to [targetMode] (`embedded` or
     * `external`), archiving the previous mode's files under `gateway-modes/<previous>/` and restoring any saved
     * files from `gateway-modes/<targetMode>/`. Returns true when a mode switch occurred.
     */
    fun switchTo(store: File, targetMode: String): Boolean {
        require(targetMode == EMBEDDED || targetMode == EXTERNAL) { "unsupported gateway mode: $targetMode" }
        val modesRoot = File(store, "gateway-modes")
        val activeFile = File(modesRoot, "active")
        val currentMode = detectCurrentMode(store, activeFile, targetMode)
        if (currentMode == targetMode) {
            if (!activeFile.exists() || activeFile.readText().trim() != targetMode) {
                modesRoot.mkdirs()
                writePrivate(activeFile, "$targetMode\n")
            }
            return false
        }

        val archiveDir = File(modesRoot, currentMode)
        archiveDir.deleteRecursively()
        for (rel in MODE_PATHS) {
            val src = File(store, rel)
            if (src.exists()) movePath(src, File(archiveDir, rel))
        }

        val restoreDir = File(modesRoot, targetMode)
        if (restoreDir.isDirectory) {
            for (rel in MODE_PATHS) {
                val saved = File(restoreDir, rel)
                if (saved.exists()) movePath(saved, File(store, rel))
            }
            restoreDir.deleteRecursively()
        }

        modesRoot.mkdirs()
        writePrivate(activeFile, "$targetMode\n")
        Log.line("gateway: switched daemon store from $currentMode to $targetMode")
        return true
    }

    /** Prepares [store] before `dieter daemon start`: switches mode state and, in `embedded` mode, enrolls with the loopback gateway. */
    fun prepareDaemon(store: File, options: JsonObject?, env: Map<String, String>) {
        val mode = of(options)
        switchTo(store, mode)
        if (mode != EMBEDDED) return
        val bin = File(env["DIETER_GATEWAY_BIN"] ?: "/usr/local/bin/dieter-gateway")
        if (!bin.canExecute()) return
        val url = gatewayUrl(env)
        val name = machineName(options)
        val done = Command.run(
            listOf(bin.path, "-prepare-daemon", store.path, "-daemon-name", name, "-gateway", url),
            timeout = 30.seconds,
            env = mapOf("HOME" to (env["HOME"] ?: "/data")),
        )
        if (done == null) Log.line("gateway: prepare-daemon timed out")
        else if (done.exit != 0) Log.line("gateway: prepare-daemon failed (${done.exit}): ${(done.stderr + done.stdout).trim().takeLast(300)}")
    }

    /** Approves the local web host's [pairingCode] in `embedded` mode. */
    fun approveEmbeddedClient(dieter: Dieter, store: File, options: JsonObject?, pairingCode: String): JsonObject {
        if (of(options) != EMBEDDED) throw BadRequest("embedded pairing approval is only available in embedded gateway mode")
        val code = pairingCode.trim().ifEmpty { throw BadRequest("pairing_code required") }
        dieter.run(listOf("--store", store.path, "device", "approve", "--format", "json", code), 20.seconds)
        return buildJsonObject { put("ok", true) }
    }

    private fun detectCurrentMode(store: File, activeFile: File, fallback: String): String {
        if (activeFile.isFile) {
            val recorded = activeFile.readText().trim()
            if (recorded == EMBEDDED || recorded == EXTERNAL) return recorded
        }
        val identityFile = File(store, "daemon/identity.json")
        if (identityFile.isFile) {
            val url = runCatching {
                (Json.parseToJsonElement(identityFile.readText()) as? JsonObject)?.text("gatewayUrl").orEmpty().trim()
            }.getOrDefault("")
            if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) return EMBEDDED
            if (url.isNotEmpty()) return EXTERNAL
        }
        return fallback
    }

    private fun movePath(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        dst.deleteRecursively()
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
    }
}
