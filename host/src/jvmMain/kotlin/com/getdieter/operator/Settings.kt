package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The add-on's own settings (Tools → Settings), kept in /data/bridge_settings.json: who titles a task created without
 * a title, how long a finished card's work folder is kept, the board order dragged on the page, and the Antigravity
 * quota groups left out of the Inbox's usage strip (Usage → Include in summary). The operator's titles and
 * housekeeping and the bridge's state stream (the board order) read the same file on every use.
 */
class Settings(private val file: File, private val ollama: OllamaEntity, private val daemonId: () -> String) {
    data class Values(val titleModel: String, val boardOrder: List<String>, val retentionDays: Int, val antigravityExcluded: List<String> = emptyList())

    fun read(): Values {
        val d = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val model = (d["title_model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val order = (d["board_order"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        val days = (d["worktree_retention_days"] as? JsonPrimitive)?.takeUnless { it.isString || it.content == "true" || it.content == "false" }?.intOrNull
        val excluded = (d["antigravity_excluded"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return Values(
            if (model in TITLE_MODELS) model!! else "openai",
            order.orEmpty(),
            days?.takeIf { it in 0..RETENTION_MAX_DAYS } ?: RETENTION_DEFAULT_DAYS,
            excluded.orEmpty(),
        )
    }

    /** {title_model?, worktree_retention_days?, antigravity_inclusion?: {group, included}}; answers the view. */
    fun update(body: JsonObject): JsonObject {
        var cur = read()
        body["title_model"]?.let { v ->
            val model = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (model !in TITLE_MODELS) throw BadRequest("title_model: " + TITLE_MODELS.keys.joinToString(" or "))
            cur = cur.copy(titleModel = model!!)
            ollama.forget()                            // look the entity up again
        }
        body["worktree_retention_days"]?.let { v ->
            val p = v as? JsonPrimitive
            val days = when {
                p == null || p.content == "true" || p.content == "false" -> null
                p.isString -> p.content.trim().takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toIntOrNull()
                else -> p.intOrNull
            }
            if (days == null || days !in 0..RETENTION_MAX_DAYS)
                throw BadRequest("worktree_retention_days: whole days, 0 (keep everything) to $RETENTION_MAX_DAYS")
            cur = cur.copy(retentionDays = days)
        }
        body["antigravity_inclusion"]?.let { v ->
            val o = v as? JsonObject
            val group = (o?.get("group") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val included = (o?.get("included") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (group.isEmpty() || group.length > 80 || included == null)
                throw BadRequest("antigravity_inclusion: {group: a quota group's name, included: true or false}")
            val rest = cur.antigravityExcluded.filter { it != group }
            cur = cur.copy(antigravityExcluded = if (included) rest else (rest + group).takeLast(ANTIGRAVITY_EXCLUDED_MAX))
        }
        save(cur)
        return view()
    }

    /** {ids: [board id]}: the given boards first in this order; boards not on the page (another project's, retired) keep their rank after them. */
    fun order(body: JsonObject): JsonObject {
        val ids = (body["ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (ids.isNullOrEmpty() || ids.any { it.isNullOrEmpty() }) throw BadRequest("ids: list of board ids required")
        val given = ids.filterNotNull()
        val cur = read()
        val order = (given + cur.boardOrder.filter { it !in given }).distinct()
        save(cur.copy(boardOrder = order))
        return buildJsonObject {
            put("ok", true)
            put("board_order", JsonArray(order.map(::JsonPrimitive)))
        }
    }

    /** The Ollama AI Task entity that titles new tasks, or null when Dieter's auto-title does (or no entity exists). */
    fun titleEntity(): String? = if (read().titleModel == "ollama") ollama.get() else null

    fun view(): JsonObject {
        val cur = read()
        val entity = ollama.get()
        return buildJsonObject {
            put("title_model", cur.titleModel)
            put("board_order", JsonArray(cur.boardOrder.map(::JsonPrimitive)))
            put("worktree_retention_days", cur.retentionDays)
            put("antigravity_excluded", JsonArray(cur.antigravityExcluded.map(::JsonPrimitive)))
            put("titles_daemon_id", if (entity != null && cur.titleModel == "ollama") daemonId() else "")
            put("title_models", buildJsonArray {
                add(buildJsonObject { put("id", "openai"); put("name", TITLE_MODELS.getValue("openai")); put("available", true); put("detail", "Dieter's auto-title (GPT Spark)") })
                add(buildJsonObject {
                    put("id", "ollama"); put("name", TITLE_MODELS.getValue("ollama")); put("available", entity != null)
                    put("detail", entity ?: if (ollama.reachable) "No AI Task entity of the Ollama integration found; OpenAI titles instead" else "Works in the Home Assistant add-on only")
                })
            })
        }
    }

    /** Written whole and renamed into place, so a reader never sees half a file. */
    private fun save(v: Values) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(buildJsonObject {
            put("title_model", v.titleModel)
            put("board_order", JsonArray(v.boardOrder.map(::JsonPrimitive)))
            put("worktree_retention_days", v.retentionDays)
            put("antigravity_excluded", JsonArray(v.antigravityExcluded.map(::JsonPrimitive)))
        }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        val TITLE_MODELS = linkedMapOf("openai" to "OpenAI", "ollama" to "Ollama (HA)")
        const val RETENTION_DEFAULT_DAYS = 14
        const val RETENTION_MAX_DAYS = 3650
        const val ANTIGRAVITY_EXCLUDED_MAX = 50

        /** The add-on daemon's id from the store's runtime file, "" when unknown. */
        fun localDaemonId(dieterHome: File): String =
            runCatching {
                ((Json.parseToJsonElement(dieterHome.resolve("runtime/daemon.json").readText()) as JsonObject)["daemonId"] as? JsonPrimitive)?.content
            }.getOrNull().orEmpty()
    }
}
