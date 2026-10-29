package com.getdieter.mobile.platform

import org.w3c.dom.HTMLElement

/*
 * HTML islands: real DOM placed in the Compose layout (HtmlElementView). The browser then selects,
 * finds, translates and reads the text aloud like any page. The functions live in one script that
 * installs `window.dieterIslands` with the islands' stylesheet the first time it is needed.
 */

@JsFun(
    """() => {
  if (window.dieterIslands) return;
  const res = 'composeResources/com.getdieter.mobile.resources/';
  const css = `
@font-face { font-family: 'Dieter Sora'; src: url('${'$'}{res}font/sora_variable.ttf') format('truetype'); font-weight: 100 900; font-display: swap; }
@font-face { font-family: 'Dieter Mono'; src: url('${'$'}{res}files/jetbrains_mono_regular.ttf') format('truetype'); font-display: swap; }
.dieter-island { box-sizing: border-box; width: 100%; height: 100%; overflow: auto; overscroll-behavior: contain; padding: 4px 24px 32px;
  color: var(--md-text); background: var(--md-background); font-family: Roboto, 'Segoe UI', system-ui, -apple-system, sans-serif;
  font-size: var(--md-body-size); line-height: var(--md-body-line); -webkit-user-select: text; user-select: text;
  -webkit-text-size-adjust: 100%; word-wrap: break-word; outline: none; }
.dieter-island:focus-visible { box-shadow: inset 0 0 0 2px var(--md-link); }
.dieter-island ::selection { background: var(--md-selection); }
.dieter-island > :first-child { margin-top: 12px; }
.dieter-island h1, .dieter-island h2, .dieter-island h3, .dieter-island h4 { font-family: 'Dieter Sora', Roboto, system-ui, sans-serif; font-weight: 500; line-height: 1.25; margin: 20px 0 8px; }
.dieter-island h1 { font-size: var(--md-h1); } .dieter-island h2 { font-size: var(--md-h2); } .dieter-island h3 { font-size: var(--md-h3); }
.dieter-island h4 { font-size: var(--md-body-size); font-weight: 600; } .dieter-island h5, .dieter-island h6 { font-size: .9em; margin: 14px 0 6px; }
.dieter-island h6 { color: var(--md-secondary); }
.dieter-island p { margin: 10px 0; }
.dieter-island a { color: var(--md-link); text-decoration: none; cursor: pointer; } .dieter-island a:hover { text-decoration: underline; }
.dieter-island code { font-family: 'Dieter Mono', ui-monospace, monospace; font-size: .88em; background: var(--md-fill); border-radius: 4px; padding: 0 3px; }
.dieter-island .code { margin: 10px 0; border-radius: 12px; background: var(--md-inset); border: .5px solid var(--md-separator); overflow: hidden; }
.dieter-island .code .lang { font-size: 12px; font-weight: 500; color: var(--md-secondary); padding: 8px 12px 0; }
.dieter-island pre { margin: 0; padding: 12px; overflow-x: auto; font-family: 'Dieter Mono', ui-monospace, monospace; font-size: 13px; line-height: 18px; }
.dieter-island pre code { background: none; padding: 0; font-size: inherit; }
.dieter-island blockquote { margin: 10px 0; padding: 0 0 0 14px; border-left: 3px solid var(--md-separator); color: var(--md-secondary); }
.dieter-island ul, .dieter-island ol { margin: 10px 0; padding-left: 26px; } .dieter-island li { margin: 4px 0; }
.dieter-island li > p { margin: 0; } .dieter-island li.task { list-style: none; margin-left: -22px; }
.dieter-island li.task > input { margin: 0 8px 0 0; vertical-align: -2px; } .dieter-island li.task > p:first-of-type { display: inline; }
.dieter-island hr { border: 0; border-top: 1px solid var(--md-separator); margin: 16px 0; }
.dieter-island .table { margin: 10px 0; overflow-x: auto; border: .5px solid var(--md-separator); border-radius: 12px; }
.dieter-island table { border-collapse: collapse; min-width: 100%; font-size: .94em; }
.dieter-island th { background: var(--md-fill); text-align: left; font-weight: 600; }
.dieter-island th, .dieter-island td { padding: 7px 10px; border-bottom: .5px solid var(--md-separator); vertical-align: top; }
.dieter-island tr:last-child td { border-bottom: 0; }
.dieter-island mark.dieter-find { background: var(--md-mark); color: inherit; border-radius: 2px; }
.dieter-island mark.dieter-find.current { background: var(--md-mark-current); box-shadow: 0 0 0 1px var(--md-mark-current); }
.dieter-island .diagram { margin: 10px 0; } .dieter-island .diagram .frame { width: 100%; }
.dieter-island .diagram:not(.failed) pre.source { display: none; }
.dieter-island .diagram.failed pre.source { display: block; border-radius: 12px; background: var(--md-inset); }
.dieter-island .diagram-error { font-size: 13px; color: var(--md-secondary); margin: 4px 0; }
.dieter-island .tok-comment { color: var(--tok-comment); } .dieter-island .tok-string { color: var(--tok-string); }
.dieter-island .tok-number, .dieter-island .tok-constant { color: var(--tok-number); }
.dieter-island .tok-keyword, .dieter-island .tok-tag { color: var(--tok-keyword); }
.dieter-island .tok-type, .dieter-island .tok-link { color: var(--tok-type); } .dieter-island .tok-function { color: var(--tok-function); }
.dieter-island .tok-annotation, .dieter-island .tok-attribute { color: var(--tok-annotation); }
.dieter-island .tok-property, .dieter-island .tok-variable { color: var(--tok-property); }
.dieter-island .tok-heading { color: var(--tok-keyword); font-weight: 700; } .dieter-island .tok-emphasis { font-style: italic; }
.dieter-diagram-frame { border: 0; width: 100%; height: 100%; display: block; background: transparent; }
`;
  const style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  let next = 1;
  const pending = new Map();
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || typeof d !== 'object' || typeof d.dieterDiagram !== 'number') return;
    const entry = pending.get(d.dieterDiagram);
    if (!entry || e.source !== entry.frame.contentWindow) return;
    entry.done(typeof d.result === 'string' ? d.result : '{}');
  });
  const failed = JSON.stringify(JSON.stringify({ error: 'The diagram could not render.' }));
  // A sandboxed frame (scripts only, opaque origin, no network by its CSP) running the offline
  // renderer page; it posts the JSON result of [script] back.
  function frame(template, script, done) {
    const id = next++;
    const f = document.createElement('iframe');
    f.className = 'dieter-diagram-frame';
    f.setAttribute('sandbox', 'allow-scripts');
    f.setAttribute('title', 'Diagram');
    f.setAttribute('scrolling', 'no');
    const safe = script.replace(/<\//g, '<\\/').replace(/<!--/g, '<\\!--');
    const post = (value) => `parent.postMessage({dieterDiagram:${'$'}{id},result:${'$'}{value}},'*')`;
    const run = `<script>(function(){try{Promise.resolve(${'$'}{safe}).then(function(r){${'$'}{post('r')}},function(){${'$'}{post(failed)}})}catch(e){${'$'}{post(failed)}}})()<\/script>`;
    // The last </body>: the inlined renderer has that text in its strings too.
    const end = template.lastIndexOf('</body>');
    f.srcdoc = template.slice(0, end) + run + template.slice(end);
    pending.set(id, { frame: f, done });
    f.dieterRelease = () => pending.delete(id);
    return f;
  }
  function release(el) {
    el.querySelectorAll('iframe').forEach((f) => f.dieterRelease && f.dieterRelease());
  }

  window.dieterIslands = {
    create(onLink, onScroll) {
      const el = document.createElement('div');
      el.className = 'dieter-island';
      el.setAttribute('role', 'document');
      el.tabIndex = 0;
      el.addEventListener('click', (e) => {
        const a = e.target && e.target.closest ? e.target.closest('a[data-link]') : null;
        if (a) { e.preventDefault(); onLink(a.getAttribute('data-link')); }
      });
      let scrolled = false;
      el.addEventListener('scroll', () => {
        const now = el.scrollTop > 8;
        if (now !== scrolled) { scrolled = now; onScroll(now); }
      }, { passive: true });
      // Keys stay with the document (scrolling, selection); Ctrl/⌘+F is the window's.
      el.addEventListener('keydown', (e) => { if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'f') e.stopPropagation(); });
      return el;
    },
    setHtml(el, html) { release(el); el.innerHTML = html; el.scrollTop = 0; el.dieterMarks = []; },
    setStyle(el, style) {
      for (const part of style.split(';')) {
        const at = part.indexOf(':');
        if (at > 0) el.style.setProperty(part.slice(0, at), part.slice(at + 1));
      }
    },
    // Keeps clear of what Compose draws over the island (menus, the find bar, snackbars): holes
    // in island pixels as [x, y, w, h, ...]; everything covered hides the island.
    clip(el, holes, hidden) {
      el.style.visibility = hidden ? 'hidden' : '';
      if (!holes.length) { el.style.clipPath = ''; return; }
      const w = el.clientWidth + 100, h = el.clientHeight + 100;
      let path = `polygon(evenodd, -50px -50px, ${'$'}{w}px -50px, ${'$'}{w}px ${'$'}{h}px, -50px ${'$'}{h}px, -50px -50px`;
      for (let i = 0; i + 3 < holes.length; i += 4) {
        const [x, y, hw, hh] = [holes[i], holes[i + 1], holes[i + 2], holes[i + 3]];
        path += `, ${'$'}{x}px ${'$'}{y}px, ${'$'}{x + hw}px ${'$'}{y}px, ${'$'}{x + hw}px ${'$'}{y + hh}px, ${'$'}{x}px ${'$'}{y + hh}px, ${'$'}{x}px ${'$'}{y}px, -50px -50px`;
      }
      el.style.clipPath = path + ')';
    },
    find(el, query) {
      el.querySelectorAll('mark.dieter-find').forEach((m) => m.replaceWith(document.createTextNode(m.textContent)));
      el.normalize();
      el.dieterMarks = [];
      if (!query) return 0;
      const needle = query.toLowerCase();
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
        acceptNode: (n) => n.parentElement && n.parentElement.closest('.diagram:not(.failed) pre.source')
          ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
      });
      const nodes = [];
      while (walker.nextNode()) nodes.push(walker.currentNode);
      for (const node of nodes) {
        const text = node.nodeValue;
        const lower = text.toLowerCase();
        let at = lower.indexOf(needle);
        if (at < 0) continue;
        const parts = document.createDocumentFragment();
        let from = 0;
        while (at >= 0) {
          parts.append(text.slice(from, at));
          const m = document.createElement('mark');
          m.className = 'dieter-find';
          m.textContent = text.slice(at, at + needle.length);
          parts.append(m);
          el.dieterMarks.push(m);
          from = at + needle.length;
          at = lower.indexOf(needle, from);
        }
        parts.append(text.slice(from));
        node.replaceWith(parts);
      }
      return el.dieterMarks.length;
    },
    show(el, index) {
      const marks = el.dieterMarks || [];
      marks.forEach((m, i) => m.classList.toggle('current', i === index));
      const m = marks[index];
      if (m) m.scrollIntoView({ block: 'center', inline: 'nearest' });
    },
    diagrams(el, template, dark) {
      el.querySelectorAll('.diagram:not([data-started])').forEach((d) => {
        d.setAttribute('data-started', '');
        const kind = d.getAttribute('data-kind');
        const source = d.querySelector('pre.source').textContent;
        const script = `window.dieterMarkdown.renderDiagram(${'$'}{JSON.stringify(kind)},${'$'}{JSON.stringify(source)},${'$'}{JSON.stringify(dark ? 'dark' : 'light')},'mail',true).then(r => JSON.stringify({height:r.height,error:r.error,stale:r.stale}))`;
        const box = document.createElement('div');
        box.className = 'frame';
        box.style.height = '180px';
        const fail = (message) => {
          if (d.classList.contains('rendered') || d.classList.contains('failed')) return;
          d.classList.add('failed');
          box.remove();
          const p = document.createElement('p');
          p.className = 'diagram-error';
          p.textContent = message;
          d.insertBefore(p, d.firstChild);
        };
        const f = frame(template, script, (json) => {
          let r = {};
          try { r = JSON.parse(json) || {}; } catch (e) {}
          if (r.stale) return;
          if (r.error) return fail(String(r.error));
          if (!(r.height > 0 && r.height <= 16000)) return fail('The diagram has unsupported dimensions.');
          box.style.height = Math.ceil(r.height) + 'px';
          d.classList.add('rendered');
          d.setAttribute('data-rendered', kind);
        });
        box.appendChild(f);
        d.insertBefore(box, d.firstChild);
        setTimeout(() => fail('The diagram renderer timed out.'), 15000);
      });
    },
    frame,
    release,
  };
}"""
)
internal external fun installIslands()

