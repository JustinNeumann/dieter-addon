package com.getdieter.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * The browser's Back button as the app's back: while something can go back (a screen, a sheet, New
 * task), one history entry of the page's own sits on top, and leaving it is a back event for Compose's
 * handlers, as Escape is. Behind Home Assistant the entry belongs to the add-on's frame, so Back first
 * goes back inside Dieter and then leaves the add-on as before.
 */
@Composable
internal fun BrowserBack() {
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    DisposableEffect(owner) {
        val input = BrowserBackInput()
        owner.navigationEventDispatcher.addInput(input)
        onDispose { owner.navigationEventDispatcher.removeInput(input) }
    }
}

private const val MARK = "dieter-back"
private const val REARM_MS = 150

private class BrowserBackInput : NavigationEventInput() {
    /** The page's entry is the current one. */
    private var armed = false
    private var enabled = false
    /** The next popstate comes from our own history.back(). */
    private var skip = false
    private val listener: (Event) -> Unit = { popped() }

    override fun onAdded(dispatcher: NavigationEventDispatcher) {
        armed = onMark()
        window.addEventListener("popstate", listener)
    }

    override fun onRemoved() {
        window.removeEventListener("popstate", listener)
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        enabled = hasEnabledHandlers
        sync()
    }

    private fun onMark() = window.history.state?.toString() == MARK

    /** One entry while something can go back, none otherwise. */
    private fun sync() {
        if (enabled && !armed) {
            window.history.pushState(MARK.toJsString(), "")
            armed = true
        } else if (!enabled && armed) {
            armed = false
            skip = true
            window.history.back()
        }
    }

    private fun popped() {
        if (skip) {
            skip = false
            return
        }
        when {
            // Forward onto the page's entry again
            onMark() -> {
                armed = true
                sync()
            }
            armed -> {
                armed = false
                dispatchOnBackStarted(NavigationEvent())
                dispatchOnBackCompleted()
                // whether something can still go back is known once the screens have changed
                window.setTimeout({ sync(); null }, REARM_MS)
            }
        }
    }
}
