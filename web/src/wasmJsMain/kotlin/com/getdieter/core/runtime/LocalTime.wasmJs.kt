package com.getdieter.core.runtime

import kotlin.time.Instant

@JsFun("(ms) => -new Date(ms).getTimezoneOffset() * 60")
private external fun browserUtcOffsetSeconds(millis: Double): Int

actual fun localUtcOffsetSeconds(at: Instant): Int =
    browserUtcOffsetSeconds(at.toEpochMilliseconds().toDouble())
