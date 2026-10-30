package com.getdieter.webhost

import com.getdieter.core.platform.JcaEcdsa
import com.getdieter.core.platform.SignatureVerifier
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Ed25519 and ECDSA P-256 through the JDK's providers (as core/testing's JcaSignatureVerifier). */
internal object JcaSignatures : SignatureVerifier {
    private val spkiPrefix =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiPrefix + publicKey))
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update(message)
                    verify(signature)
                }
            }
            .getOrDefault(false)

    override fun verifyEcdsaP256(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        JcaEcdsa.verify(publicKey, message, signature)
}
