package com.getdieter.web

@JsFun("(k) => { try { return window.localStorage.getItem(k) } catch (e) { return null } }")
internal external fun storageRead(key: String): String?

@JsFun("(k, v) => { try { window.localStorage.setItem(k, v) } catch (e) {} }")
internal external fun storageWrite(key: String, value: String)

@JsFun("(u) => { window.open(u, '_blank', 'noopener') }")
internal external fun openInNewTab(url: String)

@JsFun("(name) => new URLSearchParams(window.location.search).get(name)")
internal external fun queryParameter(name: String): String?

@JsFun("() => { const l = document.getElementById('loading'); if (l) l.remove() }")
internal external fun hideLoading()

@JsFun("() => document.hidden")
internal external fun pageHidden(): Boolean

@JsFun("(cb) => document.addEventListener('visibilitychange', () => cb())")
internal external fun onVisibilityChange(callback: () -> Unit)

/**
 * Ctrl/⌘+F anywhere in the page: [handler] opens the shared UI's find bar and answers whether a
 * screen took it. Only then is the browser's own find (which cannot search the canvas) suppressed.
 * Capturing on the window also covers the case where the canvas does not have focus.
 */
@JsFun(
    """(handler) => window.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && (e.key === 'f' || e.key === 'F') && handler()) {
        e.preventDefault();
        e.stopPropagation();
      }
    }, true)"""
)
internal external fun onFindShortcut(handler: () -> Boolean)
