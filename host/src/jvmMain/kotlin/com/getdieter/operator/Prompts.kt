package com.getdieter.operator

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Agent instructions (Settings → Agent instructions): the daemon's own prompt templates, which it renders into the
 * instructions of every turn of every harness: the template (every agent; a project's or board's own template
 * replaces it), then the board skill (cards) or the chat skill (standalone chats). `dieter prompt show|update`.
 */
class Prompts(private val dieter: Dieter) {
    /** Our key → (the CLI's field, its update flag, its reset name). */
    private class Key(val field: String, val flag: String, val reset: String)

    private val keys =
        linkedMapOf(
            "template" to Key("promptTemplate", "--template", "template"),
            "board_skill" to Key("boardSkillTemplate", "--board-skill", "board-skill"),
            "chat_skill" to Key("chatSkillTemplate", "--chat-skill", "chat-skill"),
        )

    /** The three texts, the variables they may use and the projects and boards with a template of their own. */
    fun view(): JsonObject {
        val shown = dieter.run("prompt", "show") as? JsonObject ?: JsonObject(emptyMap())
        val projects = dieter.run("project", "list", "--format", "json", timeout = 15.seconds).unwrap("projects")
        val boards = dieter.run("board", "list", "--format", "json", timeout = 15.seconds).unwrap("boards")
        val names = projects.associate { it.str("id") to it.str("name") }
        return buildJsonObject {
            for ((key, k) in keys) put(key, shown.str(k.field))
            put("variables", shown["variables"] as? JsonArray ?: JsonArray(emptyList()))
            put("overrides", buildJsonArray {
                for (p in projects) if (p.str("promptTemplate", "prompt_template").isNotBlank())
                    add(buildJsonObject { put("kind", "project"); put("id", p.str("id")); put("name", p.str("name")) })
                for (b in boards) if (b.str("prompt_template", "promptTemplate").isNotBlank())
                    add(buildJsonObject {
                        put("kind", "board"); put("id", b.str("id")); put("name", b.str("name"))
                        put("project", names[b.str("project_id", "projectId")]?.let(::JsonPrimitive) ?: JsonNull)
                    })
            })
        }
    }

    /** {template?, board_skill?, chat_skill?: text, reset?: [key]}. A reset text follows Dieter's default of later releases again. */
    fun update(body: JsonObject): JsonObject {
        val args = mutableListOf("prompt", "update")
        for ((key, k) in keys) {
            val value = body[key] ?: continue
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text.isNullOrBlank()) throw BadRequest("$key: text required (or reset it)")
            args += "${k.flag}=$text"                  // =: a text starting with "-" is no flag
        }
        val reset = body["reset"] ?: JsonArray(emptyList())
        val names = (reset as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
        if (names == null || names.any { it == null || it !in keys || it in body })
            throw BadRequest("reset: list of ${keys.keys.joinToString(", ")} (not also set)")
        for (name in names) args += listOf("--reset", keys.getValue(name!!).reset)
        if (args.size == 2) throw BadRequest("nothing to update")
        try {
            dieter.run(args, 30.seconds)
        } catch (e: CliError) {                        // "error: rpc error: code = InvalidArgument desc = template must …"
            if ("InvalidArgument" !in e.message.orEmpty()) throw e
            throw BadRequest(e.message.orEmpty().replace(Regex("^(error: )?rpc error: code = \\w+ desc = "), ""))
        }
        return view()
    }
}

/** The first of [keys] that holds a non-empty string ("" when none does), for the CLI's two spellings of a field. */
internal fun JsonObject.str(vararg keys: String): String =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
