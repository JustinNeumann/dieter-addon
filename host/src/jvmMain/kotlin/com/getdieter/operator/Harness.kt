package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The harness runtime (Claude Code / Codex / … workers, ~800 MB): the daemon's own download has a 15 min limit and
 * failed on the Pi, so the operator stages it (sha256 pinned from the fork release's signed SHA256SUMS) and hands it
 * to the daemon as DIETER_HARNESS_RUNTIME_DIR. The archive carries only node_modules (+ Bun); the runtime sources of
 * the same build are in the image ([sources], /opt/dieter-runtime, from the fork tag, see the Dockerfile) and are
 * laid over it. [version] is the fork release the image carries (the Dockerfile's DIETER_VERSION).
 */
class Harness(
    private val home: File,
    val version: String,
    private val arch: String,
    private val sources: File,
    private val pins: Map<String, String> = SHA256,
    private val run: (List<String>, File?, Duration) -> Command? = { cmd, cwd, timeout -> Command.run(cmd, cwd, timeout) },
) {
    val dir = File(home, "harness-staged/$version")
    /** DIETER_HARNESS_RUNTIME_DIR: the sources on top of the archive's node_modules. */
    val runtimeDir = File(dir, "runtime")
    private val staged get() = File(dir, ".staged").exists()

    private fun exec(cmd: List<String>, timeout: Duration, cwd: File? = null) {
        val done = run(cmd, cwd, timeout) ?: throw IllegalStateException("${cmd.first()}: timeout")
        check(done.exit == 0) { "${cmd.first()}: " + done.stderr.ifBlank { done.stdout }.trim().take(300).ifEmpty { "exit ${done.exit}" } }
    }

    private fun rmTree(path: File) = exec(listOf("rm", "-rf", path.path), 600.seconds)

    /** Downloads (once) and unpacks the archive for this platform into [dir]. */
    fun stage(download: (String, File) -> Unit = { url, to -> exec(listOf("curl", "-fsSL", "--retry", "3", "-o", to.path, url), 3600.seconds) }) {
        val sha = pins[arch] ?: return
        if (staged) return
        // A fork release that rebuilt the same archive (equal sha) takes over the staged runtime of the previous
        // version instead of downloading ~775 MB again; buildRuntimeDir() lays its sources over it.
        val parent = dir.parentFile
        for (name in parent.list().orEmpty().sorted()) {
            val marker = File(parent, "$name/.staged")
            if (name != version && marker.exists() && marker.readText() == sha) {
                rmTree(dir)
                check(File(parent, name).renameTo(dir)) { "could not move $name to $version" }
                Log.line("harness: reused the staged runtime $name (same archive) for $version")
                return
            }
        }
        val url = "https://github.com/$REPO/releases/download/v$version/${ASSET[arch]}"
        val tmp = File(dir.path + ".download.tar.gz")
        Log.line("harness: downloading ${ASSET[arch]} ($version)")
        parent.mkdirs()
        download(url, tmp)
        val got = sha256(tmp)
        if (got != sha) {
            tmp.delete()
            throw IllegalStateException("harness archive checksum mismatch (${got.take(12)}…)")
        }
        rmTree(dir)
        dir.mkdirs()
        exec(listOf("tar", "-xzf", tmp.path, "-C", dir.path), 1800.seconds)
        tmp.delete()
        File(dir, ".staged").writeText(sha)
        Log.line("harness: staged in $dir")
    }

    /** Lays the build's runtime sources over the archive's node_modules, again whenever the shipped sources change
     *  (their digest is kept next to the directory). */
    fun buildRuntimeDir() {
        val stale = File(home, "harnesses.yaml")   // catalog override of the pre-fork builds
        if (stale.exists()) {
            stale.delete()
            Log.line("harness: removed the old catalog override (the daemon's own catalog has Antigravity)")
        }
        if (!sources.isDirectory || !staged) return
        val names = sources.list().orEmpty().filter { it.endsWith(".mjs") || it.endsWith(".json") }.sorted()
        val digest = MessageDigest.getInstance("SHA-256").run {
            for (name in names) {
                update(name.toByteArray() + 0.toByte())
                update(File(sources, name).readBytes() + 0.toByte())
            }
            digest().hex()
        }
        val marker = File(dir, ".runtime-sources")
        if (File(runtimeDir, "runner.mjs").exists() && marker.exists() && marker.readText() == digest) return
        runtimeDir.mkdirs()
        val modules = File(dir, "harness/node_modules")
        if (!File(runtimeDir, "node_modules").isDirectory && modules.isDirectory) check(modules.renameTo(File(runtimeDir, "node_modules")))
        for (name in names) File(sources, name).copyTo(File(runtimeDir, name), overwrite = true)
        marker.writeText(digest)
        Log.line("harness: runtime sources staged in $runtimeDir (${names.size} files)")
    }

    /** Removes the staged runtimes of earlier versions (≈ 2.2 GB each) once this version is staged. */
    fun prune() {
        if (!staged) return
        for (name in dir.parentFile.list().orEmpty().sorted()) {
            if (name != version) {
                rmTree(File(dir.parentFile, name))
                Log.line("harness: removed the staged runtime $name")
            }
        }
    }

    /**
     * The daemon's OMP discovery wants Bun in $DIETER_HOME/runtime/tools/bun/<version> and otherwise downloads the
     * whole archive (~775 MB) for it, every discovery round (~6 min), and on the Pi never finishes inside its 10 min
     * limit. The staged archive carries the same package, so it is published there the way the daemon does
     * (internal/harness/bun.go): stage, run Bun's postinstall, check `bun --version`, rename into place. The
     * daemon's install lock is not taken (a JVM file lock is not the daemon's flock): this runs before the
     * supervisor starts the daemon, with no daemon left over (DaemonSupervisor.stopStrays).
     */
    fun seedBun() {
        val archived = File(dir, "bun")
        if (!staged || !archived.isDirectory) return
        val bun = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject["bunVersion"]!!.jsonPrimitive.content
        val root = File(home, "runtime/tools/bun")
        val final = File(root, bun)
        fun valid(d: File) = runCatching {
            run(listOf(File(d, "node_modules/.bin/bun").path, "--version"), null, 10.seconds)?.let { it.exit == 0 && it.stdout.trim() == bun } ?: false
        }.getOrDefault(false)

        root.mkdirs()
        // leftovers of interrupted daemon attempts: stages, quarantined installs, archive downloads
        for (name in root.list().orEmpty()) {
            if (name.startsWith(".stage-") || name.startsWith(".corrupt-") || name.startsWith(".download-")) rmTree(File(root, name))
        }
        if (valid(final)) return
        rmTree(final)
        val stage = Files.createTempDirectory(root.toPath(), ".stage-$bun-").toFile()
        try {
            exec(listOf("cp", "-a", "${archived.path}/.", stage.path), 600.seconds)
            exec(listOf("node", "node_modules/bun/install.js"), 600.seconds, cwd = stage)
            check(valid(stage)) { "the staged Bun does not report version $bun" }
            check(stage.renameTo(final)) { "could not move the staged Bun into place" }
        } finally {
            if (stage.exists()) rmTree(stage)
        }
        Log.line("harness: managed Bun $bun seeded in $final")
    }

    companion object {
        const val REPO = "JustinNeumann/dieter"
        /** The archives of the fork release in the Dockerfile's DIETER_VERSION (its SHA256SUMS); see DEPLOY.md. */
        val SHA256 = mapOf(
            "aarch64" to "df1c91d3fd562294e1911146994ba96f8dd0c1fdccdbfd3bbdc460b91fb5688b",
            "x86_64" to "0597dbc83fabe1e747f73314274b8af29c7a7504370bba499d96460d6d15be4c",
        )
        val ASSET = mapOf("aarch64" to "dieter-harness-runtime-linux-arm64.tar.gz", "x86_64" to "dieter-harness-runtime-linux-amd64.tar.gz")

        /** Python's platform.machine() names, which the archives go by. */
        fun machine(osArch: String = System.getProperty("os.arch")) = if (osArch == "amd64") "x86_64" else osArch

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    update(buffer, 0, n)
                }
            }
            digest().hex()
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
