plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.compose") apply false
    id("com.squareup.wire") apply false
}

// Version of the published bundle (CI sets it from the tag, vX.Y.Z -> X.Y.Z).
version = providers.environmentVariable("DIETER_WEB_VERSION").orElse("0.0.0-dev").get()
