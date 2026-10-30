package com.getdieter.webhost

import com.getdieter.core.platform.DeviceSettings
import com.getdieter.core.platform.SecureStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A string map in one JSON file, written atomically with owner-only permissions. The web host's
 * state directory belongs to its own user (not the agents'), so this is what Android's keystore-sealed
 * preferences are for the app.
 */
internal class JsonFileMap(private val file: File) {
    private val values: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> =
        runCatching {
                if (!file.exists()) mutableMapOf()
                else
                    Json.parseToJsonElement(file.readText()).jsonObject.mapValuesTo(mutableMapOf()) {
                        it.value.jsonPrimitive.contentOrNull.orEmpty()
                    }
            }
            .getOrElse {
                System.err.println("web-host: ${file.name} unreadable, starting empty: $it")
                mutableMapOf()
            }

    @Synchronized fun get(key: String): String? = values[key]

    @Synchronized
    fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(values.mapValues { JsonPrimitive(it.value) })))
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

internal class FileSecureStore(file: File) : SecureStore {
    private val map = JsonFileMap(file)

    override fun read(key: String) = map.get(key)

    override fun write(key: String, value: String) = map.set(key, value)

    override fun delete(key: String) = map.set(key, null)
}

internal class FileDeviceSettings(file: File) : DeviceSettings {
    private val map = JsonFileMap(file)

    override fun string(key: String) = map.get(key)

    override fun putString(key: String, value: String?) = map.set(key, value)
}
