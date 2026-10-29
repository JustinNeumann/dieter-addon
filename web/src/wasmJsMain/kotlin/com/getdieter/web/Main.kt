package com.getdieter.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.getdieter.mobile.platform.browserMonospace
import com.getdieter.mobile.resources.Res
import com.getdieter.mobile.app.MobileApp
import com.getdieter.mobile.store.MobilePreferences
import com.getdieter.mobile.store.MobileStore
import com.getdieter.mobile.store.runDebugScript
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The real Dieter Compose UI in the browser, driven by [DemoCore] instead of a gateway.
 * `?script=tab projects; board Main` replays the debug navigation script used for screenshots.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val store =
        MobileStore(
            DemoCore(),
            preferences =
                MobilePreferences(
                    read = { storageRead("dieter.$it") },
                    write = { key, value -> storageWrite("dieter.$key", value) },
                ),
        )
    val script = queryParameter("script").orEmpty()
    MainScope().launch {
        browserMonospace =
            runCatching {
                    FontFamily(Font("JetBrains Mono", Res.readBytes("files/jetbrains_mono_regular.ttf")))
                }
                .getOrNull()
        show(store, script)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun show(store: MobileStore, script: String) {
    ComposeViewport(viewportContainerId = "dieter") {
        LaunchedEffect(Unit) {
            hideLoading()
            if (script.isNotBlank()) store.runDebugScript(script)
        }
        MobileApp(store, openUrl = ::openInNewTab)
    }
}
