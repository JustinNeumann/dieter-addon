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
 * The web host of the Dieter add-on (see proto/dieter/web/v1/host.proto), and the add-on's front door on its port
 * ([FrontDoor]). Environment:
 *   DIETER_WEB_HOST_STATE    state directory (device key, session, settings), default /data/web-host
 *   BRIDGE_BIND, BRIDGE_PORT the front door, default 0.0.0.0:8099 (the add-on's Ingress port)
 *   BRIDGE_INGRESS_NETS      address ranges besides the Supervisor's network that reach the page's core (e2e only)
 *   DIETER_WEB_DIR           the page's bundle, default /opt/web
 *   DIETER_OPERATOR_URL      the operator, which answers /api/, default http://127.0.0.1:8096/
 *   DIETER_WEB_SIGN_IN_CALLBACK  pins the sign-in callback (https://<HA>/app/<slug>/next/signin); else the page tells it
 *   DIETER_WEB_BRIDGE_URL    the API the add-on core calls, default the operator's (…/api/)
 *   DIETER_WEB_DEVICE_NAME   how this device appears in the account's device list
 *   DIETER_WEB_HOST_VERBOSE=1    core debug logs
 */
fun main() {
    val env = System.getenv()
    Log.verbose = env["DIETER_WEB_HOST_VERBOSE"] == "1"
    val state = File(env["DIETER_WEB_HOST_STATE"] ?: "/data/web-host")
    val ingress = listOf(Net.SUPERVISOR) + Net.list(env["BRIDGE_INGRESS_NETS"])
    WebHostServer(
        state, env, env["BRIDGE_BIND"] ?: "0.0.0.0", env["BRIDGE_PORT"]?.toInt() ?: 8099,
        allowed = { it.isLoopbackAddress || it in Net.SUPERVISOR },   // Ingress, Home Assistant core, the add-on's own processes
        ingress = { a -> ingress.any { a in it } },                     // the page's core: never loopback, where the agents are
    ).start()
}

/** Dieter's core and the add-on core, behind the add-on's front door. */
class WebHostServer(
    state: File,
    env: Map<String, String>,
    bind: String,
    port: Int,
    allowed: (java.net.InetAddress) -> Boolean,
    ingress: (java.net.InetAddress) -> Boolean,
) {
    val runtime: CoreRuntime
    private val api: ClientApi
    val addon: AddonCore
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server: FrontDoor
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
        val operator = java.net.URI.create(env["DIETER_OPERATOR_URL"] ?: "http://127.0.0.1:8096/")
        addon = AddonCore(
            OkHttpBridge(env["DIETER_WEB_BRIDGE_URL"] ?: (operator.toString().trimEnd('/') + "/api/")),
            commands,
            offsetSeconds = { at, zone ->
                val id = runCatching { java.time.ZoneId.of(zone) }.getOrDefault(java.time.ZoneId.systemDefault())
                id.rules.getOffset(java.time.Instant.ofEpochMilli(at.toEpochMilliseconds())).totalSeconds
            },
            timezone = { java.util.TimeZone.getDefault().id },
        )
        foreground = Foreground(api, commands, env["DIETER_WEB_HOST_IDLE_MS"]?.toLongOrNull() ?: 120_000)
        server =
            FrontDoor(bind, port, File(env["DIETER_WEB_DIR"] ?: "/opt/web"), operator, allowed, ingress, onSignIn = ::signIn) { ws ->
                val user = ws.headers["x-remote-user-display-name"] ?: ws.headers["x-remote-user-name"].orEmpty()
                val hello = Hello(BuildInfo.DIETER_RELEASE, BuildInfo.VERSION, user)
                CoreConnection(api, runtime, addon, commands, ws, hello, foreground).run()
            }
        Log.line("Dieter core ${BuildInfo.DIETER_RELEASE}, web host ${BuildInfo.VERSION}, state $state")
    }

    /** GET next/signin?code=…&state=…: the gateway's redirect after sign-in. */
    private fun signIn(query: String): HttpReply {
        val page =
            try {
                kotlinx.coroutines.runBlocking { signIn.complete(query) }
                signInPage(true, "Dieter on this Home Assistant is signed in. Every device that opens it uses this sign-in.")
            } catch (failure: com.getdieter.core.client.ClientFailure) {
                signInPage(false, failure.failure.message.ifEmpty { "The gateway did not accept the sign-in." })
            }
        return HttpReply(200, "text/html; charset=utf-8", page)
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
