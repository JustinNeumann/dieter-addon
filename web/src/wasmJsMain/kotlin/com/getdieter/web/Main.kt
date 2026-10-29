package com.getdieter.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.getdieter.mobile.app.MobileApp
import com.getdieter.mobile.design.isDark
import com.getdieter.mobile.design.materialScheme
import com.getdieter.mobile.platform.WebHost
import com.getdieter.mobile.platform.browserMonospace
import com.getdieter.mobile.platform.platformDynamicScheme
import com.getdieter.mobile.resources.Res
import com.getdieter.mobile.store.MobileCore
import com.getdieter.mobile.store.MobilePreferences
import com.getdieter.mobile.store.MobileStore
import com.getdieter.mobile.store.runDebugScript
import com.getdieter.mobile.navigation.MobileTab
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * The real Dieter Compose UI in the browser. Its core runs in the add-on's web host and is reached
 * over a WebSocket at `core` next to this page ([RemoteCore]); `?demo` uses [DemoCore] instead (no
 * account, nothing leaves the page). `?script=tab projects; board Main` replays the debug navigation
 * script used for screenshots.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    WebHost.start()
    val core: MobileCore =
        if (queryParameter("demo") != null) DemoCore() else RemoteCore("core", MainScope()).also { it.start() }
    val store =
        MobileStore(
            core,
            preferences =
                MobilePreferences(
                    read = { storageRead("dieter.$it") },
                    write = { key, value -> storageWrite("dieter.$key", value) },
                ),
        )
    // The tab's visibility is the app's foreground (Android: onResume/onPause): the core keeps its streams
    // open only while shown. The web host combines all tabs.
    store.setForeground(!pageHidden())
    onVisibilityChange { store.setForeground(!pageHidden()) }
    val script = queryParameter("script").orEmpty()
    val route = queryParameter("route").orEmpty()
    MainScope().launch {
        browserMonospace =
            runCatching {
                    FontFamily(Font("JetBrains Mono", Res.readBytes("files/jetbrains_mono_regular.ttf")))
                }
                .getOrNull()
        show(store, script, route)
    }
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private fun show(store: MobileStore, script: String, route: String) {
    ComposeViewport(viewportContainerId = "dieter") {
        val host by WebHost.theme.collectAsState()
        // Appearance "System" means the host's mode: Home Assistant's dark mode behind Ingress (it can
        // differ from the device's), else the device. The user's own Light/Dark choice still wins.
        CompositionLocalProvider(LocalSystemTheme provides if (host.dark) SystemTheme.Dark else SystemTheme.Light) {
            LaunchedEffect(Unit) {
                hideLoading()
                if (route.isNotBlank()) openRoute(store, route)
                if (script.isNotBlank()) store.runDebugScript(script)
            }
            ChromeColor(store)
            MobileApp(store, openUrl = ::openInNewTab)
        }
    }
}

/** Tells the loader which background Compose draws, so the next load starts without a flash. */
@Composable
private fun ChromeColor(store: MobileStore) {
    val dark = store.isDark()
    val palette by store.theme.palette.collectAsState()
    val dynamic by store.theme.dynamicColor.collectAsState()
    val scheme = (if (dynamic) platformDynamicScheme(dark) else null) ?: materialScheme(palette, dark)
    LaunchedEffect(scheme.surface, dark) { WebHost.setChrome(scheme.surface, dark) }
}

/**
 * Deep links: `?route=card/<id>` opens that card's conversation, `?route=tab/<name>` a tab. The classic page
 * turns `<HA>/app/<slug>/next/<route>` into this (Home Assistant always opens the add-on at its root).
 */
private suspend fun openRoute(store: MobileStore, route: String) {
    val parts = route.trim('/').split('/')
    when (parts.firstOrNull()) {
        "card", "chat" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { id ->
            withTimeoutOrNull(30_000) { store.workspace.slice.first { it.loaded } }
            store.openConversation(id)
        }
        "tab" -> parts.getOrNull(1)?.let { name ->
            MobileTab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(store::selectTab)
        }
    }
}