@JsFun("(onLink, onScroll) => window.dieterIslands.create(onLink, onScroll)")
internal external fun createIsland(onLink: (String) -> Unit, onScroll: (Boolean) -> Unit): HTMLElement

@JsFun("(el, html) => window.dieterIslands.setHtml(el, html)")
internal external fun setIslandHtml(element: HTMLElement, html: String)

@JsFun("(el, style) => window.dieterIslands.setStyle(el, style)")
internal external fun setIslandStyle(element: HTMLElement, style: String)

@JsFun("(el, holes, hidden) => window.dieterIslands.clip(el, holes.split(',').filter(Boolean).map(Number), hidden)")
internal external fun clipIsland(element: HTMLElement, holes: String, hidden: Boolean)

@JsFun("(el, query) => window.dieterIslands.find(el, query)")
internal external fun findInIsland(element: HTMLElement, query: String): Int

@JsFun("(el, index) => window.dieterIslands.show(el, index)")
internal external fun showIslandHit(element: HTMLElement, index: Int)

@JsFun("(el, template, dark) => window.dieterIslands.diagrams(el, template, dark)")
internal external fun renderIslandDiagrams(element: HTMLElement, template: String, dark: Boolean)

@JsFun("(el) => window.dieterIslands.release(el)")
internal external fun releaseIsland(element: HTMLElement)

