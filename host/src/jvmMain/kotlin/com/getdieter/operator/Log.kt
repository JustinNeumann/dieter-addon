package com.getdieter.operator

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lines on stderr like the bridge's, so the add-on log reads as one. */
internal object Log {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun line(message: String) = System.err.println("[${LocalTime.now().format(time)}] operator: $message")
}
