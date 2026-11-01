package com.getdieter.operator

import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The add-on's repositories (Projects → Add): a Git URL cloned into /data/projects/<name>, or a new empty repository
 * there (an existing folder is adopted), registered with the daemon once. Private GitHub repositories clone with the
 * add-on's github_token through the git configuration in /data (Setup.git).
 */
class Projects(
    private val dieter: Dieter,
    private val dir: File,
    /** The add-on's options (/data/options.json), null outside an add-on. */
    private val options: () -> JsonObject?,
    private val runGit: (List<String>, File?) -> String = { args, cwd -> git(args, cwd) },
) {
    /** {git_url, name?, branch?} clones; {folder} without git_url creates or adopts a folder. */
    fun add(body: JsonObject): JsonObject {
        if (options() == null) throw BadRequest("adding repositories works in the add-on only")
        val folder = body.text("folder")
        return if (folder.isNotEmpty() && body.text("git_url").isEmpty()) addFolder(folder)
        else addRepository(body.text("git_url"), body.text("name"), body.text("branch"))
    }

    fun addRepository(gitUrl: String, givenName: String = "", givenBranch: String = ""): JsonObject {
        val url = gitUrl.trim()
        val name = givenName.trim().ifEmpty { repoName(url) }
        val branch = givenBranch.trim()
        if (!GIT_URL.matches(url)) throw BadRequest("git_url must be an https://, ssh:// or git@host:path URL")
        if (!NAME.matches(name)) throw BadRequest("name may contain letters, digits, . _ - (max 64)")
        if (branch.isNotEmpty() && !BRANCH.matches(branch)) throw BadRequest("invalid branch")
        dir.mkdirs()
        val path = dir.resolve(name)
        if (!path.resolve(".git").isDirectory) {
            Log.line("projects: cloning $url -> $path")
            runGit(listOf("clone", "--quiet") + (if (branch.isNotEmpty()) listOf("--branch", branch) else emptyList()) + listOf(url, path.path), null)
        }
        return register(path, name, if (branch.isNotEmpty()) listOf("--base-branch", branch) else emptyList(), "")
    }

    fun addFolder(givenName: String): JsonObject {
        val name = givenName.trim()
        if (!NAME.matches(name)) throw BadRequest("name may contain letters, digits, . _ - (max 64)")
        val path = dir.resolve(name)
        path.mkdirs()
        if (!path.resolve(".git").isDirectory) {
            Log.line("projects: initialising $path")
            runGit(listOf("init", "--quiet", "--initial-branch=main"), path)
            val ident = if (options()?.text("git_user").isNullOrBlank()) listOf("-c", "user.name=Dieter", "-c", "user.email=dieter@home-assistant.local") else emptyList()
            runGit(ident + listOf("commit", "--quiet", "--allow-empty", "-m", "Initial commit"), path)
        }
        return register(path, name, emptyList(), "folder ")
    }

    /** Registers any existing git repositories in [dir] that the current daemon replica does not yet know. */
    fun adoptExisting() {
        val folders = dir.listFiles()?.filter { it.isDirectory && NAME.matches(it.name) && it.resolve(".git").isDirectory }.orEmpty()
        if (folders.isEmpty()) return
        val known = runCatching {
            dieter.run("project", "list", "--format", "json", timeout = 20.seconds).unwrap("projects").associateBy { it.str("path") }
        }.getOrDefault(emptyMap())
        for (folder in folders.sortedBy { it.name }) {
            if (folder.path !in known) {
                runCatching { register(folder, folder.name, emptyList(), "existing ") }
                    .onFailure { Log.line("projects: ${folder.name}: ${it.message}") }
            }
        }
    }

    /** The daemon's project at [path], created when it has none; `existed` tells which. */
    private fun register(path: File, name: String, extra: List<String>, what: String): JsonObject {
        val known = dieter.run("project", "list", "--format", "json", timeout = 20.seconds).unwrap("projects").associateBy { it.str("path") }
        known[path.path]?.let { p ->
            return answer(p.str("id"), p.str("name").ifEmpty { name }, existed = true)
        }
        val out = dieter.run(listOf("project", "create", "--name", name, "--format", "json") + extra + path.path, 60.seconds)
        val id = when (out) {
            is JsonObject -> ((out["project"] as? JsonObject) ?: out).str("id")
            is JsonPrimitive -> out.content
            else -> ""
        }
        Log.line("projects: registered $what$name as $id")
        return answer(id, name, existed = false)
    }

    private fun answer(id: String, name: String, existed: Boolean) =
        buildJsonObject { put("ok", true); put("id", id); put("name", name); put("existed", existed) }

    companion object {
        // (?U): \w as in the bridge's Python patterns
        val GIT_URL = Regex("""(?U)^(https://[\w.\-]+(:\d+)?/[\w.\-/~]+|git@[\w.\-]+:[\w.\-/~]+|ssh://[\w.\-@]+(:\d+)?/[\w.\-/~]+)$""")
        val NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")
        private val BRANCH = Regex("""(?U)^[\w./-]{1,100}$""")

        fun repoName(url: String) = url.trimEnd('/').substringAfterLast("/").substringAfterLast(":").removeSuffix(".git")
    }
}

/** A string field; "" when it is missing or no string. */
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

