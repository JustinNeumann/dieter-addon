package com.getdieter.operator

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Files attached to a message before it is sent: each in its own folder under [dir] (DIETER_HOME/uploads), named by a
 * random id that the send or create request names (the bridge hands the file to the CLI with --attach and removes
 * it). Uploads nobody sent are removed after a day.
 */
class Uploads(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    /** Stores [data] as [name]: {id, name, size}. */
    fun store(name: String, data: ByteArray): JsonObject {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('.', '-').ifEmpty { "file" }.take(120)
        val id = ByteArray(18).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val folder = File(dir, id)
        folder.mkdirs()
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwx------"))
        val file = File(folder, safe)
        Files.newOutputStream(file.toPath()).use { it.write(data) }
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        dir.listFiles().orEmpty()
            .filter { it.name != id && now() - it.lastModified() > DAY_MS }
            .forEach { it.deleteRecursively() }
        return buildJsonObject {
            put("id", id)
            put("name", file.name)
            put("size", data.size)
        }
    }

    companion object {
        const val MAX = 20 * 1024 * 1024
        const val DAY_MS = 86_400_000L
    }
}
