package com.getdieter.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.getdieter.core.composition.Attachments
import com.getdieter.mobile.store.*
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

/** Opens what a browser shows itself (images, PDF, text, audio, video) in a new tab; anything else downloads. */
@Composable
internal actual fun rememberAttachmentViewer(
    onError: (String) -> Unit
): (com.getdieter.api.v1.MessagePart) -> Unit {
    val failure by rememberUpdatedState(onError)
    return { part ->
        val bytes = part.data_
        if (bytes.size == 0) failure("This attachment has no content to open.")
        else openBlob(bytes.base64(), part.media_type.ifEmpty { Attachments.OCTET_STREAM }, part.filename.ifEmpty { "attachment" })
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
