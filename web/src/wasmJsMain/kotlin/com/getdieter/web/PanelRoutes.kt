package com.getdieter.web

import kotlinx.coroutines.channels.Channel

/**
 * Routes that reach the page after it loaded, for [openRoute]. Home Assistant's app panel tells a subscribed
 * frame its route on every navigation, so a push opening `/<panel>/card/<id>` (or `/<panel>/next/<route>`)
 * while this page is open lands here; the panel's address then goes back to `/<panel>/next`, so a reload stays
 * on this page. An older HA automation registers the card with the bridge before its push instead
 * (`api/focus`, valid 15 minutes): that card opens once at start when no other route was asked for.
 */
internal object PanelRoutes {
    val later = Channel<String>(Channel.UNLIMITED)

    /** [initial] is `?route=`, forwarded by the classic page from the panel route the panel then reports again. */
    fun start(initial: String, focus: Boolean) {
        var repeat = initial.trim('/')
        var routed = repeat.isNotEmpty()
        followPanelRoute { route ->
            routed = true
            consumeFocus {}
            if (route == repeat) repeat = "" else later.trySend(route)
        }
        if (focus && !routed) consumeFocus { id -> if (!routed && id.isNotEmpty()) later.trySend("card/$id") }
    }
}

@JsFun(
    """(onRoute) => {
      if (window.parent === window) return;
      addEventListener('message', (e) => {
        const d = e.data;
        if (e.source !== window.parent || !d || d.type !== 'home-assistant/properties' || !d.route) return;
        const m = (d.route.path || '').match(/^(?:\/next)?\/((?:card|chat|idea)\/[\w-]+|tab\/\w+|dreamfall(?:\/[\w-]+){0,2})\/?$/);
        if (!m) return;
        onRoute(m[1]);
        window.parent.postMessage({ type: 'home-assistant/navigate', path: (d.route.prefix || '') + '/next', options: { replace: true } }, '*');
      });
      window.parent.postMessage({ type: 'home-assistant/subscribe-properties' }, '*');
    }"""
)
private external fun followPanelRoute(onRoute: (String) -> Unit)

@JsFun(
    """(onId) => fetch(new URL('../api/focus?consume=1', location.href))
      .then((r) => (r.ok ? r.json() : null))
      .then((f) => onId((f && typeof f.id === 'string' && f.id) || ''), () => onId(''))"""
)
private external fun consumeFocus(onId: (String) -> Unit)
