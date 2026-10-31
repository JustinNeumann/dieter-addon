package com.getdieter.operator

import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SettingsTest {
    private val file = File.createTempFile("bridge_settings", ".json").apply { delete() }
    private var lookups = 0
    private var found: String? = "ai_task.ollama"
    private var clock = 0L
    private val ha = HomeAssistant { path, _, _ ->
        assertEquals("template", path)
        lookups++
        found?.let { """["$it"]""" } ?: "[]"
    }
    private val settings = Settings(file, OllamaEntity(ha, Duration.ofMinutes(10)) { clock }) { "d_addon" }

    private fun body(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `defaults without a file, and a broken or odd file reads as the defaults`() {
        assertEquals(Settings.Values("openai", emptyList(), 14), settings.read())
        file.writeText("{not json")
        assertEquals(Settings.Values("openai", emptyList(), 14), settings.read())
        file.writeText("""{"title_model":"gpt","worktree_retention_days":true,"board_order":"b1"}""")
        assertEquals(Settings.Values("openai", emptyList(), 14), settings.read())
        file.writeText("""{"title_model":"ollama","worktree_retention_days":30,"board_order":["b2","b1"]}""")
        assertEquals(Settings.Values("ollama", listOf("b2", "b1"), 30), settings.read())
    }

    @Test
    fun `an update checks its values like the bridge did and keeps what it does not name`() {
        settings.order(body("""{"ids":["b1"]}"""))
        val v = settings.update(body("""{"worktree_retention_days":" 30 "}"""))
        assertEquals(30, v["worktree_retention_days"]!!.jsonPrimitive.int)
        assertEquals(listOf("b1"), v["board_order"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (bad in listOf("""{"worktree_retention_days":-1}""", """{"worktree_retention_days":3651}""", """{"worktree_retention_days":1.5}""",
                           """{"worktree_retention_days":true}""", """{"worktree_retention_days":"x"}""", """{"title_model":"gpt"}""", """{"title_model":1}"""))
            assertFailsWith<BadRequest>(bad) { settings.update(body(bad)) }
        assertEquals(Settings.Values("openai", listOf("b1"), 30), settings.read())
        assertEquals(0, settings.update(body("""{"worktree_retention_days":0}"""))["worktree_retention_days"]!!.jsonPrimitive.int)
    }

    @Test
    fun `Antigravity groups are left out of the summary and taken back, one at a time`() {
        settings.order(body("""{"ids":["b1"]}"""))
        var v = settings.update(body("""{"antigravity_inclusion":{"group":"Gemini 4 Argon","included":false}}"""))
        assertEquals(listOf("Gemini 4 Argon"), v["antigravity_excluded"]!!.jsonArray.map { it.jsonPrimitive.content })
        settings.update(body("""{"antigravity_inclusion":{"group":"Gemini Models","included":false}}"""))
        settings.update(body("""{"antigravity_inclusion":{"group":"Gemini Models","included":false}}"""))
        v = settings.update(body("""{"antigravity_inclusion":{"group":"Gemini 4 Argon","included":true}}"""))
        assertEquals(listOf("Gemini Models"), v["antigravity_excluded"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Settings.Values("openai", listOf("b1"), 14, listOf("Gemini Models")), settings.read())
        for (bad in listOf("""{"antigravity_inclusion":{"group":"","included":false}}""", """{"antigravity_inclusion":{"group":"G"}}""",
                           """{"antigravity_inclusion":{"group":"G","included":"no"}}""", """{"antigravity_inclusion":"G"}""",
                           """{"antigravity_inclusion":{"group":"${"x".repeat(81)}","included":false}}"""))
            assertFailsWith<BadRequest>(bad) { settings.update(body(bad)) }
        // other settings keep the list, and a file from before it reads as nothing left out
        settings.update(body("""{"worktree_retention_days":30}"""))
        assertEquals(listOf("Gemini Models"), settings.read().antigravityExcluded)
        file.writeText("""{"title_model":"openai","worktree_retention_days":14,"board_order":[]}""")
        assertEquals(emptyList(), settings.read().antigravityExcluded)
    }

    @Test
    fun `boards dragged on the page go first, others keep their rank after them`() {
        settings.order(body("""{"ids":["a","b","c","d"]}"""))
        val v = settings.order(body("""{"ids":["c","a"]}"""))
        assertEquals(true, v["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("c", "a", "b", "d"), v["board_order"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (bad in listOf("""{}""", """{"ids":[]}""", """{"ids":["a",""]}""", """{"ids":[1]}""", """{"ids":"a"}"""))
            assertFailsWith<BadRequest>(bad) { settings.order(body(bad)) }
    }

    @Test
    fun `the palette picked on one page is kept for every device, and other settings keep it`() {
        assertEquals("", settings.view()["palette"]!!.jsonPrimitive.content)
        assertEquals("dreamfall", settings.update(body("""{"palette":"dreamfall"}"""))["palette"]!!.jsonPrimitive.content)
        settings.update(body("""{"worktree_retention_days":30}"""))
        settings.order(body("""{"ids":["b1"]}"""))
        assertEquals(Settings.Values("openai", listOf("b1"), 30, palette = "dreamfall"), settings.read())
        for (bad in listOf("""{"palette":""}""", """{"palette":1}""", """{"palette":"Dream Fall"}""", """{"palette":"x${"y".repeat(40)}"}""", """{"palette":null}"""))
            assertFailsWith<BadRequest>(bad) { settings.update(body(bad)) }
        assertEquals("dreamfall", settings.read().palette)
        // a file from before it, or an odd value, reads as none picked
        file.writeText("""{"title_model":"openai","worktree_retention_days":14,"board_order":[],"palette":"<b>"}""")
        assertEquals("", settings.read().palette)
    }

    @Test
    fun `Ollama titles name the add-on's daemon only while chosen and available, and the entity is looked up again`() {
        var v = settings.view()
        assertEquals("", v["titles_daemon_id"]!!.jsonPrimitive.content)
        val ollama = v["title_models"]!!.jsonArray[1].jsonObject
        assertEquals(true, ollama["available"]!!.jsonPrimitive.boolean)
        assertEquals("ai_task.ollama", ollama["detail"]!!.jsonPrimitive.content)
        v = settings.update(body("""{"title_model":"ollama"}"""))
        assertEquals("d_addon", v["titles_daemon_id"]!!.jsonPrimitive.content)
        assertEquals(2, lookups)                       // choosing the model forgets the cached entity
        settings.view()
        assertEquals(2, lookups)                       // cached
        found = null
        clock += Duration.ofMinutes(11).toMillis()
        v = settings.view()
        assertEquals(3, lookups)
        assertEquals("", v["titles_daemon_id"]!!.jsonPrimitive.content)
        assertEquals("No AI Task entity of the Ollama integration found; OpenAI titles instead", v["title_models"]!!.jsonArray[1].jsonObject["detail"]!!.jsonPrimitive.content)
        val outside = Settings(file, OllamaEntity(null)) { "d_addon" }.view()
        assertEquals("Works in the Home Assistant add-on only", outside["title_models"]!!.jsonArray[1].jsonObject["detail"]!!.jsonPrimitive.content)
    }
}
