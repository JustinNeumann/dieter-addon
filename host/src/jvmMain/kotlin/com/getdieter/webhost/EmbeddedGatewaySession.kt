package com.getdieter.webhost

import com.getdieter.addon.core.Bridge
import com.getdieter.core.CoreRuntime
import com.getdieter.core.connection.ConnectionPhase
import com.getdieter.core.identity.Gateway
import com.getdieter.core.trust.TrustState
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * In `embedded` gateway mode, adopts the local gateway's pre-seeded session token and pairs the web host's device
 * with the local daemon automatically via the operator's `POST /api/gateway/embedded-trust` route.
 */
internal class EmbeddedGatewaySession(
    private val runtime: CoreRuntime,
    private val bridge: Bridge,
    private val foreground: Foreground,
    private val scope: CoroutineScope,
    private val bootstrapFile: File,
) {
    private val lease = Any()

    private data class Bootstrap(
        val gateway: Gateway,
        val sessionToken: String,
        val pairingCode: String,
    )

    fun start(): Job = scope.launch {
        var adopted: Bootstrap? = null
        var holdingForeground = false
        fun setHolding(active: Boolean) {
            if (holdingForeground != active) {
                holdingForeground = active
                foreground.set(lease, active)
            }
        }
        try {
            while (isActive) {
                val bootstrap = readBootstrap()
                if (bootstrap == null) {
                    delay(300)
                    continue
                }
                val activeOrigin = runtime.accounts.state.value.active.origin
                val phase = runtime.connection.state.value.phase
                if (adopted != bootstrap || activeOrigin != bootstrap.gateway.origin || phase == ConnectionPhase.AUTH_REQUIRED) {
                    setHolding(true)
                    runCatching {
                        runtime.adoptSession(bootstrap.gateway, bootstrap.sessionToken, bootstrap.pairingCode)
                        adopted = bootstrap
                        Log.line("embedded gateway: adopted session for ${bootstrap.gateway.origin}")
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.line("embedded gateway: adoptSession failed: ${e.message}")
                    }
                    delay(400)
                    continue
                }

                val trust = runtime.trust.view.value
                when (trust.state) {
                    TrustState.TRUSTED -> {
                        setHolding(false)
                        delay(3_000)
                    }
                    TrustState.CONFLICT, TrustState.REVOKED -> {
                        setHolding(true)
                        Log.line("embedded gateway: trust is ${trust.state}, resetting and re-pairing")
                        runCatching {
                            runtime.resetTrust()
                            runtime.adoptSession(bootstrap.gateway, bootstrap.sessionToken, bootstrap.pairingCode)
                        }
                        delay(500)
                    }
                    TrustState.PENDING, TrustState.NO_ROSTER -> {
                        setHolding(true)
                        val code = trust.pairingCode?.ifBlank { null } ?: bootstrap.pairingCode
                        runCatching {
                            bridge.request("POST", "gateway/embedded-trust", buildJsonObject { put("pairing_code", code) })
                        }
                        runCatching { runtime.refreshDevices() }
                        delay(500)
                    }
                    TrustState.UNKNOWN -> {
                        setHolding(true)
                        delay(400)
                    }
                }
            }
        } finally {
            setHolding(false)
        }
    }

    private fun readBootstrap(): Bootstrap? {
        if (!bootstrapFile.isFile) return null
        return runCatching {
            val obj = Json.parseToJsonElement(bootstrapFile.readText()) as? JsonObject ?: return null
            val url = (obj["gatewayUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val token = (obj["sessionToken"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val code = (obj["pairingCode"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val gateway = Gateway.parse(url, "Home Assistant") ?: return null
            if (token.isEmpty() || code.isEmpty()) return null
            Bootstrap(gateway, token, code)
        }.getOrNull()
    }
}
