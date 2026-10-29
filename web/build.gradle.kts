import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.squareup.wire")
}

// Wire models + :core:runtime + :ui common sources from the dieter submodule, compiled for the
// browser, plus the browser actuals and the entry point in src/wasmJsMain. Nothing in the
// submodule is built with its own Gradle build (that one needs the Android SDK).
val dieter = rootDir.resolve("dieter")
val apps = dieter.resolve("apps/kotlin")

// :ui's sources as they are, except that the Material type scale takes its monospace family from
// platformMonospace() (the browser has no system monospace font; the web actual bundles one).
// Upstream this is a two-line change in MobileTheme.kt.
val uiSources =
    tasks.register<Sync>("prepareUiSources") {
        from(apps.resolve("ui/src/commonMain/kotlin"))
        into(layout.buildDirectory.dir("ui-src"))
        filesMatching("**/design/MobileTheme.kt") {
            filter { line -> line.replace("fontFamily = FontFamily.Monospace", "fontFamily = platformMonospace()") }
        }
    }

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("dieter")
        browser {
            commonWebpackConfig { outputFileName = "dieter.js" }
        }
        binaries.executable()
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        commonMain {
            kotlin.srcDir(apps.resolve("core/runtime/src/commonMain/kotlin"))
            kotlin.srcDir(uiSources)
            dependencies {
                implementation(libs.wire.runtime)
                implementation(libs.wire.grpc.client)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
                implementation(libs.kotlinx.serialization.json)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.compose.material.icons.core)
            }
        }
    }
}

wire {
    sourcePath { srcDir(dieter.resolve("api/proto")) }
    kotlin {
        rpcRole = "client"
        rpcCallStyle = "suspending"
        explicitStreamingCalls = true
    }
}

compose.resources {
    packageOfResClass = "com.getdieter.mobile.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = provider { layout.projectDirectory.dir("../dieter/apps/kotlin/ui/src/commonMain/composeResources") },
    )
}
