package com.getdieter.operator

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * One line in the add-on log per page start, which the page reports once its first frame is drawn: how long the
 * start took on that device and which of the page's files came over the network. It shows whether a device
 * downloads the page again on each open (the Companion app's WebView keeps only a small HTTP cache).
 */
object PageLoad {
    fun log(report: JsonObject): JsonElement {
        Log.line(line(report))
        return buildJsonObject { put("ok", true) }
    }

    /** {ms, client: user agent, where: nabu|local|other, downlink: Mbit/s, files: [{name, transfer, size}]} */
    fun line(report: JsonObject): String {
        fun JsonObject.long(key: String) = ((this[key] as? JsonPrimitive)?.longOrNull ?: 0L).coerceIn(0L, 1L shl 40)
        val ms = report.long("ms").coerceAtMost(600_000)
        val files = (report["files"] as? JsonArray).orEmpty().take(60).mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .map { File(name(it["name"]), it.long("transfer"), it.long("size")) }
        val parts = mutableListOf("page: started in %.1f s".format(Locale.ROOT, ms / 1000.0), client((report["client"] as? JsonPrimitive)?.content.orEmpty()))
        when ((report["where"] as? JsonPrimitive)?.content) {
            "nabu" -> parts += "Nabu Casa"
            "local" -> parts += "local network"
        }
        (report["downlink"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { parts += "%.1f Mbit/s".format(Locale.ROOT, it) }
        val downloaded = files.filter { it.transfer >= it.size && it.transfer > 0 }
        val revalidated = files.filter { it.transfer in 1 until it.size }
        val cached = files.count { it.transfer == 0L }
        parts += if (downloaded.isEmpty()) "nothing downloaded" else
            "downloaded %s in %d files (%s)".format(Locale.ROOT, mb(downloaded.sumOf { it.transfer }), downloaded.size,
                downloaded.sortedByDescending { it.transfer }.take(4).joinToString(", ") { "${it.name} ${mb(it.transfer)}" })
        if (revalidated.isNotEmpty()) parts += "${revalidated.size} checked for changes"
        parts += "$cached from the cache"
        return parts.joinToString(" · ")
    }

    private class File(val name: String, val transfer: Long, val size: Long)

    /** The file's own name, its content hash shortened. */
    private fun name(value: JsonElement?): String =
        ((value as? JsonPrimitive)?.content.orEmpty()).substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }
            .replace(Regex("[0-9a-f]{12,}")) { it.value.take(6) + "…" }.take(48).ifEmpty { "?" }

    private fun mb(bytes: Long) = if (bytes >= 1_000_000) "%.1f MB".format(Locale.ROOT, bytes / 1e6) else "${(bytes + 999) / 1000} kB"

    /** "Home Assistant app on Android", "Chrome on Windows", … from the user agent. */
    fun client(agent: String): String {
        val app = when {
            "Home Assistant/" in agent -> "Home Assistant app"
            "Edg/" in agent -> "Edge"
            "Firefox/" in agent -> "Firefox"
            "Chrome/" in agent -> "Chrome"
            "Safari/" in agent -> "Safari"
            else -> "browser"
        }
        val system = listOf("Android", "iPhone", "iPad", "Windows", "Mac OS", "Linux").firstOrNull { it in agent }?.replace("Mac OS", "macOS")
        return if (system == null) app else "$app on $system"
    }
}
