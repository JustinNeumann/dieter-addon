package com.getdieter.mobile.platform

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import com.getdieter.mobile.design.palette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bundled offline renderer (MarkdownPreview) in a sandboxed frame island, as Android and iOS
 * run it in a WebView: vega-lite, vega and mermaid without any network.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun NativeMarkdownDiagram(
    assets: Map<String, ByteArray>,
    script: String,
    modifier: Modifier,
    onResult: (Double, String?) -> Unit,
) {
    val callback by rememberUpdatedState(onResult)
    val dark = palette.dark
    val template = remember(assets, dark) { diagramTemplate(assets, dark) }
    if (template == null) {
        LaunchedEffect(Unit) { callback(0.0, "The offline diagram renderer is unavailable.") }
        return
    }
    key(template, script) {
        HtmlElementView(
            factory = {
                installIslands()
                diagramFrame(template, script) { encoded ->
                    runCatching {
                            val json = Json.parseToJsonElement(encoded).jsonObject
                            if (json["stale"]?.jsonPrimitive?.booleanOrNull == true) return@runCatching
                            callback(
                                json["height"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                json["error"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                        .onFailure { callback(0.0, "The diagram could not render.") }
                }
            },
            modifier = modifier,
            onRelease = { releaseDiagramFrame(it) },
        )
    }
}
