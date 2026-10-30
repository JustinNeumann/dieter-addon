package com.getdieter.mobile.platform

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.getdieter.mobile.design.FloatingLayers
import androidx.compose.ui.viewinterop.HtmlElementView
import com.getdieter.core.presentation.Markdown
import com.getdieter.mobile.design.colors
import com.getdieter.mobile.design.palette
import com.getdieter.mobile.design.type
import org.w3c.dom.HTMLElement

internal actual val nativeMarkdownViews: Boolean
    get() = islandsEnabled

/** An HTML island: the document as real DOM, styled from the current palette. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun NativeMarkdownView(
    markdown: String,
    modifier: Modifier,
    linkHandler: (String) -> Unit,
    linkable: (String) -> Boolean,
    diagrams: Boolean,
    find: NativeFind,
    onScrolled: (Boolean) -> Unit,
) {
    val link by rememberUpdatedState(linkHandler)
    val scrolled by rememberUpdatedState(onScrolled)
    var element by remember { mutableStateOf<HTMLElement?>(null) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val html = remember(markdown, linkable, diagrams) {
        MarkdownHtml.render(Markdown.parse(markdown), linkable, diagrams)
    }
    val dark = palette.dark
    val style = islandStyle(dark)
    HtmlElementView(
        factory = {
            installIslands()
            createIsland({ link(it) }, { scrolled(it) }).also { element = it }
        },
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        onRelease = {
            releaseIsland(it)
            element = null
        },
    )
    val shown = element ?: return
    // Compose draws menus, sheets and the find bar on its canvas, under the island: cut them out.
    val density = LocalDensity.current.density
    val layers = FloatingLayers.bounds.values.toList()
    val covered = layers.mapNotNull { it.intersect(bounds).takeIf { cut -> cut.width > 0 && cut.height > 0 } }
    val hidden = covered.any { it.width >= bounds.width - 1 && it.height >= bounds.height - 1 }
    val holes =
        if (hidden) ""
        else
            covered.joinToString(",") {
                listOf(it.left - bounds.left, it.top - bounds.top, it.width, it.height)
                    .joinToString(",") { px -> (px / density).toString() }
            }
    LaunchedEffect(shown, holes, hidden) { clipIsland(shown, holes, hidden) }
    LaunchedEffect(shown, style) { setIslandStyle(shown, style) }
    LaunchedEffect(shown, html) {
        setIslandHtml(shown, html)
        find.revision++
        if (diagrams && "class=\"diagram\"" in html) {
            val template = runCatching { DiagramAssets.load() }.getOrNull()?.let { diagramTemplate(it, dark) }
            if (template != null) renderIslandDiagrams(shown, template, dark)
        }
    }
    DisposableEffect(shown, find) {
        find.search = { findInIsland(shown, it) }
        find.show = { showIslandHit(shown, it) }
        onDispose {
            find.search = { 0 }
            find.show = {}
        }
    }
}

private fun Color.css(): String {
    val argb = toArgb()
    val a = ((argb ushr 24) and 0xFF) / 255.0
    return "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},${(a * 1000).toInt() / 1000.0})"
}

/** The palette and type scale as the island's CSS variables. */
@Composable
private fun islandStyle(dark: Boolean): String {
    val p = palette
    val link = if (p.accent == Color.Black || p.accent == Color.White) p.info else p.accent
    val t = type
    fun px(value: Float) = "${value}px"
    fun tok(light: Long, night: Long) = Color(if (dark) night else light).css()
    return listOf(
            "--md-text" to p.label.css(),
            "--md-secondary" to p.secondaryLabel.css(),
            "--md-background" to "transparent",
            "--md-link" to link.css(),
            "--md-fill" to p.fill.css(),
            "--md-inset" to p.inset.css(),
            "--md-separator" to p.separator.css(),
            "--md-selection" to colors.primary.copy(alpha = .28f).css(),
            "--md-mark" to p.warning.copy(alpha = if (dark) .45f else .32f).css(),
            "--md-mark-current" to p.warning.copy(alpha = if (dark) .8f else .65f).css(),
            "--md-body-size" to px(t.body.fontSize.value),
            "--md-body-line" to px(t.body.lineHeight.value.takeIf { it.isFinite() } ?: (t.body.fontSize.value * 1.45f)),
            "--md-h1" to px(t.title1.fontSize.value),
            "--md-h2" to px(t.title2.fontSize.value),
            "--md-h3" to px(t.title3.fontSize.value),
            "--tok-comment" to tok(0xFF5D6C79, 0xFF7F8C98),
            "--tok-string" to tok(0xFFC41A16, 0xFFFC6A5D),
            "--tok-number" to tok(0xFF1C00CF, 0xFFD0BF69),
            "--tok-keyword" to tok(0xFF9B2393, 0xFFFC5FA3),
            "--tok-type" to tok(0xFF0B4F79, 0xFF5DD8FF),
            "--tok-function" to tok(0xFF326D74, 0xFF67B7A4),
            "--tok-annotation" to tok(0xFF815F03, 0xFFBF8555),
            "--tok-property" to tok(0xFF3E6D8F, 0xFF41A1C0),
            "color-scheme" to if (dark) "dark" else "light",
        )
        .joinToString(";") { (name, value) -> "$name:$value" }
}
