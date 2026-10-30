package com.getdieter.addon.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The add-on's JSON API under `api/`, answered by its operator (host/…/operator; the Python bridge until
 * 0.6.431.21, hence the name). The web host reaches it on loopback, which it trusts as it trusts Home Assistant's Ingress.
 */
interface Bridge {
    /**
     * One request; [body] null is a GET. [user] is the Home Assistant user behind the page, for what
     * the bridge records by name. Throws [BridgeUnreachable] when no answer came.
     */
    suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        user: String = "",
        timeout: Duration = 2.minutes,
    ): BridgeResponse

    /** The bridge's live state reduced to [fields], a new value per change; reconnects by itself. */
    fun events(fields: List<String>): Flow<JsonObject>
}

class BridgeResponse(val status: Int, val json: JsonObject)

class BridgeUnreachable(message: String) : Exception(message)

/** An answer of the bridge that is not what was asked for: its `error`, or the status. */
class BridgeRefused(message: String) : Exception(message)

/** GET [path]; a non-2xx status or an `error` field throws [BridgeRefused]. */
suspend fun Bridge.read(path: String): JsonObject = request("GET", path).checked()

/** POST [path]; a non-2xx status or an `error` field throws [BridgeRefused]. */
suspend fun Bridge.write(path: String, body: JsonObject = JsonObject(emptyMap()), user: String = ""): JsonObject =
    request("POST", path, body, user).checked()

private fun BridgeResponse.checked(): JsonObject {
    val error = json.str("error")
    if (status !in 200..299 || error.isNotEmpty())
        throw BridgeRefused(error.ifEmpty { "The add-on answered $status." })
    return json
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.str(key: String): String =
    ((this?.get(key)) as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject?.bool(key: String): Boolean = ((this?.get(key)) as? JsonPrimitive)?.content == "true"

internal fun JsonObject?.int(key: String): Int? = ((this?.get(key)) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

internal fun JsonObject?.objects(key: String): List<JsonObject> =
    ((this?.get(key)) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
