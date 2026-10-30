package com.getdieter.mobile.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.getdieter.core.composition.Attachments
import com.getdieter.core.files.FilePaths
import com.getdieter.mobile.design.*
import com.getdieter.mobile.store.*
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64

// No terminal or screen canvas on the web yet; files go through the browser's own picker, tabs and share sheet.
@Composable internal actual fun NativeTerminal(store: MobileStore, modifier: Modifier) = Unit

@Composable internal actual fun NativeScreenCanvas(store: MobileStore, modifier: Modifier) = Unit

/** The browser's file dialog; the files are read in the page and checked against [Attachments]' limits. */
@Composable
internal actual fun rememberAttachmentPicker(
    onPicked: (List<com.getdieter.api.v1.MessagePart>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val receiver by rememberUpdatedState(onPicked)
    val failure by rememberUpdatedState(onError)
    return {
        pickFiles(
            Attachments.MAX_COUNT,
            Attachments.MAX_FILE_BYTES.toDouble(),
            { files ->
                try {
                    val picked =
                        Json.parseToJsonElement(files).jsonArray.map { file ->
                            val (name, type, data) = file.jsonArray.map { it.jsonPrimitive.content }
                            Triple(name, type, requireNotNull(data.decodeBase64()) { "Could not read $name" })
                        }
                    Attachments.limitError(picked.map { it.first }, picked.map { it.third.size.toLong() })?.let { error(it) }
                    receiver(picked.map { (name, type, bytes) -> Attachments.part(name, type.ifEmpty { null }, bytes) })
                } catch (error: Exception) {
                    failure(error.message ?: "Could not read the attachment")
                }
            },
        ) { problem ->
            failure(
                when (problem) {
                    "too-many" -> Attachments.TOO_MANY
                    "too-large" -> Attachments.FILE_TOO_LARGE
                    else -> problem
                }
            )
        }
    }
}

/**
 * Images open full screen over the page (a tap, ✕ or Escape closes them); what else a browser shows itself (PDF,
 * text, audio, video) opens in a new tab, and anything else downloads.
 */
@Composable
internal actual fun rememberAttachmentViewer(
    onError: (String) -> Unit
): (com.getdieter.api.v1.MessagePart) -> Unit {
    val failure by rememberUpdatedState(onError)
    var shown by remember { mutableStateOf<Pair<com.getdieter.api.v1.MessagePart, ImageBitmap>?>(null) }
    shown?.let { (part, bitmap) -> ImageViewer(part, bitmap) { shown = null } }
    return { part ->
        val bytes = Attachments.bytes(part)
        val bitmap =
            bytes
                ?.takeIf { FilePaths.isImage(part.filename, part.media_type) }
                ?.let { runCatching { it.toByteArray().decodeToImageBitmap() }.getOrNull() }
        when {
            bytes == null -> failure("This attachment has no content to open.")
            bitmap != null -> shown = part to bitmap
            else -> openBlob(bytes.base64(), part.media_type.ifEmpty { Attachments.OCTET_STREAM }, part.filename.ifEmpty { "attachment" })
        }
    }
}

/** Over the whole window: a popup, as menus are (a Dialog's accessibility nodes outlive it on the web). */
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset.Zero
}

@Composable
private fun ImageViewer(part: com.getdieter.api.v1.MessagePart, bitmap: ImageBitmap, onClose: () -> Unit) {
    val name = part.filename.ifEmpty { "Image" }
    CoversWindow()
    Popup(WholeWindow, onClose, PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = .88f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                .testTag("image-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            // as large as the window allows, never above its own size
            Image(bitmap, name, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Inside)
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleButton(Glyph.OPEN, "Open in a new tab", {
                    Attachments.bytes(part)?.let { openBlob(it.base64(), part.media_type.ifEmpty { "image/png" }, name) }
                }, tint = Color.White)
                CircleButton(Glyph.CLOSE, "Close", onClose, tint = Color.White)
            }
        }
    }
}

/** The system share sheet where the browser has one (phones, Safari); elsewhere the text downloads as Markdown. */
@Composable
internal actual fun rememberTextSharer(): (subject: String, text: String) -> Unit = { subject, text ->
    shareText(subject, text, subject.replace(Regex("""[^\w.-]+"""), " ").trim().ifEmpty { "Dieter" }.take(80) + ".md")
}

@JsFun(
    """(maxCount, maxBytes, onFiles, onError) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = true;
      input.style.display = 'none';
      input.addEventListener('change', async () => {
        const files = Array.from(input.files || []);
        input.remove();
        if (!files.length) return;
        if (files.length > maxCount) return onError('too-many');
        if (files.some((f) => f.size > maxBytes)) return onError('too-large');
        try {
          const out = [];
          for (const f of files) {
            const bytes = new Uint8Array(await f.arrayBuffer());
            let bin = '';
            for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
            out.push([f.name, f.type, btoa(bin)]);
          }
          onFiles(JSON.stringify(out));
        } catch (e) { onError(String((e && e.message) || e)); }
      });
      document.body.appendChild(input);
      input.click();
    }"""
)
private external fun pickFiles(maxCount: Int, maxBytes: Double, onFiles: (String) -> Unit, onError: (String) -> Unit)

@JsFun(
    """(data, type, name) => {
      const bin = atob(data);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const url = URL.createObjectURL(new Blob([bytes], { type }));
      const a = document.createElement('a');
      a.href = url;
      if (/^(image|audio|video|text)\//.test(type) || type === 'application/pdf') a.target = '_blank';
      else a.download = name;
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    }"""
)
private external fun openBlob(data: String, type: String, name: String)

@JsFun(
    """(title, text, name) => {
      const download = () => {
        const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown' }));
        const a = document.createElement('a');
        a.href = url; a.download = name;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      };
      if (navigator.share) navigator.share({ title, text }).catch((e) => { if (e && e.name !== 'AbortError') download(); });
      else download();
    }"""
)
private external fun shareText(title: String, text: String, name: String)
