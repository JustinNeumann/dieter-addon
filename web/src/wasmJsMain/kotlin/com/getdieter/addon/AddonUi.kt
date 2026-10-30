package com.getdieter.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonFailure
import com.getdieter.addon.core.AddonSubscription
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.Result
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Step
import com.getdieter.addon.v1.Tone
import com.getdieter.addon.v1.Update
import com.getdieter.mobile.design.*
import com.getdieter.mobile.store.MobileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The add-on core as the page reaches it: commands and slices of dieter/addon/v1/addon.proto, served by
 * the web host ([com.getdieter.web.RemoteCore]). The add-on's screens only draw slices and send commands;
 * what a page shows and what an action does is the add-on core's (addon/ in this repository).
 */
interface AddonClient {
    /** Throws [AddonFailure]. */
    suspend fun dispatch(command: Command): Result

    fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): AddonSubscription
}

internal val LocalAddon = staticCompositionLocalOf<AddonClient?> { null }

/** The add-on core's latest [slice] (for questions, [scope] is a conversation); null until it arrives. */
@Composable
internal fun rememberAddonSlice(slice: Slice, scope: String = ""): Update? {
    val client = LocalAddon.current ?: return null
    var update by remember(client, slice, scope) { mutableStateOf<Update?>(null) }
    DisposableEffect(client, slice, scope) {
        val subscription = client.observe(slice, scope) { update = it }
        onDispose { subscription.close() }
    }
    return update
}

/** Sends commands to the add-on core; its confirmation or failure becomes the app's message. */
internal class AddonActions(private val client: AddonClient?, private val store: MobileStore, private val scope: CoroutineScope) {
    fun send(command: Command, done: (Boolean) -> Unit = {}) {
        scope.launch { done(run(command)) }
    }

    /** Like [send]; [done] gets the core's result, null when the command failed. */
    fun sendFor(command: Command, done: (Result?) -> Unit) {
        scope.launch { done(result(command)) }
    }

    suspend fun run(command: Command): Boolean = result(command) != null

    private suspend fun result(command: Command): Result? {
        val addon = client ?: return null.also { store.error.value = "Not connected to the Dieter add-on." }
        return try {
            addon.dispatch(command).also { result -> result.message.takeIf { it.isNotEmpty() }?.let { store.error.value = it } }
        } catch (failure: AddonFailure) {
            store.error.value = failure.failure.message.ifEmpty { "The add-on could not do that." }
            null
        }
    }

    fun refresh() = send(Command(refresh = Step()))
}

@Composable
internal fun rememberAddonActions(store: MobileStore): AddonActions {
    val client = LocalAddon.current
    val scope = rememberCoroutineScope()
    return remember(client, store, scope) { AddonActions(client, store, scope) }
}

/** A row for a section whose slice has not arrived ([error] empty) or could not be read (tap to retry). */
@Composable
internal fun StatusRow(error: String, what: String, retry: () -> Unit) {
    Group(listOf(error)) { _, position ->
        if (error.isEmpty()) ListRow("Loading…", position = position, glyph = Glyph.HOURGLASS)
        else
            ListRow(
                "$what unavailable",
                position = position,
                subtitle = error,
                subtitleMaxLines = 3,
                glyph = Glyph.WARNING,
                glyphTint = palette.warning,
                onClick = retry,
            )
    }
}

@Composable
internal fun toneColor(tone: Tone): Color =
    when (tone) {
        Tone.TONE_SUCCESS -> palette.success
        Tone.TONE_WARNING -> palette.warning
        Tone.TONE_DANGER -> palette.destructive
        Tone.TONE_ACTIVE -> palette.accent
        else -> palette.secondaryLabel
    }

/** A dialog in the shared UI's style, for the add-on's multi-step flows. */
@Composable
internal fun AddonDialog(
    title: String,
    tag: String,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CoversWindow()
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = buttons,
    )
}

@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, tag: String = "") {
    TextButton(onClick, enabled = enabled, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag)) { Text(text) }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, tone: Color? = null) {
    Text(text, modifier, style = type.footnote, color = tone ?: palette.secondaryLabel)
}

/** The accessible name of a text field (its floating label is not exposed to screen readers). */
internal fun Modifier.named(name: String): Modifier = this.then(Modifier.semantics { contentDescription = name })

/** Copies [text]; [done] gets whether it worked (the Clipboard API needs a secure context and focus). */
@JsFun(
    """(text, done) => {
      const fallback = () => {
        try {
          const t = document.createElement('textarea');
          t.value = text; t.style.position = 'fixed'; t.style.opacity = '0';
          document.body.appendChild(t); t.select();
          const ok = document.execCommand('copy'); t.remove(); done(ok);
        } catch (e) { done(false); }
      };
      if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(() => done(true), fallback);
      else fallback();
    }"""
)
internal external fun copyText(text: String, done: (Boolean) -> Unit)
