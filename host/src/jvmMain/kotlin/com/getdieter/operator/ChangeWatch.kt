package com.getdieter.operator

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `dieter watch changes`: the daemon's change frames (the stream the native apps hold), bridge/dieter_bridge.py's
 * Watch until 0.6.431.21. What a frame changed on the board decides the poll ([classify]): the cards and chats it names
 * are re-read one by one, a change of projects, boards or checkouts lists everything. Not counted: activity frames that
 * only stream a running turn's text, a card summary whose only news is its lastActivityAt (a running agent bumps it
 * every ~2 s), an owned card whose only news is its updatedAt and token usage (every 5-10 s per running agent: each
 * started a full poll and kept a Pi's daemon at 2-4 cores, 0.6.429.41-.45), the Dreamfall records (kv, [DreamfallView]
 * watches them itself) and schedules. Activity and token counts come with the timed full poll.
 */
class ChangeWatch(private val board: Board) {
    /** caught up and running: the timed poll is only a safety net */
    @Volatile var live = false
        private set

    /** The frames' state of one stream. */
    class Seen {
        private val summaries = HashMap<String, String?>()
        private val owned = HashMap<String, JsonElement>()
        private val checkouts = HashMap<String, JsonElement>()
        private val status = HashMap<String, JsonElement?>()

        /** (full, ids): whether the frame changed projects, boards or checkouts (list everything), and which cards and chats it changed. */
        fun classify(frame: JsonObject): Pair<Boolean, Set<String>> {
            var full = listOf("resetRecords", "resetLocal").any { frame[it].truthy() }
            val ids = linkedSetOf<String>()
            for (r in frame["records"].list()) {
                if (r !is JsonObject) {
                    full = true
                    continue
                }
                val (kind, rid) = r.field("kind") to r.field("id")
                if (kind.startsWith("kv.") || kind == "schedule") continue
                val cid = rid.substringBefore(".")
                val field = rid.substringAfter(".", "")
                if (kind !in setOf("item", "") || !cid.startsWith("c_")) {
                    full = true
                    continue
                }
                if (field == "summary") {
                    val key = summaryKey(r)
                    if (key != null && rid in summaries && summaries[rid] == key) continue
                    summaries[rid] = key
                }
                ids += cid
            }
            for (c in frame["ownedCards"].list()) {
                val id = (c as? JsonObject)?.field("id")?.ifEmpty { null } ?: continue
                val key = JsonObject(c - QUIET_OWNED)
                if (owned[id] != key) {
                    owned[id] = key
                    ids += id
                }
            }
            for (c in frame["ownedCheckouts"].list()) {
                val id = (c as? JsonObject)?.field("id") ?: c.toString()
                if (checkouts[id] != c) {
                    checkouts[id] = c
                    full = true
                }
            }
            for (a in frame["activities"].list()) {
                val card = (a as? JsonObject)?.field("cardId")?.ifEmpty { null } ?: continue
                val now = a["status"]?.takeUnless { it is JsonNull }
                if (status[card] != now) {
                    status[card] = now
                    ids += card
                }
            }
            return full to ids
        }

        private fun JsonElement?.list() = (this as? kotlinx.serialization.json.JsonArray).orEmpty()

        /** A summary record's versions without their lastActivityAt; null when unreadable. */
        private fun summaryKey(record: JsonObject): String? = runCatching {
            record.objs("versions").map { v ->
                val text = Base64.getDecoder().decode(v.field("valueJson")).decodeToString()
                val value = if (text.isEmpty()) JsonNull else Json.parseToJsonElement(text)
                if (value is JsonObject) JsonObject(value - "lastActivityAt") else value
            }.toString()
        }.getOrNull()
    }

    /** The stream for good, again after it ends (backing off up to a minute while it keeps ending at once). */
    fun loop(bin: String) {
        var backoff = 2L
        while (true) {
            val started = System.currentTimeMillis()
            val rc = try {
                val process = ProcessBuilder(bin, "watch", "changes")
                    .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val seen = Seen()
                process.inputStream.bufferedReader().forEachLine { line ->
                    val frame = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                    var (full, ids) = seen.classify(frame)
                    if (frame["caughtUp"].truthy()) {   // before that, the replay of what the daemon holds
                        if (!live) {
                            live = true
                            Log.line("watch: following the daemon's changes")
                            full = true
                        }
                        if (full) board.refresh() else if (ids.isNotEmpty()) board.refresh(ids = ids)
                    }
                }
                process.waitFor().toString()
            } catch (e: Exception) {
                e.toString()
            }
            if (live) Log.line("watch: ended ($rc), polling every ${Board.POLL_MS / 1000}s until it is back")
            live = false
            backoff = if (System.currentTimeMillis() - started > 60_000) 2 else minOf(backoff * 2, 60)
            Thread.sleep(backoff * 1000)
        }
    }

    companion object {
        private val QUIET_OWNED = setOf("updatedAt", "tokenUsage")
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
