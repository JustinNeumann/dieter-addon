package com.getdieter.mobile.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The theme of the page that hosts Dieter: Home Assistant's when the page runs behind Ingress (the
 * loader reads the parent frame, see index.html `window.dieterHost`), else `?theme=` or the device.
 * Colours are ARGB.
 */
internal data class HostTheme(
    val dark: Boolean,
    /** "home-assistant", "query" or "device" */
    val source: String = "device",
    /** Home Assistant's theme name ("default" for its built-in theme) */
    val name: String? = null,
    val primary: Int? = null,
    val accent: Int? = null,
    val background: Int? = null,
    val surface: Int? = null,
)

internal object WebHost {
    private val state = MutableStateFlow(read())
    val theme: StateFlow<HostTheme> = state

    /** Follows the host's theme from now on (the loader debounces and reports only real changes). */
    fun start() = hostOnThemeChange { state.value = read() }

    /** Remembers the colour Compose draws behind everything, for the loader and the browser chrome. */
    fun setChrome(color: Color, dark: Boolean) = hostSetChrome(color.toHex(), dark)

    private fun read(): HostTheme =
        hostThemeJson()?.let { runCatching { parse(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }
            ?: HostTheme(dark = !hostPrefersLight())

    private fun parse(o: JsonObject): HostTheme {
        fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        fun color(key: String) = text(key)?.let(::parseHexColor)
        return HostTheme(
            dark = o["dark"]?.jsonPrimitive?.booleanOrNull ?: !hostPrefersLight(),
            source = text("source") ?: "device",
            name = text("name"),
            primary = color("primary"),
            accent = color("accent"),
            background = color("background"),
            surface = color("surface"),
        )
    }
}

/** `#rrggbb` -> opaque ARGB. */
internal fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}

private fun Color.toHex(): String {
    fun channel(v: Float) = (v * 255f + .5f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + channel(red) + channel(green) + channel(blue)
}

/**
 * A Material 3 colour scheme seeded from one colour (Home Assistant's primary colour), the way Android
 * derives Material You from the wallpaper: tonal-spot variant of the Material colour utilities.
 */
internal fun seededColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val s: DynamicScheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    fun c(argb: Int) = Color(argb)
    return if (dark)
        darkColorScheme(
            primary = c(s.primary), onPrimary = c(s.onPrimary), primaryContainer = c(s.primaryContainer),
            onPrimaryContainer = c(s.onPrimaryContainer), inversePrimary = c(s.inversePrimary),
            secondary = c(s.secondary), onSecondary = c(s.onSecondary), secondaryContainer = c(s.secondaryContainer),
            onSecondaryContainer = c(s.onSecondaryContainer), tertiary = c(s.tertiary), onTertiary = c(s.onTertiary),
            tertiaryContainer = c(s.tertiaryContainer), onTertiaryContainer = c(s.onTertiaryContainer),
            background = c(s.background), onBackground = c(s.onBackground), surface = c(s.surface),
            onSurface = c(s.onSurface), surfaceVariant = c(s.surfaceVariant), onSurfaceVariant = c(s.onSurfaceVariant),
            surfaceTint = c(s.surfaceTint), inverseSurface = c(s.inverseSurface), inverseOnSurface = c(s.inverseOnSurface),
            error = c(s.error), onError = c(s.onError), errorContainer = c(s.errorContainer),
            onErrorContainer = c(s.onErrorContainer), outline = c(s.outline), outlineVariant = c(s.outlineVariant),
            scrim = c(s.scrim), surfaceBright = c(s.surfaceBright), surfaceContainer = c(s.surfaceContainer),
            surfaceContainerHigh = c(s.surfaceContainerHigh), surfaceContainerHighest = c(s.surfaceContainerHighest),
            surfaceContainerLow = c(s.surfaceContainerLow), surfaceContainerLowest = c(s.surfaceContainerLowest),
            surfaceDim = c(s.surfaceDim),
        )
    else
        lightColorScheme(
            primary = c(s.primary), onPrimary = c(s.onPrimary), primaryContainer = c(s.primaryContainer),
            onPrimaryContainer = c(s.onPrimaryContainer), inversePrimary = c(s.inversePrimary),
            secondary = c(s.secondary), onSecondary = c(s.onSecondary), secondaryContainer = c(s.secondaryContainer),
            onSecondaryContainer = c(s.onSecondaryContainer), tertiary = c(s.tertiary), onTertiary = c(s.onTertiary),
            tertiaryContainer = c(s.tertiaryContainer), onTertiaryContainer = c(s.onTertiaryContainer),
            background = c(s.background), onBackground = c(s.onBackground), surface = c(s.surface),
            onSurface = c(s.onSurface), surfaceVariant = c(s.surfaceVariant), onSurfaceVariant = c(s.onSurfaceVariant),
            surfaceTint = c(s.surfaceTint), inverseSurface = c(s.inverseSurface), inverseOnSurface = c(s.inverseOnSurface),
            error = c(s.error), onError = c(s.onError), errorContainer = c(s.errorContainer),
            onErrorContainer = c(s.onErrorContainer), outline = c(s.outline), outlineVariant = c(s.outlineVariant),
            scrim = c(s.scrim), surfaceBright = c(s.surfaceBright), surfaceContainer = c(s.surfaceContainer),
            surfaceContainerHigh = c(s.surfaceContainerHigh), surfaceContainerHighest = c(s.surfaceContainerHighest),
            surfaceContainerLow = c(s.surfaceContainerLow), surfaceContainerLowest = c(s.surfaceContainerLowest),
            surfaceDim = c(s.surfaceDim),
        )
}

@JsFun("() => window.dieterHost ? window.dieterHost.theme() : null")
private external fun hostThemeJson(): String?

@JsFun("(callback) => { if (window.dieterHost) window.dieterHost.onThemeChange(callback) }")
private external fun hostOnThemeChange(callback: () -> Unit)

@JsFun("(color, dark) => { if (window.dieterHost) window.dieterHost.setChrome(color, dark) }")
private external fun hostSetChrome(color: String, dark: Boolean)

@JsFun("() => !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches)")
private external fun hostPrefersLight(): Boolean
