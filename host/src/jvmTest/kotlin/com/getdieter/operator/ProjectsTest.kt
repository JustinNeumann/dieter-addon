package com.getdieter.operator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class ProjectsTest {
    private val dir = kotlin.io.path.createTempDirectory("projects").toFile()

    /** The daemon's project list, grown by `project create`. */
    private inner class Daemon : Dieter {
        val projects = mutableListOf<String>()
        val creates = mutableListOf<List<String>>()

        override fun run(args: List<String>, timeout: Duration): JsonElement? =
            when (args.take(2)) {
                listOf("project", "list") -> Json.parseToJsonElement("""{"projects":[${projects.joinToString(",")}]}""")
                listOf("project", "create") -> {
                    creates += args
                    val id = "p_${projects.size + 1}"
                    projects += """{"id":"$id","name":"${args[args.indexOf("--name") + 1]}","path":"${args.last()}"}"""
                    Json.parseToJsonElement("""{"project":{"id":"$id"}}""")
                }
                else -> null
            }
    }

    private val daemon = Daemon()
    private val clones = mutableListOf<List<String>>()
    private val fakeClone: (List<String>, File?) -> String = { args, _ ->
        clones += args
        File(args.last(), ".git").mkdirs()
        ""
    }

    private fun body(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `a repository is cloned once under its name and registered once`() {
        val projects = Projects(daemon, dir, { JsonObject(emptyMap()) }, fakeClone)
        val first = projects.add(body("""{"git_url":"https://github.com/octocat/Hello-World.git ","branch":"main"}"""))
        assertEquals("""{"ok":true,"id":"p_1","name":"Hello-World","existed":false}""", first.toString())
        assertEquals(listOf("clone", "--quiet", "--branch", "main", "https://github.com/octocat/Hello-World.git", dir.resolve("Hello-World").path), clones.single())
        assertEquals(listOf("--base-branch", "main"), daemon.creates.single().let { it.subList(it.size - 3, it.size - 1) })
        val again = projects.add(body("""{"git_url":"https://github.com/octocat/Hello-World.git"}"""))
        assertEquals(true, again["existed"]!!.jsonPrimitive.boolean)
        assertEquals(1, clones.size)
        assertEquals(1, daemon.creates.size)
        assertEquals("p_2", projects.add(body("""{"git_url":"git@github.com:octocat/Spoon-Knife.git","name":"spoon"}"""))["id"]!!.jsonPrimitive.content)
        assertTrue(dir.resolve("spoon/.git").isDirectory)
    }

    @Test
    fun `names, URLs and branches are checked before anything runs`() {
        val projects = Projects(daemon, dir, { JsonObject(emptyMap()) }, fakeClone)
        for (bad in listOf("""{"git_url":"file:///etc"}""", """{"git_url":"https://github.com/a/b","name":"../x"}""", """{"git_url":"https://github.com/a/b","branch":"a b"}""",
                           """{"git_url":"https://github.com/a/b; rm -rf /"}""", """{"folder":".hidden"}""", """{}"""))
            assertFailsWith<BadRequest>(bad) { projects.add(body(bad)) }
        assertTrue(clones.isEmpty() && daemon.creates.isEmpty())
        assertFailsWith<BadRequest> { Projects(daemon, dir, { null }, fakeClone).add(body("""{"folder":"x"}""")) }
        assertEquals("Hello-World", Projects.repoName("ssh://git@host:22/octocat/Hello-World.git/"))
        assertEquals("repo", Projects.repoName("git@host:repo.git"))
    }

    @Test
    fun `a folder becomes an empty repository with one commit, an existing one is adopted as it is`() {
        val projects = Projects(daemon, dir, { JsonObject(emptyMap()) })
        assertEquals(false, projects.add(body("""{"folder":"notes"}"""))["existed"]!!.jsonPrimitive.boolean)
        assertEquals("Initial commit\n", git(listOf("log", "--format=%s"), dir.resolve("notes")))
        assertEquals("Dieter\n", git(listOf("log", "--format=%an"), dir.resolve("notes")))
        assertEquals(true, projects.add(body("""{"folder":"notes"}"""))["existed"]!!.jsonPrimitive.boolean)
        dir.resolve("kept").mkdirs()
        dir.resolve("kept/file.txt").writeText("mine")
        projects.add(body("""{"folder":"kept"}"""))
        assertTrue(dir.resolve("kept/.git").isDirectory)
        assertEquals("mine", dir.resolve("kept/file.txt").readText())
        assertEquals(2, daemon.creates.size)
    }

    @Test
    fun `a failing git answers 502 with git's message`() {
        val e = assertFailsWith<CliError> { git(listOf("rev-parse", "HEAD"), kotlin.io.path.createTempDirectory().toFile()) }
        assertTrue(e.message!!.startsWith("git: fatal: not a git repository"), e.message)
    }
}
