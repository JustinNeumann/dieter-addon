package com.getdieter.core.runtime

/** The browser runs the core on one thread, so the lock only has to run [block]. */
actual class CoreLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
