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
        inputs.property("patches", 2) // bump when the filters below change (Gradle cannot see into them)
        filesMatching("**/design/MobileTheme.kt") {
            filter { line ->
                line.replace("fontFamily = FontFamily.Monospace", "fontFamily = platformMonospace()")
                    // the entry point gives the loader the colour Compose draws (no flash on the next load)
                    .replace("private fun materialScheme(", "internal fun materialScheme(")
            }
        }
        // "Dynamic color" follows Home Assistant's theme on the web, not a wallpaper
        filesMatching("**/settings/SettingsScreen.kt") {
            filter { line -> line.replace("\"Use colors from your wallpaper\"", "\"Use Home Assistant's theme colors\"") }
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
            kotlin.srcDir(rootDir.resolve("addon/src/commonMain/kotlin")) // the add-on core's rules (AddonRules)
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
        wasmJsMain.dependencies {
            // Material colour utilities (HCT, tonal-spot schemes) without Compose dependencies: the
            // seeded scheme for "Dynamic color" from Home Assistant's primary colour.
            implementation("com.materialkolor:material-color-utilities:5.0.1")
            // Escape reaches Compose's back handlers on the web (SystemBackHandler's actual).
            implementation("org.jetbrains.compose.ui:ui-backhandler:${providers.gradleProperty("composeWebVersion").getOrElse(libs.versions.compose.multiplatform.get())}")
            // The browser's Back and Forward as navigation events (BrowserInput, popstate); the version Compose pins
            implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
        }
    }
}

wire {
    sourcePath {
        srcDir(dieter.resolve("api/proto"))
        srcDir(rootDir.resolve("proto")) // the browser <-> web host protocol
    }
    kotlin {
        rpcRole = "client"
        rpcCallStyle = "suspending"
        explicitStreamingCalls = true
    }
}

// The offline diagram renderer (apps/apple/mac/MarkdownPreview: vega-lite, vega, mermaid), built
// the way :ui builds it for Android and iOS, from a copy so the submodule's tree stays clean. It
// lands next to the UI's resources as files/markdown-preview, where DiagramAssets reads it.
val previewSource = layout.projectDirectory.dir("../dieter/apps/apple/mac/MarkdownPreview")
val previewWork = layout.buildDirectory.dir("markdown-preview")
val previewResources = layout.buildDirectory.dir("generated/markdownResources")
val prepareMarkdownPreview =
    tasks.register<Sync>("prepareMarkdownPreview") {
        from(previewSource) {
            include("src/**", "build.mjs", "package.json", "package-lock.json")
            exclude("**/node_modules/**")
        }
        into(previewWork)
        preserve { include("node_modules/**") }
    }
val buildMarkdownPreview =
    tasks.register<Exec>("buildMarkdownPreview") {
        dependsOn(prepareMarkdownPreview)
        inputs.dir(previewSource.dir("src"))
        inputs.files(
            previewSource.file("build.mjs"),
            previewSource.file("package.json"),
            previewSource.file("package-lock.json"),
        )
        outputs.dir(previewResources)
        workingDir(previewWork)
        commandLine(
            "sh",
            "-c",
            "npm ci --ignore-scripts --no-audit --no-fund && node build.mjs --output \"\$1\"",
            "markdown-preview",
            previewResources.get().dir("files/markdown-preview").asFile.absolutePath,
        )
    }
val webResources = layout.buildDirectory.dir("generated/webComposeResources")
val mergeComposeResources =
    tasks.register<Sync>("mergeComposeResources") {
        dependsOn(buildMarkdownPreview)
        from(layout.projectDirectory.dir("../dieter/apps/kotlin/ui/src/commonMain/composeResources"))
        from(previewResources)
        into(webResources)
    }

compose.resources {
    packageOfResClass = "com.getdieter.mobile.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = mergeComposeResources.map { webResources.get() },
    )
}

// Binaryen's step copies the JS import object (the @JsFun bodies) next to the optimized wasm but only tracks the
// wasm: a change to JS alone would otherwise ship the old import object.
tasks.matching { it.name == "compileProductionExecutableKotlinWasmJsOptimize" }.configureEach {
    inputs.files(layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/kotlin").map { it.asFileTree.matching { include("*.mjs") } })
        .withPropertyName("importObject")
}
