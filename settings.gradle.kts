// Dieter's Compose UI for the Home Assistant add-on: the browser build of the real apps/kotlin UI
// (:web) and, later, the JVM web host that runs the Kotlin core next to the add-on's daemon.
// The Dieter sources come from the `dieter` submodule (JustinNeumann/dieter, pinned commit); all
// toolchain and library versions come from its version catalog, so they move together with it.
pluginManagement {
    val toml = settingsDir.resolve("dieter/apps/kotlin/gradle/libs.versions.toml").readText()
    fun v(key: String) =
        Regex("""(?m)^$key\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
            ?: error("$key missing from libs.versions.toml (is the dieter submodule checked out?)")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version v("kotlin")
        id("org.jetbrains.kotlin.jvm") version v("kotlin")
        id("org.jetbrains.kotlin.plugin.compose") version v("kotlin")
        id("org.jetbrains.kotlin.plugin.serialization") version v("kotlin")
        // gradle.properties' composeWebVersion (the page's own Compose release), else the fork's
        val composeWeb =
            settingsDir.resolve("gradle.properties").readLines()
                .firstNotNullOfOrNull { Regex("""^composeWebVersion=(\S+)""").find(it)?.groupValues?.get(1) }
        id("org.jetbrains.compose") version (composeWeb ?: v("compose-multiplatform"))
        id("com.squareup.wire") version v("wire")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("dieter/apps/kotlin/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "dieter-addon-kotlin"

// The Compose UI compiled for the browser (Kotlin/Wasm).
include(":web")

// The web host (JVM): runs the Kotlin core next to the add-on's daemon, one WebSocket to the browser.
include(":host")
