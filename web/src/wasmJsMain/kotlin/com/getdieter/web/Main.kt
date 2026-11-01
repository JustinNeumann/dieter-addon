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
import com.getdieter.addon.AddonClient
import com.getdieter.addon.LocalAddon
import com.getdieter.addon.PaletteSync
import com.getdieter.addon.addonExtensions
import com.getdieter.client.v1.Command
import com.getdieter.client.v1.OpenConversationRequest
import com.getdieter.mobile.app.MobileApp
import com.getdieter.mobile.extensions.LocalMobileExtensions
import com.getdieter.mobile.design.FindRequests
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
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.navigation.MobileTab
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The real Dieter Compose UI in the browser. Its core runs in the add-on's web host and is reached
 * over a WebSocket at `core` next to this page ([RemoteCore]), and so is the add-on core for the
 * add-on's own features. `?demo` uses [DemoCore] for Dieter's data instead (no account); the add-on's
 * features still come from the web host. `?demo=standalone` needs no web host at all: the add-on core runs in
 * the page over sample data too ([DemoAddon], the demo on GitHub Pages). `?script=tab projects; board Main`
 * replays the debug navigation script used for screenshots.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    WebHost.start()
    val demo = queryParameter("demo")
    val remote = if (demo == "standalone") null else RemoteCore("core", MainScope()).also { it.start() }
    val core: MobileCore = if (demo != null || remote == null) DemoCore() else remote
    val addon: AddonClient = remote ?: DemoAddon(MainScope(), freshDreamfall = queryParameter("dreamfall") == "fresh")
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
    onFindShortcut { FindRequests.request() }
    val route = queryParameter("route").orEmpty()
    PanelRoutes.start(route, focus = demo == null && route.isBlank() && script.isBlank())
    MainScope().launch {
        browserMonospace =
            runCatching {
                    FontFamily(Font("JetBrains Mono", Res.readBytes("files/jetbrains_mono_regular.ttf")))
                }
                .getOrNull()
        show(store, addon, script, route)
    }
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private fun show(store: MobileStore, addon: AddonClient, script: String, route: String) {
    val extensions = addonExtensions()
    ComposeViewport(viewportContainerId = "dieter") {
        val host by WebHost.theme.collectAsState()
        // Appearance "System" means the host's mode: Home Assistant's dark mode behind Ingress (it can
        // differ from the device's), else the device. The user's own Light/Dark choice still wins.
        CompositionLocalProvider(LocalSystemTheme provides if (host.dark) SystemTheme.Dark else SystemTheme.Light) {
            LaunchedEffect(Unit) {
                hideLoading()
                if (route.isNotBlank()) openRoute(store, route)
                if (script.isNotBlank()) store.runDebugScript(script)
                for (later in PanelRoutes.later) openRoute(store, later)
            }
            ChromeColor(store)
            BrowserBack()
            CompositionLocalProvider(LocalMobileExtensions provides extensions, LocalAddon provides addon) {
                PaletteSync(store)
                MobileApp(store, openUrl = ::openInNewTab)
            }
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
 * Deep links: `?route=card/<id>` opens that conversation where the core says it belongs (or the Inbox
 * once it is gone), `?route=tab/<name>` a tab, `?route=dreamfall/<id>[/settings]` a Dreamfall board (or a
 * stage board) and `?route=idea/<id>` an idea. The add-on's root page turns `<HA>/app/<slug>/next/<route>` into this (Home
 * Assistant always opens the add-on at its root); routes that arrive while the page is open come from [PanelRoutes].
 */
private suspend fun openRoute(store: MobileStore, route: String) {
    val parts = route.trim('/').split('/')
    when (parts.firstOrNull()) {
        "card", "chat" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { id ->
            val target =
                runCatching { store.core.dispatch(Command(open_conversation_request = OpenConversationRequest(id))) }
                    .getOrNull()
                    ?.notification_target
            when {
                target?.card_id?.isNotEmpty() == true -> store.openConversation(target.card_id!!)
                target?.inbox != null -> store.selectTab(MobileTab.INBOX)
            }
        }
        // the four destinations; links to the tabs before 0.6.432 (mail, channels, tools) open their place in More
        "tab" -> parts.getOrNull(1)?.let { name ->
            when (name.lowercase()) {
                "mail" -> store.openMore(MobileRoute.Mailbox)
                "channels" -> store.openMore(MobileRoute.Channels)
                "tools" -> store.selectTab(MobileTab.MORE)
                else -> MobileTab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(store::selectTab)
            }
        }
        // More → Dreamfall, a Dreamfall board (its ID or a stage board's) with its settings, or one idea
        "dreamfall" -> {
            store.openMore(MobileRoute.Extension("dreamfall"))
            parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { id ->
                store.push(MobileRoute.Extension("dreamfall", id))
                if (parts.getOrNull(2) == "settings") store.present(MobileRoute.Extension("dreamfall-form", id))
            }
        }
        "idea" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { store.push(MobileRoute.Extension("dreamfall-idea", it)) }
    }
}
