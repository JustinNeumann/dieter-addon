package com.getdieter.addon

import androidx.compose.runtime.*
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.SettingChange
import com.getdieter.addon.v1.Slice
import com.getdieter.mobile.settings.DieterPalette
import com.getdieter.mobile.store.MobileStore

/**
 * The palette of Settings → Appearance for every device and page load: the add-on keeps the one picked last
 * (SettingsSlice.palette) and the page takes it over, also into its local preferences, so the next load starts in
 * it. A pick on this page goes to the add-on, and pages open elsewhere follow. A page that finds none kept yet hands
 * over its own pick, if it made one.
 */
@Composable
internal fun PaletteSync(store: MobileStore) {
    val settings = rememberAddonSlice(Slice.SLICE_SETTINGS)?.settings ?: return
    val saved = settings.palette
    val local by store.theme.palette.collectAsState()
    val actions = rememberAddonActions(store)
    // The add-on's answer to a pick that is on its way may still name the palette before it
    var sent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) {
        if (sent != null && sent != saved) return@LaunchedEffect
        sent = null
        if (saved.isNotEmpty()) DieterPalette.resolve(saved).takeIf { it != store.theme.palette.value }?.let(store.theme::setPalette)
    }
    LaunchedEffect(local) {
        // the current value: on the first run the effect above has just applied the kept palette
        val pick = store.theme.palette.value
        if (pick.slug == saved || (saved.isEmpty() && pick == DieterPalette.DEFAULT) || pick.slug == sent) return@LaunchedEffect
        sent = pick.slug
        actions.send(Command(change_setting = SettingChange(palette = pick.slug))) { if (!it) sent = null }
    }
}
