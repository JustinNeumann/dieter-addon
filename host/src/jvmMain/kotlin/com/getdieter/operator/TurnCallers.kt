package com.getdieter.operator

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The conversation an agent turn's token belongs to, from the daemon (`dieter card caller` run with that token, which
 * the daemon checks against its live turns). The web host's agent relay asks before an agent's hass-cli reaches Home
 * Assistant: only a conversation created with Home Assistant access gets through. A forged token, a finished turn or
 * no token at all answers `home_assistant_access: false` with the daemon's reason.
 */
class TurnCallers(private val caller: (String) -> Command?) {
    constructor(bin: String) : this({ token ->
        Command.run(listOf(bin, "card", "caller"), timeout = 15.seconds, env = mapOf("DIETER_TURN_TOKEN" to token))
    })

    fun resolve(token: String): JsonObject {
        if (!TOKEN.matches(token)) return refused("not a turn token")
        val done = caller(token) ?: return refused("the daemon did not answer")
        if (done.exit != 0) return refused(done.stderr.ifBlank { done.stdout }.trim().take(300).ifEmpty { "exit ${done.exit}" })
        val answer = runCatching { Json.parseToJsonElement(done.stdout) as JsonObject }.getOrNull() ?: return refused("no caller")
        return buildJsonObject {
            put("card_id", (answer["cardId"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            put("home_assistant_access", (answer["homeAssistantAccess"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    private fun refused(reason: String) = buildJsonObject {
        put("home_assistant_access", false)
        put("reason", reason)
    }

    companion object {
        /** dtt1:CARD:TURN:MAC, as the daemon writes them; anything else never reaches the CLI. */
        val TOKEN = Regex("dtt1:[A-Za-z0-9_-]{1,100}:[A-Za-z0-9_-]{1,100}:[A-Za-z0-9_-]{20,100}")
    }
}
