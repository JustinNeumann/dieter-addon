package com.getdieter.webhost

import com.getdieter.core.runtime.CoreLogger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lines on stderr like the bridge's, so the add-on log reads as one. */
internal object Log : CoreLogger {
    var verbose = false
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun line(message: String) = System.err.println("[${LocalTime.now().format(time)}] web-host: $message")

    override fun debug(tag: String, message: String) {
        if (verbose) line("D/$tag: $message")
    }

    override fun info(tag: String, message: String) = line("I/$tag: $message")

    override fun warn(tag: String, message: String, error: Throwable?) =
        line("W/$tag: $message${error?.let { " ($it)" } ?: ""}")
}
