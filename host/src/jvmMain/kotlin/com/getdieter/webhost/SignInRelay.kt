package com.getdieter.webhost

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.CompleteSignIn
import com.getdieter.client.v1.Done
import com.getdieter.client.v1.Failure
import com.getdieter.client.v1.Result
import com.getdieter.client.v1.SignInStarted
import com.getdieter.core.CoreRuntime
import com.getdieter.core.client.ClientApi
import com.getdieter.core.client.ClientFailure
import com.getdieter.core.identity.Gateway
import com.getdieter.core.identity.SignIn
import java.net.URI

/**
 * GitHub sign-in for a core that lives on a server. The gateway sends the browser back to an exact,
 * registered address (DIETER_NATIVE_REDIRECT_URIS); for the web host that is an address of Home
 * Assistant that reaches this add-on's page, which differs by how the person reaches HA (Nabu Casa,
 * LAN, own domain). So each sign-in starts with the address the page is on (or the add-on's pinned
 * one), and the callback's code reaches the core under the host's fixed internal callback: the core
 * only checks the callback's prefix locally and exchanges code + PKCE verifier with the gateway.
 *
 * The session then belongs to the add-on, not to a browser: signing in once (e.g. from a desktop
 * browser) signs in every device that opens the page, the Companion app included.
 */
internal class SignInRelay(
    private val runtime: CoreRuntime,
    private val api: ClientApi,
    val pinned: String?,
) {
    suspend fun begin(gatewayUrl: String, pageCallback: String?): Result {
        if (isCallback(gatewayUrl)) {
            // The address the browser landed on, pasted into the gateway field: complete with it.
            complete(gatewayUrl.substringAfter('?', ""))
            return Result(done = Done())
        }
        val callback =
            pinned
                ?: pageCallback?.takeIf(::isUsableCallback)
                ?: throw ClientFailure(
                    Failure(
                        Failure.Kind.KIND_INVALID,
                        "Open Dieter through Home Assistant to sign in: this page could not tell its own address.",
                    )
                )
        val gateway =
            Gateway.parse(gatewayUrl.trim(), "Custom")
                ?: throw ClientFailure(Failure(Failure.Kind.KIND_INVALID, "That is not a valid gateway address."))
        val url =
            try {
                runtime.onCore {
                    SignIn(runtime.storage, runtime.platform.http, runtime.credentials, callback, runtime.platform.clock)
                        .begin(gateway)
                }
            } catch (error: ClientFailure) {
                throw error
            } catch (error: Exception) {
                throw ClientFailure(Failure(Failure.Kind.KIND_PERMANENT, error.message ?: "Sign-in could not start."))
            }
        Log.line("sign-in to ${gateway.origin} started; callback $callback")
        return Result(sign_in_started = SignInStarted(url))
    }

    /** Completes the attempt with the callback's query (code, state). Throws [ClientFailure]. */
    suspend fun complete(query: String) {
        api.dispatch(Command(complete_sign_in = CompleteSignIn("$INTERNAL_CALLBACK?$query")))
        Log.line("signed in")
    }

    companion object {
        /** The core's own (never registered) callback: only its prefix is checked, locally. */
        const val INTERNAL_CALLBACK = "dieter-web://oauth/callback"

        fun isCallback(url: String): Boolean =
            url.trim().let { it.startsWith("http") && it.substringBefore('?').endsWith("/next/signin") && "code=" in it }

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
