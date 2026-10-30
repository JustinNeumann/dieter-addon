package com.getdieter.core.runtime

@JsFun("() => crypto.getRandomValues(new Uint8Array(1))[0]")
private external fun cryptoRandomByte(): Int

actual fun secureRandomBytes(count: Int): ByteArray =
    ByteArray(count) { cryptoRandomByte().toByte() }
