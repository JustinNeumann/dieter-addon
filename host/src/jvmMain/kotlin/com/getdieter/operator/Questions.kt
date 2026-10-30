package com.getdieter.operator

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Questions agents wait on. The harness worker registers one (DIETER_QUESTIONS_URL) and polls for the answer, which
 * the person gives on the page (the add-on core) or declines. Kept in memory, like the bridge did: an agent turn does
 * not outlive the add-on, and a question nobody answered is forgotten after a day.
 */
class Questions(private val now: () -> Long = System::currentTimeMillis) {
    private class Question(val id: String, val session: String, val input: JsonObject, val createdAt: String, val created: Long) {
        var output: JsonObject? = null
    }

    private val questions = linkedMapOf<String, Question>()

    /** {id, session, input}: the worker's question, its tool call id as id and the card as session. */
    @Synchronized
    fun register(body: JsonObject): JsonObject {
        val id = body.text("id")
        val session = body.text("session")
        val input = body["input"] as? JsonObject
        if (!ID.matches(id)) throw BadRequest("invalid question id")
        if (!SESSION.matches(session)) throw BadRequest("invalid session")
        if ((input?.get("questions") as? JsonArray).isNullOrEmpty()) throw BadRequest("input.questions required")
        if (input.toString().length > 64_000) throw BadRequest("question too large")
        questions[id] = Question(id, session, input, ZonedDateTime.now().format(STAMP), now())
        val first = ((input["questions"] as JsonArray).firstOrNull() as? JsonObject)?.text("question").orEmpty()
        Log.line("question $id: $session asks: ${first.take(80)}")
        return buildJsonObject { put("ok", true) }
    }

    /** {action, answers?}: the person's answer; the worker's next poll takes it. */
    @Synchronized
    fun answer(id: String, body: JsonObject): JsonObject {
        val action = body.text("action")
        if (action !in ACTIONS) throw BadRequest("action must be one of " + ACTIONS.joinToString(", "))
        val output = buildJsonObject {
            put("action", action)
            if (action == "answered" || action == "partially-answered") {
                val answers = body["answers"] as? JsonObject ?: throw BadRequest("answers required")
                put("answers", JsonObject(answers.mapNotNull { (key, a) -> (a as? JsonObject)?.let(::clean)?.let { key to it } }.toMap()))
            }
        }
        val q = questions[id] ?: throw BadRequest("no such question (the agent may have moved on)")
        if (q.output != null) throw BadRequest("already answered")
        q.output = output
        Log.line("question $id: $action")
        return buildJsonObject { put("ok", true) }
    }

    /** The worker's poll: {done: false} until answered, then the answer once (the question is forgotten); null if unknown. */
    @Synchronized
    fun poll(id: String): JsonObject? {
        val q = questions[id] ?: return null
        val output = q.output ?: return buildJsonObject { put("done", false) }
        questions.remove(id)
        return buildJsonObject {
            put("done", true)
            put("output", output)
        }
    }

    /** {questions: [{id, session, input, created_at}]}: the ones still waiting for the person. */
    @Synchronized
    fun pending(): JsonObject {
        val now = now()
        questions.values.removeIf { now - it.created > TTL_MS }
        return buildJsonObject {
            putJsonArray("questions") {
                questions.values.filter { it.output == null }.forEach { q ->
                    add(buildJsonObject {
                        put("id", q.id)
                        put("session", q.session)
                        put("input", q.input)
                        put("created_at", q.createdAt)
                    })
                }
            }
        }
    }

    /** One answer: the chosen option ids (strings or whole numbers) and a free-form text (≤ 4000 characters); null when it holds neither. */
    private fun clean(a: JsonObject): JsonObject? {
        val ids = (a["optionIds"] as? JsonArray).orEmpty().mapNotNull { id ->
            (id as? JsonPrimitive)?.takeUnless { it is JsonNull }?.takeIf { it.isString || it.content.toLongOrNull() != null }?.content
        }

        val freeform = (a["freeform"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(4000)
        if (ids.isEmpty() && freeform == null) return null
        return buildJsonObject {
            putJsonArray("optionIds") { ids.forEach { add(JsonPrimitive(it)) } }
            if (freeform != null) put("freeform", freeform)
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()

    companion object {
        val ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        val SESSION = Regex("^[A-Za-z0-9._-]{1,80}$")
        val ACTIONS = listOf("answered", "partially-answered", "declined", "cancelled")
        const val TTL_MS = 24 * 3600 * 1000L
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