@JsFun("(template, script, done) => window.dieterIslands.frame(template, script, done)")
internal external fun diagramFrame(template: String, script: String, done: (String) -> Unit): HTMLElement

@JsFun("(f) => { if (f.dieterRelease) f.dieterRelease() }")
internal external fun releaseDiagramFrame(frame: HTMLElement)

@JsFun("(name) => new URLSearchParams(window.location.search).get(name)")
private external fun islandQuery(name: String): String?

/** `?islands=off` keeps Compose's own rendering, to compare or to fall back. */
internal val islandsEnabled: Boolean by lazy { islandQuery("islands") != "off" }

private val scriptEnd = Regex("</(script)", RegexOption.IGNORE_CASE)

/**
 * The renderer page (MarkdownPreview) as one self-contained document for a sandboxed frame: its
 * script and stylesheet inline, its policy still without any network, `</body>` left for the
 * render call.
 */
internal fun diagramTemplate(assets: Map<String, ByteArray>, dark: Boolean): String? {
    val index = assets["index.html"] ?: return null
    val js = assets["app.js"]?.decodeToString() ?: return null
    val css = assets["app.css"]?.decodeToString().orEmpty()
    val policy =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; " +
            "img-src data:; font-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; " +
            "worker-src 'none'; base-uri 'none'; form-action 'none'"
    return diagramPage(index, dark)
        .decodeToString()
        .replace(Regex("""<meta\s+http-equiv="Content-Security-Policy"[^>]*>""")) {
            """<meta http-equiv="Content-Security-Policy" content="$policy" />"""
        }
        .replace("""<link rel="stylesheet" href="app.css" />""", "<style>${css.replace("</style", "<\\/style")}</style>")
        // Deferred in the page: inline, it runs at the end of the body, once #preview exists.
        .replace("""<script defer src="app.js"></script>""", "")
        .let { page ->
            val end = page.lastIndexOf("</body>")
            val script =
                "<script>${js.replace(scriptEnd) { "<\\/" + it.groupValues[1] }.replace("<!--", "<\\!--")}</script>"
            if (end < 0) page + script else page.substring(0, end) + script + page.substring(end)
        }
}
