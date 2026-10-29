package com.getdieter.webhost

import com.getdieter.addon.core.AddonCore
import com.getdieter.core.CoreRuntime
import com.getdieter.core.RuntimeConfig
import com.getdieter.core.client.ClientApi
import com.getdieter.core.platform.GrpcTunnelFactory
import com.getdieter.core.platform.OkHttpAuthHttp
import com.getdieter.core.platform.OkHttpRpcTransport
import com.getdieter.core.platform.Platform
import com.getdieter.core.platform.SoftwareDeviceKeys
import com.getdieter.web.v1.Hello
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The web host of the Dieter add-on (see proto/dieter/web/v1/host.proto). Environment:
 *   DIETER_WEB_HOST_STATE    state directory (device key, session, settings), default /data/web-host
 *   DIETER_WEB_HOST_BIND     default 127.0.0.1;  DIETER_WEB_HOST_PORT  default 8097
 *   DIETER_WEB_HOST_SECRET_FILE  file with the per-boot secret the bridge presents, "-" for stdin (unset: no check, tests only)
 *   DIETER_WEB_SIGN_IN_CALLBACK  pins the sign-in callback (https://<HA>/app/<slug>/next/signin); else the page tells it
 *   DIETER_WEB_BRIDGE_URL        the add-on's bridge API for the add-on core, default http://127.0.0.1:8099/api/
 *   DIETER_WEB_DEVICE_NAME       how this device appears in the account's device list
 *   DIETER_WEB_HOST_VERBOSE=1    core debug logs
 */
fun main() {
    val env = System.getenv()
    Log.verbose = env["DIETER_WEB_HOST_VERBOSE"] == "1"
    val state = File(env["DIETER_WEB_HOST_STATE"] ?: "/data/web-host")
    // "-" reads the secret from standard input (run.sh pipes it; reopening /dev/stdin after the user switch is denied)
    val secret =
        env["DIETER_WEB_HOST_SECRET_FILE"]
            ?.let { if (it == "-") System.`in`.bufferedReader().readLine().orEmpty() else File(it).readText() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    if (secret == null) Log.line("no DIETER_WEB_HOST_SECRET_FILE: every local process may connect (tests only)")
    WebHostServer(state, env, secret, env["DIETER_WEB_HOST_BIND"] ?: "127.0.0.1", env["DIETER_WEB_HOST_PORT"]?.toInt() ?: 8097)
        .start()
}

/** Dieter's core and the add-on core, plus the WebSocket server in front of them. */
class WebHostServer(
    state: File,
    env: Map<String, String>,
    secret: String?,
    bind: String,
    port: Int,
) {
    val runtime: CoreRuntime
    private val api: ClientApi
    val addon: AddonCore
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server: WebSocketServer
    private val signIn: SignInRelay
    private val foreground: Foreground

    init {
        state.mkdirs()
        runCatching { Files.setPosixFilePermissions(state.toPath(), PosixFilePermissions.fromString("rwx------")) }
        val deviceKeys =
            SoftwareDeviceKeys(
                File(state, "device-key"),
                platform = "web",
                deviceName = env["DIETER_WEB_DEVICE_NAME"] ?: "Home Assistant (web)",
            )
        val transport = OkHttpRpcTransport(deviceKeys)
        val pinned = env["DIETER_WEB_SIGN_IN_CALLBACK"]?.trim()?.takeIf { it.isNotEmpty() }
        if (pinned != null && !SignInRelay.isUsableCallback(pinned))
            Log.line("ignoring DIETER_WEB_SIGN_IN_CALLBACK=$pinned: expected http(s)://…/next/signin without query")
        // where the gateway sends the browser back: the add-on's pinned address, else each page's own
        val callback = pinned?.takeIf(SignInRelay::isUsableCallback)
        runtime =
            CoreRuntime(
                Platform(
                    transport = transport,
                    tunnels = GrpcTunnelFactory(transport),
                    deviceKeys = deviceKeys,
                    signatures = JcaSignatures,
                    secureStore = FileSecureStore(File(state, "secure.json")),
                    settings = FileDeviceSettings(File(state, "settings.json")),
                    http = OkHttpAuthHttp(),
                    fileSystem = FileSystem.SYSTEM,
                    stateDirectory = File(state, "core").apply { mkdirs() }.toOkioPath(),
                    logger = Log,
                ),
                RuntimeConfig(
                    clientVersion = BuildInfo.DIETER_RELEASE,
                    oauthRedirectUri = callback ?: SignInRelay.INTERNAL_CALLBACK,
                    oauthRedirectPerAttempt = callback == null,
                    includeLoopbackRoutes = true,
                    clientIdPrefix = "web",
                ),
            )
        api = ClientApi(runtime)
        signIn = SignInRelay(api, callback ?: SignInRelay.INTERNAL_CALLBACK)
        addon = AddonCore(OkHttpBridge(env["DIETER_WEB_BRIDGE_URL"] ?: "http://127.0.0.1:8099/api/"), commands)
        foreground = Foreground(api, commands, env["DIETER_WEB_HOST_IDLE_MS"]?.toLongOrNull() ?: 120_000)
        server =
            WebSocketServer(bind, port, secret, onHttp = ::http) { ws ->
                val user = ws.headers["x-remote-user-display-name"] ?: ws.headers["x-remote-user-name"].orEmpty()
                val hello = Hello(BuildInfo.DIETER_RELEASE, BuildInfo.VERSION, user)
                CoreConnection(api, runtime, addon, commands, ws, hello, foreground).run()
            }
        Log.line("Dieter core ${BuildInfo.DIETER_RELEASE}, web host ${BuildInfo.VERSION}, state $state")
    }

    /** GET …/signin?code=…&state=…: the gateway's redirect, relayed by the bridge. */
    private fun http(path: String, headers: Map<String, String>): HttpReply? {
        if (path.substringBefore('?').endsWith("/signin")) {
            val query = path.substringAfter('?', "")
            val page =
                try {
                    kotlinx.coroutines.runBlocking { signIn.complete(query) }
                    signInPage(true, "Dieter on this Home Assistant is signed in. Every device that opens it uses this sign-in.")
                } catch (failure: com.getdieter.core.client.ClientFailure) {
                    signInPage(false, failure.failure.message.ifEmpty { "The gateway did not accept the sign-in." })
                }
            return HttpReply(200, "text/html; charset=utf-8", page)
        }
        return null
    }

    /** Starts the core and the server; returns the bound port. */
    fun start(): Int {
        runtime.start()
        addon.start()
        return server.start()
    }

    fun stop() {
        server.stop()
        addon.stop()
        commands.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        kotlinx.coroutines.runBlocking { runtime.shutdown() }
    }
}
