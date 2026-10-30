package com.getdieter.webhost

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.CompleteSignIn
import com.getdieter.core.client.ClientApi
import java.net.URI

/**
 * GitHub sign-in for a core that lives on a server. The core chooses where the gateway sends the browser
 * back for each attempt: the address the page is on (BeginSignIn.redirect_uri, which the page fills in),
 * or the add-on's pinned one (RuntimeConfig). The gateway accepts only addresses it lists
 * (DIETER_NATIVE_REDIRECT_URIS). The bridge relays that request to this host, which hands it to the core
 * under its configured callback address, the way an app hands over the URL its system delivered.
 *
 * The session then belongs to the add-on, not to a browser: signing in once (e.g. from a desktop
 * browser) signs in every device that opens the page, the Companion app included.
 */
internal class SignInRelay(private val api: ClientApi, private val callback: String) {
    /** Completes the attempt with the callback's query (code, state). Throws [ClientFailure]. */
    suspend fun complete(query: String) {
        api.dispatch(Command(complete_sign_in = CompleteSignIn("$callback?$query")))
        Log.line("signed in")
    }

    companion object {
        /** The callback address of a core whose attempts name their own return address. */
        const val INTERNAL_CALLBACK = "dieter-web://oauth/callback"

        /** Whether the add-on's pinned address can be one: http(s), ending in next/signin, no query. */
        fun isUsableCallback(url: String): Boolean =
            runCatching {
                    val uri = URI(url)
                    (uri.scheme == "https" || uri.scheme == "http") && uri.host != null && uri.rawQuery == null &&
                        uri.rawFragment == null && uri.path.endsWith("/next/signin")
                }
                .getOrDefault(false)
    }
}

/** The page the browser lands on after the gateway (served through the bridge at next/signin). */
internal fun signInPage(ok: Boolean, message: String): String {
    val text = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val title = if (ok) "Signed in" else "Sign-in failed"
    val refresh = if (ok) "<meta http-equiv=\"refresh\" content=\"2; url=./\">" else ""
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
$refresh<title>Dieter: $title</title><style>:root{color-scheme:light dark}body{font:16px/1.5 system-ui,sans-serif;max-width:34rem;margin:12vh auto;padding:24px}</style>
<script>try{var p=window.parent!==window&&window.parent.location;if(p&&/\/next\/signin$/.test(p.pathname))window.parent.history.replaceState(null,"",p.pathname.replace(/signin$/,""))}catch(e){}</script>
<h1>$title</h1><p>$text</p><p><a href="./">Open Dieter</a></p></html>"""
}
