package com.getdieter.operator

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Titles by Home Assistant's Ollama (Settings → Title model → Ollama (HA)): a card or chat is created under a
 * provisional title, its task's first line, without Dieter's auto-title, and renamed in the background once the
 * model answers, unless someone renamed it meanwhile (bridge/dieter_bridge.py until 0.6.431.20). [entity] is the
 * Ollama AI Task entity while Settings name Ollama, else null; [ha] asks it.
 */
class Titles(private val dieter: Dieter, private val ha: HomeAssistant?, private val entity: () -> String?) {
    /** card/chat id -> provisional title, until renamed */
    private val pending = ConcurrentHashMap<String, String>()
    /** a burst of new cards must not queue up the model server */
    private val slots = Semaphore(2)

    /** {kind: card|chat, id, prompt, provisional}: a conversation created under its provisional title, to be renamed. */
    fun nameLater(body: JsonObject): JsonObject {
        val kind = body.field("kind")
        val id = body.field("id")
        val prompt = body.field("prompt")
        val provisional = body.field("provisional")
        if (kind !in setOf("card", "chat") || !ID.matches(id) || prompt.isBlank() || provisional.isBlank())
            throw BadRequest("titles: {kind: card|chat, id, prompt, provisional}")
        val entity = entity() ?: throw BadRequest("Ollama no longer names new tasks; this one keeps its first line as title")
        pending[id] = provisional
        Thread.ofVirtual().name("title-$id").start { retitle(kind, id, prompt, entity) }
        return buildJsonObject { put("ok", true) }
    }

    /** Background: rename a card/chat created under its provisional title, unless someone renamed it meanwhile. */
    internal fun retitle(kind: String, id: String, prompt: String, entity: String) {
        try {
            slots.acquire()
            val title = try { aiTitle(entity, prompt) } finally { slots.release() }
            val provisional = pending[id]
            if (title == null || provisional == null || title == provisional) return
            val cur = (dieter.run(kind, "show", id, timeout = 15.seconds) as? JsonObject)?.obj(kind)
            if (cur?.field("title") != provisional) return
            dieter.run(kind, "rename", "--title", title, id, timeout = 30.seconds)
            Log.line("title: $id '$title'")
        } catch (e: Exception) {
            Log.line("title: $id $entity ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    private fun aiTitle(entity: String, prompt: String): String? {
        val ha = ha ?: return null
        val instructions =
            "Write a short title for the task below, as a task list would show it: at most 8 words, no quotes, " +
                "no trailing period. Write the title in the same language as the task: a German task gets a German " +
                "title, an English task an English one. Reply with the title only.\n\nTask:\n" + prompt.trim().take(4000)
        val answer = ha.post(
            "services/ai_task/generate_data?return_response",
            buildJsonObject {
                put("entity_id", entity)
                put("task_name", TASK_NAME)
                put("instructions", instructions)
            },
            Duration.ofSeconds(TIMEOUT_S),
        )
        val data = (Json.parseToJsonElement(answer) as? JsonObject)?.obj("service_response")?.get("data")
        return clean((data as? JsonPrimitive)?.content ?: data?.toString())
    }

    companion object {
        const val MAX = 80
        const val TASK_NAME = "Dieter task title"
        /** a cold Ollama loads the model first */
        const val TIMEOUT_S = 120L
        private val ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val SPACE = Regex("\\s+")
        private val LABEL = Regex("^\\s*(title|titel)\\s*:\\s*", RegexOption.IGNORE_CASE)
        private const val QUOTES = " \"'`*#„“”‚‘’«»"

        /** Provisional title: the task's first non-empty line, cut at a word boundary (a long word: at [MAX]). */
        fun firstLine(prompt: String): String {
            val first = prompt.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            val line = first.trim().trimStart('#', '>', '*', '-', ' ').trim().replace(SPACE, " ")
            if (line.length <= MAX) return line.ifEmpty { "New task" }
            val cut = line.take(MAX + 1).substringBeforeLast(' ', "")
            return (if (cut.length >= MAX / 2) cut else line.take(MAX)).trimEnd(' ', ',', ';', ':', '-')
        }

        /** The model's answer as a title: its first line without a "Title:" label, quotes or a closing period. */
        fun clean(text: String?): String? {
            val first = text.orEmpty().lines().firstOrNull { it.isNotBlank() }.orEmpty()
            val line = first.trim().replace(LABEL, "").trimEnd(' ', '.').trim(*QUOTES.toCharArray()).replace(SPACE, " ").trimEnd(' ', '.')
            return line.take(MAX).trim().ifEmpty { null }
        }
    }
}
