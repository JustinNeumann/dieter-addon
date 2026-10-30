import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.squareup.wire")
}

// The web host: runs Dieter's Kotlin core (ClientApi over CoreRuntime, JVM platform: OkHttp,
// software device key, file-backed secure store) next to the add-on's daemon and serves it to the
// browser over one WebSocket (proto/dieter/web/v1/host.proto). The core sources come from the
// fork submodule as they are, like the web module's.
val dieter = rootDir.resolve("fork")
val apps = dieter.resolve("apps/kotlin")

// The Dieter release the submodule is at (gradle.properties dieterRelease): the client version the
// core reports to the gateway; and this repository's version.
val buildInfo =
    tasks.register("generateBuildInfo") {
        val release = providers.gradleProperty("dieterRelease")
        val version = rootProject.version.toString()
        val out = layout.buildDirectory.dir("generated/buildinfo")
        inputs.property("release", release)
        inputs.property("version", version)
        outputs.dir(out)
        doLast {
            val file = out.get().file("com/getdieter/webhost/BuildInfo.kt").asFile
            file.parentFile.mkdirs()
            file.writeText(
                "package com.getdieter.webhost\n\ninternal object BuildInfo {\n" +
                    "    const val DIETER_RELEASE = \"${release.get()}\"\n    const val VERSION = \"$version\"\n}\n"
            )
        }
    }

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        commonMain {
            kotlin.srcDir(apps.resolve("core/runtime/src/commonMain/kotlin"))
            kotlin.srcDir(rootDir.resolve("addon/src/commonMain/kotlin"))
            dependencies {
                implementation(libs.wire.runtime)
                implementation(libs.wire.grpc.client)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        jvmMain {
            kotlin.srcDir(apps.resolve("core/runtime/src/jvmSharedMain/kotlin"))
            kotlin.srcDir(buildInfo)
            dependencies { implementation(libs.okhttp) }
        }
        jvmTest {
            // The core's own tests of what the web host relies on (sign-in, opening conversations): the
            // submodule's Gradle build needs the Android SDK, so they run here.
            kotlin.srcDir(apps.resolve("core/testing/src/commonMain/kotlin"))
            kotlin.srcDir(rootDir.resolve("addon/src/commonTest/kotlin"))
            for (area in listOf("identity", "navigation", "notifications"))
                kotlin.srcDir(apps.resolve("core/runtime/src/commonTest/kotlin/com/getdieter/core/$area"))
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.okhttp)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okio.fakefilesystem)
            }
        }
    }
}

wire {
    sourcePath {
        srcDir(dieter.resolve("api/proto"))
        srcDir(rootDir.resolve("proto"))
    }
    kotlin {
        rpcRole = "client"
        rpcCallStyle = "suspending"
        explicitStreamingCalls = true
    }
}

// One runnable jar: build/libs/dieter-web-host-<version>.jar  (java -jar …)
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "build"
        description = "The web host with all its dependencies in one jar."
        archiveBaseName.set("dieter-web-host")
        archiveVersion.set(rootProject.version.toString())
        manifest {
            attributes(
                "Main-Class" to "com.getdieter.webhost.MainKt",
                "Implementation-Version" to rootProject.version.toString(),
            )
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        val jvmJar = tasks.named<Jar>("jvmJar")
        dependsOn(jvmJar)
        from(jvmJar.map { zipTree(it.archiveFile) })
        from(configurations.named("jvmRuntimeClasspath").map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }

tasks.register("assembleHost") { dependsOn(fatJar) }

tasks.withType<Test>().configureEach { testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
