package com.getdieter.mobile.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.getdieter.mobile.design.*

/**
 * "Dynamic color" on the web follows Home Assistant's theme: a scheme seeded from its primary colour
 * (custom themes and the profile's primary colour included), the way Android follows the wallpaper.
 */
@Composable
internal actual fun platformDynamicScheme(dark: Boolean): ColorScheme? {
    val host by WebHost.theme.collectAsState()
    val seed = host.primary ?: return null
    return remember(seed, dark) { seededColorScheme(seed, dark) }
}

/** Offered only where there is a host colour to follow (behind Home Assistant's Ingress). */
internal actual val platformSupportsDynamicColor: Boolean
    get() = WebHost.theme.value.primary != null

@Composable
internal actual fun platformSymbolPainter(symbol: String, size: Dp, weight: GlyphWeight): Painter? =
    null

@Composable internal actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/** Set by the web entry point once the bundled JetBrains Mono has loaded. */
internal var browserMonospace: androidx.compose.ui.text.font.FontFamily? = null

internal actual fun platformMonospace(): androidx.compose.ui.text.font.FontFamily =
    browserMonospace ?: androidx.compose.ui.text.font.FontFamily.Monospace
