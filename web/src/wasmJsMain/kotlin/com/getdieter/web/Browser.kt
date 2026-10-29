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
