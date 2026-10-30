# Compose web UI for the Dieter add-on: plan

Status (2026-10-30): **Phases 1–4 done; the switch is done in add-on 0.6.431.9**: one repository
(`JustinNeumann/dieter-addon`, this one), the Compose page is the add-on's only page and the classic page is removed.
Next: the bridge's remaining duties move into the web host one area at a time, each its own release, until no Python
is left (see [Step 2](#step-2-the-bridge-into-the-web-host)). See [Progress](#progress) for what is done, where it
deviates from this plan and what is open.

The work runs in the order you set:

1. [Remote access](#phase-1--remote-access-through-ingress): works over HTTPS / Nabu Casa / Companion app
2. [Canvas drawing](#phase-2--canvas-drawing-selection-find-accessibility): selection, find, accessibility
3. [Theme](#phase-3--theme-from-home-assistant): follows Home Assistant, not just the device
4. [Feature parity](#phase-4--parity-with-dieter-addon-piece-by-piece) with today's `dieter-addon` page, piece by piece

Phases 1–3 can be done on demo data. Phase 4 starts with the real data path (4.0) and then moves the add-on's
features over one group at a time.

---

## Progress

| Part | State | Where |
|---|---|---|
| Phase 1: bundle served at `next/` behind Ingress | live in add-on 0.6.431.2; checked through HA's frontend over LAN (first frame 1.3 s) and Nabu Casa (4.6 s); Companion app still to check by a person | add-on `dieter_bridge.py` (`serve_web`), `web/`, `tools/package.sh`, release v0.1.0 |
| Phase 2: selection, find, accessibility | released in v0.3.0: selection areas, Ctrl/⌘+F find, semantics fixes, HTML islands for mail and Markdown files, offline diagrams; Playwright suite + CI job; screen-reader runs by a person still open. Known limit: Compose 1.12 keeps a closed popup's nodes in the accessibility tree (v0.4.4 built with 1.13.0-beta01, whose text input loses keys; v0.4.5 is back on 1.12, see the open issues) | `tests/`, `web/…/platform/Islands.kt`, `MarkdownHtml.kt`, submodule branch `web-phase2` (`design/Find.kt`) |
| Phase 3: theme from HA | live; the real HA frontend hands light and dark with its colours to the page | `index.html` (`dieterHost`), `WebHost.kt`, `Platform.wasmJs.kt` |
| 4.0 web host + `RemoteCore` | built; verified against Dieter's isolated fixture gateway (real boards, conversations, sending runs a turn) and in the add-on e2e | `host/`, `proto/dieter/web/v1/host.proto`, `web/…/RemoteCore.kt`, add-on `run.sh` + bridge relay |
| 4.0 sign-in | live: signed in with GitHub and paired as "Home Assistant (web)" on 2026-10-29. Since ADR 0005 the core chooses the return address per attempt (`BeginSignIn.redirect_uri`, `RuntimeConfig.oauthRedirectPerAttempt`); the "paste the callback URL" fallback is gone | `SignInRelay.kt`, fork `identity/SignIn.kt`, add-on DOCS "Signing in the new page" |
| 4.A deep links | `<HA>/app/<slug>/next/card/<id>` and `…/next/tab/<name>` open the card or tab, `…/next/dreamfall/<id>[/settings]` and `…/next/idea/<id>` a Dreamfall board or idea; a card link asks the core (`OpenConversationRequest` → `NotificationTarget`), unknown or archived cards open the Inbox | `Main.kt` (`openRoute`), fork `client/MailCommands.kt`, add-on `app.js` |
| Extension points + add-on core ([ADR 0005](https://github.com/JustinNeumann/dieter/blob/web-addon/docs/adr/0005-web-client.md)) | built: `MobileExtensions` in the fork; the add-on core runs in the web host (`dieter.addon.v1`, rules in `AddonRules`); screens for gateway enrollment, SSH key, Claude sign-in, title model and work-folder retention, agent questions (Inbox + conversation), Clone repository / New empty repository; add-on e2e `next-addon.spec.mjs` | `addon/`, `proto/dieter/addon/v1/addon.proto`, `web/…/addon/`, fork `apps/kotlin/ui/…/extensions/` |
| I. Dreamfall | built: Tools → Dreamfall lists the boards, a stage board opened from Projects shows its Dreamfall board (`BoardRenderer`), funnel + stages side by side (tabs on phones), idea page with brief, provenance and trail, Pick/Park/Promote/Release/Back to the queue/Start/Review, the ⋮ menu, the settings form (new board from Tools or Projects → Add). The rules are the add-on core's (`Dreamfall.kt`: lanes, verdicts, run lines, times in the board's time zone); add-on e2e `next-dreamfall.spec.mjs` | `addon/…/core/Dreamfall.kt`, `web/…/addon/Dreamfall*.kt`, add-on bridge `?fields=dreamfall_cards` |
| 4.A–J parity rest, switch to `/` | open; Mail, Tools, Inbox, boards and conversations already show real data from the fixture gateway | |
| Upstream proposals | drafted, not posted; per-attempt redirect, extension points (used by questions and Dreamfall) and deep links through the core are implemented in the fork | `docs/proposals/web-client.md`, `docs/proposals/dreamfall.md` |

**Deviations from the plan, and why:**

- The bridge keeps serving the bundle (Phase 1) and *relays* `next/core` (WebSocket) and `next/signin` to the web
  host on loopback. Only one Ingress port exists, the bridge already owns it, and its caching/precompression
  work is done. The host answers only requests carrying a per-boot secret that `run.sh` hands to the bridge on an
  inherited descriptor; the host runs as its own user (`dieter-web`) with `/data/web-host` private, so the
  agents (user `dieter`) can neither read the session nor talk to the host.
- D5: the bundle and the host jar are built from *this* repository by the add-on's Builder (`builder.yaml`, job
  `page`: `tools/addon-dist.sh` hands both to the image build in `dieter/web-dist`), from the same commit as the
  image. Until add-on 0.6.431.8 they were releases (`vX.Y.Z`, `SHA256SUMS`) of the separate repository
  dieter-addon-kotlin, pinned by sha256 in the Dockerfile.
- Sign-in: the gateway only redirects to exact registered addresses, and how you reach HA varies (Nabu Casa,
  LAN), so each sign-in starts with the address the page is on: `<HA>/app/<slug>/next/signin`. HA opens the
  add-on at its root for any `/app/<slug>/…` path, so the add-on's root page forwards `next/…` deep links (which also
  makes `<HA>/app/<slug>/next/` a direct link to the new page). The host completes the code under its fixed
  internal callback (the core checks only the prefix and exchanges code + PKCE verifier). Pasting the landed
  address into the gateway field also completes. The session belongs to the add-on, so every browser and the
  Companion app share one sign-in and one pairing.
- Foreground: the core keeps its streams open only while "the app" is shown. Each tab reports its visibility;
  the host runs the core while any tab is shown and stops two minutes after the last one hides.
- RAM: about 100 MB RSS idle and about 200 MB with the showcase workspace on x86 (JVM flags in `run.sh`
  cap heap 160 MB, metaspace 96 MB, code cache 48 MB). The Pi has 8 GB and the add-on uses about 1 GB today;
  measure the host there after deploy.

---

## Where things stand (spike)

| | |
|---|---|
| What renders | The real Compose screens from `apps/kotlin/ui` (Inbox, Projects, boards, conversations, Chats, Mail, Tools) at phone, tablet and desktop widths, dark and light |
| How | Standalone Gradle build (`~/dieter/spike/web`) that compiles `core/runtime` + `ui` common sources and the Wire models for `wasmJs`. Additions: 3 runtime actuals (`CoreLock`, `secureRandomBytes`, `localUtcOffsetSeconds`), copies of the JVM stubs for the UI's platform hooks, `DemoCore` (a `MobileCore` answering slices from demo data) |
| One patch | `MobileTheme.kt` is filtered at build time so the Material type scale uses `platformMonospace()` (the browser has no system monospace font; the web actual bundles JetBrains Mono). Upstream this is a two-line change |
| Size | ≈ 15 MB optimized (Skia wasm 8.4 MB, app wasm 6.2 MB, JS 0.5 MB), ≈ 5 MB gzip |
| Build times on your PC | First build 3 min, incremental dev build ~12 s, production build ~100 s |
| Not real yet | Data (demo only); terminal and screen sharing are no-ops (back, attachment picker, attachment viewer/download and share: done since 0.4.0) |

### Target architecture (decided in the last discussion)

```
Browser (HA Ingress iframe, same origin as HA)       Dieter add-on container
┌───────────────────────────────┐                   ┌───────────────────────────────────────────┐
│ Compose UI (wasm)             │                   │ bridge (Python, as today)                 │
│ RemoteMobileCore              │──── Ingress ─────►│  /           old JS page (until switch)   │
│   dispatch(Command) ─ bytes ─►│                   │  /next/      → web host                   │
│   observe(Slice)   ◄─ bytes ──│                   │  /api/ha, rest_commands, events, push,    │
└───────────────────────────────┘                   │  questions relay, uploads, housekeeping   │
                                                    │                                           │
                                                    │ web host (JVM, new)                       │
                                                    │  serves the wasm bundle                   │
                                                    │  WebSocket: Command/Update protobufs      │
                                                    │  runs the real Kotlin core (ClientApi):   │──► daemon (loopback route)
                                                    │  OkHttp transport, device key in /data    │──► gateway → other machines
                                                    │                                           │
                                                    │ Go daemon + Node harnesses (unchanged)    │
                                                    └───────────────────────────────────────────┘
```

The UI only talks through `MobileCore` (`dispatch(Command)`, `observe(Slice, scope)`), and both carry Wire
protobuf messages, the same contract the iOS app uses through its byte bridge. So the browser stays thin and
the core runs on the JVM exactly as on Android.

---

## Decisions to take before Phase 4 (Phases 1–3 don't depend on them)

| # | Decision | Recommendation | Why it matters |
|---|---|---|---|
| D1 | Data path | Web host (JVM) runs the Kotlin core; the browser gets `RemoteMobileCore` over a WebSocket | One trusted device, every machine visible, no mutual-TLS gRPC from a browser. The alternative (core in the browser) makes every browser a paired device and needs a new web transport in upstream's core |
| D2 | Account requirement | The Compose UI needs a gateway account: the core's session is a gateway session, and the add-on's daemon must be **enrolled** (Tools → Machines → Gateway → Enroll exists today). The web host is approved as an app device once (pairing code) | Today's page also works *without* enrollment (it drives the local daemon through the CLI). Gateway-less installs keep the old page until there is an answer (see Open questions) |
| D3 | Where add-on-only features live | Your fork's `apps/kotlin/ui` first, behind small extension points (extra Tools pages, Settings rows, board types, conversation widgets). Propose those points upstream (`docs/proposals`) | Dreamfall, agent questions, Claude sign-in etc. don't exist in upstream's apps. Without extension points every add-on feature becomes a fork patch |
| D4 | Rollout | Side by side: old page at `/`, Compose at `/next/` (and a toggle in both). Switch the default when the parity table is green; remove the JS page last | Nothing breaks while parity grows; the HA package (sensors, push) keeps working throughout |
| D5 | Where it is built | Fork CI ("Fork server release") also publishes `dieter-web-<version>.tar.gz` (bundle) and `dieter-web-host-<version>.jar`, checksummed; the add-on's Dockerfile pins and verifies them like the daemon | Building Gradle on a Pi is out; the add-on image stays a pure download of pinned artifacts |

---

## Phase 1 · Remote access through Ingress

**Problem.** The spike is served from your PC over `http://`. A page opened over `https://` (Nabu Casa) may not
embed `http://` content (mixed content), and it only works on your LAN while `watch.sh` runs.

**Fix.** Serve the bundle from the add-on behind Ingress, like the current page. Ingress pages are served by
HA itself under `/api/hassio_ingress/<token>/`, so they are **same origin** as HA: HTTPS via Nabu Casa, HA's
login and the Companion app all work without anything extra. This is also the precondition for Phase 3
(reading HA's theme needs same origin).

### Steps

1. **Serve the demo bundle from the bridge at `next/`.** Static files only, no new process yet: copy the
   production dist into the image (`/opt/web`) and have `dieter_bridge.py` serve `GET /next/...` from there.
   - Keep the trailing-slash guard the current page has (`/api/hassio_ingress/<token>/next` → `.../next/`),
     because all asset URLs must stay **relative**. Check that the wasm loader and `composeResources/` resolve
     relative to the page (they do on the LAN spike; verify under the Ingress prefix).
   - Headers: hashed `*.wasm` / `*.js` → `Cache-Control: public, max-age=31536000, immutable`;
     `index.html` → `no-cache`; `.wasm` as `application/wasm` (streaming compilation).
   - Ship pre-compressed `.br` / `.gz` next to the files and serve them by `Accept-Encoding` (cuts the first
     load over Nabu Casa from ~15 MB to ~5 MB).
2. **Build it in CI (D5).** Until the fork publishes the bundle, the add-on's `builder.yaml` can check out
   the fork at the bundled tag and run the spike build. Later replace that with the pinned release asset.
3. **Loader page.** Background from the theme (no white flash; Phase 3 refines it), "Loading Dieter…",
   and a clear message when the browser lacks WebAssembly GC.
4. **Browser coverage.** Kotlin/Wasm needs WebAssembly GC: current Chrome/Edge/Firefox, Android System WebView
   (Companion app on Android), Safari / iOS WebView **from 18.2** (Companion app on iPhone and iPad follows
   the iOS version). Decide whether older iOS matters: Compose's *compatibility mode* builds a JS fallback
   next to the wasm build, but it needs an extra `js()` target (all dependencies support it; doubles build
   time and output).
5. **WebSocket over Ingress.** Not needed for demo data, but test it now with a trivial echo endpoint so
   Phase 4 has no surprise: `ingress_stream: true` is already set in `config.yaml`.
6. **Remove the temporary setup.** `cat ~/dieter/spike/web/ha/dashboard.py | ssh hassio@192.168.0.10 'python3 - remove'`,
   stop `watch.sh`.

### Verify

- [ ] LAN `http://homeassistant.local:8123` → sidebar Dieter → `/next/` loads
- [ ] Nabu Casa `https://…ui.nabu.casa` → same
- [ ] Companion app Android and iOS (phone + tablet), incl. a cold start on mobile data
- [ ] First-load and cached-load times recorded (target: < 3 s cached on a phone)
- [x] Playwright: port `page loads through Ingress with relative assets` to `/next/` (the e2e suite's
      Ingress-like proxy already exists: `tests/ingress-proxy.mjs`) → add-on `tests/e2e/next.spec.mjs`

**Done when** `/next/` works everywhere the current page works.

---

## Phase 2 · Canvas drawing: selection, find, accessibility

Compose for Web draws everything into one `<canvas>`. What that changes compared to today's DOM page:

| Browser feature | Today's JS page | Compose on canvas |
|---|---|---|
| Select and copy text | everywhere | only inside `SelectionContainer` (already used in the conversation timeline) |
| Find in page (Ctrl/⌘+F) | yes | no: the browser cannot search a canvas |
| Screen readers, keyboard navigation | DOM semantics | Compose semantics mapped to the browser (Compose for Web Beta lists "fundamental accessibility support"); depth must be measured |
| Browser translation, spellcheck, autofill | yes | no (text fields get IME support from Compose) |
| Links: hover URL, middle click, "copy link" | yes | only what Compose implements |
| Page zoom | yes | yes (canvas rescales) |

### Steps

1. **Measure first (half a day).** Same demo build, a checklist per screen (Inbox, board, conversation, Mail
   message, Files, New task):
   - screen readers: Orca (Linux desktop), VoiceOver (iPad/Mac), TalkBack in the Companion app;
   - keyboard only: Tab order, Enter/Space, Escape closes sheets;
   - selection: drag-select in replies, mail and files; copy; long press on touch.
   Write the results into this file. The UI already sets a lot of semantics (`contentDescription`,
   `testTag`), so expect a usable baseline.
2. **Use what Compose already offers.**
   - `SelectionContainer` around mail bodies, file viewer text, tool output, subagent details (the
     conversation has it).
   - **In-app find**: intercept Ctrl/⌘+F at the window level and open a find bar for the current screen
     (conversation, mail message, file). It searches the slice data, not the canvas, and scrolls to the
     hits.
   - Fix whatever semantics gaps step 1 finds, in the shared `:ui`, so Android/iOS benefit too.
3. **HTML islands for read-heavy content** (`WebElementView`: real DOM elements placed inside the Compose
   layout). Candidates, in order of value:
   - **Markdown documents**: the Mail message body and the file viewer as real HTML. That gives native
     selection, Ctrl+F, translation and screen-reader support where people read the most. Use the same
     Markdown subset as today.
   - **Diagrams**: implement `NativeMarkdownDiagram` (Android/iOS run the bundled offline renderer from
     `apps/apple/mac/MarkdownPreview` in a WebView) by running that same bundle in an HTML island. Mail's
     vega-lite / vega / mermaid charts then work offline, unlike today's jsDelivr loading.
   - **Terminal**: `NativeTerminal` as an xterm.js island (later, see Phase 4 H).
   - Risks: islands sit above the canvas, so scrolling inside lazy lists and overlapping sheets/menus need
     care. Start with full-screen readers (mail message, file viewer), not the timeline.
4. **Decision gate.** If step 1 shows accessibility that is not good enough for your use, the fallback is a
   DOM UI (Compose HTML) that reuses the store and models but not the composables. That is a rewrite of
   every screen, so only go there for a hard requirement.

**Done when** the measured checklist passes for the screens above and Ctrl/⌘+F works in conversation, mail and
files.

### Results (measured 2026-10-29, demo bundle, Chromium 1200×800)

Measured by `tests/measure.spec.ts` (`npm run measure`, Playwright 1.59.1) against the packaged demo
bundle: the accessibility tree Compose for Web mirrors into `#cmp_a11y_root`, its ARIA snapshot, keyboard
only (Tab ×1–8 then Enter, Space, Escape) and drag-select plus Ctrl+C. "Before" is the build without the
Phase 2 changes, "after" is branch `phase2`. Escape on menus is judged by the pixels under the menu button:
the accessibility tree is not reliable for that (see the open issues).

**Before**

| Screen | Exposed (nodes / buttons / unnamed) | Odd accessible names | Tab → Enter activates | Space | Escape closes | Drag-select + copy |
|---|---|---|---|---|---|---|
| Inbox | 32 / 18 / 0 | `Chat` (icon named instead of the row), `D Compose web UI…` (avatar initial first) | after 1–8 Tabs | no | menu: yes¹ | n/a |
| Board | 44 / 29 / 0 | cards named `Last activity 1min` (text of merged children dropped) | after 2–8 | yes | menu: yes¹ | n/a |
| Conversation | 48 / 27 / 0 | `Done, Done, Open, Open` (plan icons) | after 2–8 | yes | menu: yes¹ | ❌ nothing copied from a reply |
| Mail message | 32 / 15 / 0 | none | after 2–8 | yes | menu: yes¹ | 🟡 one block at a time, no selection across blocks |
| Files | 36 / 19 / 0 | as Inbox (list pane) | after 2–8 | yes | menu: yes¹ | 🟡 one block at a time |
| New task | 50 / 28 / 0 | as Inbox | ❌ never | n/a | ❌ no (back handler was a no-op on the web) | n/a |

¹ `DropdownMenu` closes on Escape in Compose for Web itself; the first run judged this from the stale tree
and recorded "no", the after run measured it properly.

**After**

| Screen | Exposed (nodes / buttons / unnamed) | Odd accessible names | Tab → Enter activates | Space | Escape closes | Drag-select + copy |
|---|---|---|---|---|---|---|
| Inbox | 36 / 23 / 0 | ✅ none | ✅ after 1–8 | ✅ yes | ✅ menu | n/a |
| Board | 44 / 29 / 0 | ✅ cards named by title (`UI Compose web UI…` is the card's label "UI", not an avatar) | ✅ after 2–8 | ✅ yes | ✅ menu | n/a |
| Conversation | 49 / 29 / 0 | ✅ none; plan rows carry their state | ✅ after 2–8 | ✅ yes | ✅ menu, find bar | ✅ replies, tool output, subagent details |
| Mail message | 65 / 18 / 0 (body is real DOM) | ✅ none | ✅ after 2–8 | ✅ yes | ✅ menu, find bar | ✅ native selection in the HTML island |
| Files | 52 / 21 / 0 | ✅ none | ✅ after 2–8 | ✅ yes | ✅ menu, find bar | ✅ Markdown island native; text files in the editor |
| New task | 50 / 30 / 0 | ✅ none | ❌ focus stays in the text field | n/a | 🟡 yes once the canvas has focus, not from a text field | n/a |

Ctrl/⌘+F opens the app's find bar in the conversation, a mail message and a file (n/m, Enter / Shift+Enter
and the arrow keys step and scroll, Escape closes the bar only); elsewhere it stays the browser's.
Tests for all of this: `tests/{selection,find,islands,keyboard}.spec.ts`, run by CI against the packaged
bundle.

**Open issues**

- **Compose for Web keeps a closed popup in the accessibility tree.** Compose 1.12's
  `ComposeWebSemanticsListener` keeps only the last added `SemanticsOwner`, so after a menu, dialog or sheet
  closed `#cmp_a11y_root` holds the popup's nodes until the page reloads. Compose 1.13 keeps every open
  layer's owner, and v0.4.4 built the page with 1.13.0-beta01 (`composeWebVersion` in `gradle.properties`).
  That beta's text input is new, though: a `contenteditable` instead of a `textarea`, with its own offset
  arithmetic. With a keyboard, a field that changed (an error appeared) and is tapped again loses the keys
  typed next (the backing input goes away while the field keeps Compose's focus), and fast typing can arrive
  out of order ("https//127.0.0.1:19:"). A probe on the add-on's Gateway address field lost the second entry
  in 3 of 5 tries at 120 ms between keys; 1.12 typed all 20. A paste (one input event) is fine. Not reported
  upstream yet. v0.4.5 builds with the fork's 1.12 again; set `composeWebVersion` once a 1.13 build types
  reliably. While the page is on 1.12, `tests/popups.spec.ts` is expected to fail and `tests/archive.spec.ts`
  (it reads the tree after a dialog) is skipped.
- **DOM focus does not follow Compose focus** (except into text fields). Tab moves Compose's focus, but a
  screen reader's focus and the browser's focus ring stay on the canvas.
- **Escape inside a text field** goes to the field's input element, not to the back handlers, so New task
  closes on Escape only after clicking outside the field.
- **Not measured, needs a person:** Orca (Linux desktop), VoiceOver (iPad/Mac) and TalkBack in the Companion
  app; long press to select on touch screens. The tree above is what they get; whether it is usable is
  still to be judged by ear.
- HTML islands sit above the canvas. Menus, dialogs, sheets, snackbars and the find bar report their bounds
  (`FloatingLayers` in `:ui`) and the island cuts them out (`clip-path`) or hides behind full-window layers.
  A new kind of overlay must call `Modifier.floating()` or `CoversWindow()`.
- The offline diagram renderer adds about 6 MB (uncompressed) to the bundle; it loads only when a diagram
  is shown.

---

## Phase 3 · Theme from Home Assistant

Today the spike (and the current page) follow `prefers-color-scheme` of the device. HA can be in dark
mode on a light device, use a custom theme, or switch per user.

### Steps

1. **Read HA's theme.** Behind Ingress the page is same origin with HA (Phase 1), so the page can read the
   parent frame:
   - `window.parent.document.querySelector("home-assistant").hass`: `themes.darkMode`, `selectedTheme`
     (empty for *Auto*), theme name;
   - CSS custom properties HA applies to the parent's document: `--primary-color`,
     `--accent-color`, `--primary-background-color`, `--card-background-color`, `--primary-text-color`;
   - first check what HA's app-panel message `home-assistant/properties` contains: the current page already
     subscribes to it for deep links. If it carries theme data, prefer it over reading the parent DOM.
   - Fallbacks: `?theme=dark|light` (like today's page), then `prefers-color-scheme`.
2. **Follow changes.** A `MutationObserver` on the parent's `<html>` style and class, plus `matchMedia`, plus a
   re-read on `visibilitychange` (the Companion app). Debounce, then push the new values into the store.
3. **Map into Dieter's theming. Only web actuals change; nothing in upstream's screens.**
   - Light/dark → `ThemeModel.setAppearance("dark" | "light")`.
   - Colors: implement `platformDynamicScheme(dark)` on web with MaterialKolor
     (`dynamicColorScheme(seedColor = HA --primary-color, isDark = …)`, supports wasm) and set
     `platformSupportsDynamicColor = true`. The existing Settings switch "Dynamic color" then means
     *follow Home Assistant's theme*, the way it means Material You on Android. With the switch off, the
     user's Dieter palette applies.
   - Optional: take the background and surface colors from HA's variables, so the panel blends with HA's
     sidebar instead of matching only in hue. Decide by looking at both.
   - Keep Sora as the display font (Dieter's look); don't follow HA's font.
4. **Loader and browser chrome.** The loader background and `<meta name="theme-color">` come from the same
   values, so there's no flash before Compose starts.

### Verify

- [ ] HA profile Light / Dark / Auto on a dark and a light device: the panel follows within a second
- [ ] A custom HA theme with a different primary color → Dieter's accent follows when Dynamic color is on
- [ ] Companion app: same, plus after switching the phone's dark mode with the app in the background

**Done when** changing the theme in HA's profile changes the panel without reload.

---

## Phase 4 · Parity with dieter-addon, piece by piece

### 4.0 Foundation: the real data path (blocks everything below)

1. **Web host (JVM)**: a new process in the add-on (decision D1):
   - Serves the bundle (taking over from the bridge's static route of Phase 1) and one WebSocket, `core`.
   - Runs `ClientApi` with the JVM `Platform`: OkHttp transport, loopback routes to the add-on's own daemon
     (the core already supports them on JVM), device key + secure store + settings in `/data/web-host/`.
   - Protocol: `dispatch` = request/response with an id; `observe` = subscribe/unsubscribe per (slice, scope),
     Update messages pushed as they come. All payloads are the Wire-encoded `Command` / `Result` / `Update`.
   - Accept connections only from the Supervisor network, like the bridge; take the HA user name from the
     Ingress headers for anything that records who did what (Dreamfall trail).
2. **`RemoteMobileCore` in the browser**: replaces `DemoCore`. Reconnects with backoff and re-subscribes;
   the UI's existing offline/syncing states show connection loss.
3. **Sign-in and pairing (decision D2).** The core starts in `PHASE_AUTH_REQUIRED`; the UI's sign-in and
   pairing screens already exist. Open point: the sign-in callback. The apps receive it through their URL
   scheme (`CompleteSignIn(callback_url)`); the web host needs either a redirect back into the Ingress page
   or a "paste the callback URL" step. Check what the gateway allows before building. Then approve the web
   host's pairing code once (Tools → Devices on the old page, or an app).
4. **Image and resources**: JRE in the image (+≈ 50–70 MB). Measure RAM on the Pi with Node harnesses
   running (estimate 150–250 MB for the host). A GraalVM native-image build is the follow-up if that's too much.
5. **Bridge stays** for everything that is not UI: HA sensors and rest_commands, events and push, harness
   staging, daemon supervision, Claude credential renewal, the agent-questions relay, uploads, work-folder
   housekeeping. The web host never duplicates these; where the UI needs them, it calls the bridge (see
   *Add-on extensions* below).
6. **Tests**: JVM UI tests in the fork for new screens (`ui/src/jvmTest` exists); Playwright smoke tests per
   group against `/next/` with the mock harness (`tests/run.sh` already starts it), selecting by
   accessibility names/test tags; screenshot comparisons for the look.

**Done when** `/next/` shows your real boards, cards and conversations, and sending a message runs a turn.

### Add-on extensions (decision D3)

Features that only the add-on has need two things: a Compose screen in the fork's `:ui` (or a small add-on
UI module) and a backend. The backend is the bridge's existing endpoint, reached through the web host. A
narrow extension API keeps this out of upstream's screens:

- `extraToolPages` (Tools tab), `extraSettingsSections`, `boardRenderers` (Dreamfall as a board type),
  `conversationWidgets` (agent questions), and an `AddonApi` client for the bridge endpoints.
- Propose the extension points upstream once two features use them (questions + Dreamfall).

Built as [ADR 0005](https://github.com/JustinNeumann/dieter/blob/web-addon/docs/adr/0005-web-client.md) decided: the
fork's `MobileExtensions` (pages, tool-page sections, Inbox sections, conversation widgets, Projects actions, a board
renderer) and, instead of an `AddonApi` client in the UI, an **add-on core** in the web host (`addon/`). The screens
keep drafts and draw slices; the core owns the bridge calls, the enrollment and sign-in processes, the messages and
the rules (`AddonRules`, also compiled into the page for button readiness).

### Parity table

Legend: **Compose** = what the shared UI already has. ✅ exists in the apps (needs only real data and,
where noted, a web actual) · 🟡 partly / verify · ❌ add-on only (new screen + bridge endpoint).
**Test** = today's Playwright test that must pass against `/next/` (adapted).

#### A. Shell and navigation (do first)

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Six tabs, rail from 600 px, two panes from 840 px | ✅ | none | |
| Most recently used project first, preselected in New task, opened by the Projects tab | ✅ | opening a project, board, Dreamfall board or conversation tells the core (`remember_creation`), whose creation memory New task already preselects from; Projects lists it under "Last used" (unless pinned) and choosing the Projects tab from another tab opens it. Kept by the add-on's one core, so shared by every browser (the classic page keeps it per browser) | `projects.spec.ts`, `MobileStoreTest.theProjectUsedLast…` (fork) |
| Push deep link `/<panel>/card/<id>` through HA's app-panel messages (`home-assistant/subscribe-properties`, `home-assistant/navigate`) | ✅ | `PanelRoutes`: card, tab, Dreamfall and idea routes that arrive while the page is open go through `openRoute`; the panel's address then goes back to `/<panel>/next` | `a push to /<panel>/card/<id> opens the card in the open page…`; `opened at /<panel>/next/dreamfall/<id>…` |
| `?card=`, `?tab=`, legacy `api/focus` | ✅ `?route=card/<id>`, `?route=tab/<name>`, `<HA>/app/<slug>/next/<route>`; the page takes `api/focus` at start when no route was asked for | same entry point | `?route=card/<id> opens that conversation…`; add-on e2e `Push: the new page takes over the card…` |
| Browser/Android back | ✅ | one history entry of the page's own while something can go back; `popstate` is a back event for Compose's handlers (`BrowserBack.kt`, a `NavigationEventInput`) | `the browser's Back closes New task, then leaves a board` |

#### B. Inbox

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Sections (needs you, running, review/recent), filters, search, pins | ✅ | none | `the Inbox lists the finished task and opens it` |
| "Waiting for your answer" (agent questions) | ✅ add-on core (`questions` slice), Inbox section | see F: questions | part of `a question from the agent…` |

#### C. Projects and boards

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Project and board lists, project page, ⋮ menus | ✅ | none | |
| **Add repository** (Git URL, name, base branch → clone into `/data/projects`) | ✅ add-on core (`AddRepository`), Projects → Add → Clone repository | Compose has project creation (`AdminCreateProject`); cloning is the bridge's. Extension action calling the bridge | `a repository can be added… (chunked POST through Ingress)` |
| **New folder** (empty Git repo, registered) | ✅ add-on core, Projects → Add → New empty repository | same | `a new folder becomes a project…` |
| **New board** (Review lane switch, Publishing mode) | ✅ | the shared New Board sheet has the Review lane switch and a Publish picker (Manual, Push base, Pull request; `publish_mode` on `AdminCreateBoard`), the same modes as the board settings | `a new board is added next to Main…` |
| Board order by drag (kept for all devices) | ✅ | the bridge keeps one order for both pages (`board_order`): the add-on core reads it into `SettingsSlice.board_order` and `OrderBoards` saves a drop; the fork's `MobileExtensions.boardOrder` sorts a project's boards by it in Projects and the project screen, and a board row is dragged after a long press (as cards are) | `Projects: a board dragged after a long press keeps its place…` (add-on e2e) |
| Dreamfall board as one row in Projects | ✅ | as on the classic page: a Dreamfall board's stage boards are one purple row (waterfall glyph, its ideas, a badge for ideas waiting for the person; "Dreamfall · N ideas · M waiting for you" on the project's page) in the place of its first stage board, dragged as one; the fork's `MobileExtensions.boardGroups`, filled from `DreamfallSummary` (new `ideas`) | `Projects lists the Dreamfall board as one row…` (add-on e2e) |
| Archive board (new, not on the classic page) | ✅ | Dieter's retirement (`SetBoardRetired`): the board menu's "Archive board…" retires a board without open cards, its project's page lists it under "Archived boards", and the board offers Restore. Archived cards stay with the retired board: upstream's daemon keeps any board an archived card points at (and Dieter can neither delete cards nor move them to another board), the fork's daemon since v0.6.431-antigravity.7 does not (`board_lifecycle.go`; an unarchived card brings the board back), and since antigravity.8 the archived cards and their conversations stay readable (`ResolveArchiveBoard`, `ReadCardDetail`; turns on them stay refused). Open cards and schedules still keep a board; the core says so in the person's words (`BoardRetirement.refusal`) | `tests/archive.spec.ts`, `aBoardTheDaemonKeepsSaysWhy`, fork `TestBoardRetiredWithArchivedCardsOnEveryPeer` |
| Publishing per board (Manual / Pull request / Push to base) | ✅ | verify | |

#### D. Board

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Lanes, newest/oldest sort, labels, filters | ✅ | none | |
| Card actions: start, move, finish, cancel, archive, edit title/task | ✅ | none | `Edit… changes the title and task of a Todo card…` |
| Drag cards between lanes (mouse, touch) | ✅ | check touch in Companion app | |

#### E. New task / New chat

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Project, board, machine, agent, model, effort pickers | ✅ | none | `project, board, agent, model and effort pickers open and apply`, `pickers work with touch` |
| New worktree vs project directory, branch fields | ✅ | none | `workspace segment…` |
| Publishing override, Add to Todo vs start | ✅ | verify | `Add to Todo creates an unstarted card…` |
| Vault / mail access switches | ✅ | verify | |
| Attachments (≤ 4 × 5 MB, 6 MB total: the apps' `Attachments` limits) | ✅ | `rememberAttachmentPicker` web actual: `<input type=file multiple>` → `Attachments.part`, the apps' limit messages | `the Attach button does not break the form`; `Attach in New task takes files from the browser's file dialog`; `a file over the limit is refused with the apps' message` |
| Draft survives live updates, Escape closes, X cancels | ✅ | verify on web | `typing in the form survives live updates…`, `Escape closes the form`, … |
| **Title model** (OpenAI vs Ollama via HA AI Task) | ✅ | with Ollama chosen, a task or chat sent without a title to the add-on's own machine is created under Dieter's placeholder without the auto-title and handed to the bridge (`POST api/titles`), which renames it as for the classic page. The fork's `ConversationNamer` (in `CreationModel.submit`) is the hook; the add-on core says which machine (`SettingsSlice.titles_daemon_id`). Other machines keep Dieter's auto-title (the bridge can only rename on its own daemon) | `MobileStoreTest.aHostThatNamesTasks…` (fork), `AddonCoreTest.whileOllamaNamesTasks…`, `titles_test.py` |

#### F. Conversation

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Streaming timeline, tool rows, reasoning toggle, context use | ✅ | none | `a task runs on the mock harness and its conversation streams` |
| Send, queue, steer, cancel; model/effort pickers | ✅ | none | `typing in the chat field keeps the focus…`, `model and effort pickers in the conversation work` |
| Changes / diff, Files pane, subagents, processes | ✅ | none | |
| Attachments in messages, image previews | ✅ | picker ✅; an attached image shows as the picture in the chat (Compose's `decodeToImageBitmap` of the part's data or base64 data URL, the core's `Attachments.bytes`), other files and images it cannot draw (SVG) as file tiles; a tap opens the viewer (row below), other types open in a new tab or download (Blob URL) | `an attached image and a tool's screenshot show as pictures…` (`tests/images.spec.ts`) |
| **Images from the agent**: tool-result images; `![](path)` from the checkout | ✅ tool results / `![](path)` stays a link (decided) | the core's `ToolImages` flags a tool step whose output preview holds an image (the bridge's rule) and finds the images in its output (Claude Code's Read, MCP screenshots, data URLs; at most 4); the timeline loads such a step's output and shows its images under the row; the tool sheet shows them above the output, whose base64 becomes "‹image/png · 12 KB›" | `a tool's output shows its images above the text…` |
| **Agent questions** (Claude's AskUserQuestion relayed by the fork's worker, options + free text, decline, expiry) | ✅ add-on core (`questions` slice, `AnswerQuestion`), conversation widget | `conversationWidgets` extension + bridge `questions` endpoints; Inbox section | `a question from the agent is shown on the card…` |
| Full-screen image viewer | ✅ | web actual of `rememberAttachmentViewer`: a popup over the whole window (dark backdrop, the image fitted and never enlarged; a tap, ✕ or Escape closes it; "Open in a new tab" for the browser's zoom). On Compose 1.12 its nodes stay in the accessibility tree after it closes, like every popup's (see the open issues); the test judges the close by pixels | `…open full screen; Escape closes` |

#### G. Mail

| Feature today | Compose | Work | Test |
|---|---|---|---|
| Boxes (Inbox, Flagged, Unread, Archive, Trash), search, filters, undo, badge | ✅ | none (the page was modelled on the Android Mail tab) | |
| Markdown body incl. tables | ✅ | HTML island (Phase 2) for selection/find | |
| vega-lite / vega / mermaid charts | ✅ | `NativeMarkdownDiagram` web actual with the offline renderer (Phase 2), in sandboxed frames inside the HTML island | `diagrams render offline in sandboxed frames` |
| Relative links open the file in the sender's checkout | ✅ | already in the shared Mail screen: links resolve against the reader's checkout (`MailReader.checkout_id`, `Markdown.link`) and open the file in Files; the island draws only links that resolve, so one leaving the checkout stays text. The demo's checkouts had `~/…` paths (not absolute), which left every link inert there; daemons report absolute paths | `a relative link in an agent's mail opens the file…` (`tests/islands.spec.ts`) |
| Share a message | ✅ | `rememberTextSharer` web actual: the system share sheet where the browser has one (phones), else the Markdown downloads as `<subject>.md`; menus drawn over the HTML island take clicks (the island's interop wrapper lets pointer events through) | `Share on a mail message downloads its Markdown…` |

#### H. Tools

| Page | Feature today | Compose | Work | Test |
|---|---|---|---|---|
| Machines | machine info, CPU/GPU | ✅ | none | `Tools pages: Machines, Usage and Changes render…` |
| Machines | **Gateway enroll / re-enroll** of the add-on's daemon | ✅ add-on core (`gateway` slice, `EnrollGateway`, `CloseEnrollment`, `UnenrollGateway`) | extension page calling the bridge's `gateway` endpoints. Note: needed *before* the Compose UI can work (D2), so it also stays on the old page | `Gateway: the Machines page offers enrollment…` |
| Machines | **SSH key** of the add-on, copy, connection test | ✅ add-on core (`ssh` slice, `TestSsh`) | extension section + bridge `ssh` endpoints | `Machines shows the add-on's own SSH key…` |
| Files | browse, dotfiles, text/images, download | ✅ | "Open preview" goes through the attachment viewer: a new tab, or a download for types a browser does not show | `Files browses the project's checkout…` |
| Schedules | list, create (presets, preview), run, pause, history, delete | ✅ | verify presets and next-run preview | `Schedules: create with the mock agent…` |
| Usage | quotas (Claude, Codex, Antigravity groups) | ✅ | checked live (OpenAI, Claude accounts and windows, the add-on's Claude sign-in). The daemon reports no Antigravity usage (the classic page asks `agy --print=/quota` through the bridge): the add-on core streams the bridge's `quota` and `backends` into `SLICE_ANTIGRAVITY` and Usage gets an Antigravity section while the add-on has its credentials (one card per group, its tightest window, reset times through the core's `Quotas.resetText`); checked live with the add-on's Antigravity account (3 groups, add-on 0.6.431.7) and with a stand-in `agy` in the e2e container, the mapping in `AddonCoreTest`. The Inbox's usage strip shows Antigravity as a third bar (its tightest group) through the fork's `MobileExtensions.usageBars`. Reset times show as raw ISO stamps ("resets 2026-10-30T01:29:59…") in upstream's shared Usage screen, in every app: the core's `QuotaRows.window` passes `resets_at` through (its test expects that) while the widget uses `Quotas.resetText`. Upstream fix, see `docs/proposals/web-client.md` | `antigravitysQuotaGroupsComeFromTheBridge` |
| Usage | **Claude sign-in** (`claude auth login`, paste code, cancel, logout) | ✅ add-on core (`claude` slice, sign-in/code/cancel/sign-out commands) | extension section + bridge `claude` endpoints | `Usage offers the Claude sign-in…` |
| Changes | project changes | ✅ | none | |
| Devices | trust state, approve code, remove | ✅ | none (refusal message when not enrolled) | `Devices shows the daemon's trust state…` |
| Settings | **Agent instructions** (prompt templates, reset) | ✅ add-on core | the account's three parts (`dieter prompt` through the bridge's `api/prompts`): a Settings section and an editor with variables, own templates and Reset; `AddonRules.instructionsProblem` checks the two blocks before sending. Per-project and per-board instructions stay in the shared admin screens | `InstructionsTest` (JVM); add-on e2e `Settings: agent instructions are edited and reset…` |
| Settings | **Title model**, **Keep finished work folders** | ✅ add-on core (`settings` slice, `ChangeSetting`) | extension settings section + bridge `settings` | |
| Settings | show reasoning, appearance, palette | ✅ | Phase 3 adds "follow HA" | |
| Terminals / Screens | (not on today's page) | ✅ in apps | web actuals: xterm.js island; screens later or hidden on web | |

#### I. Dreamfall (largest single feature, do last)

| Feature today | Compose | Work | Test |
|---|---|---|---|
| New Dreamfall board (categories, goal, navigation logic, implementation instructions, mail switch, times, weekdays, agent, stage boards) | ✅ | an extension page (Tools → Dreamfall → +, Projects → Add → New Dreamfall board), not a mode of the shared New board form; `AddonRules.dreamfallProblem` checks what the bridge checks | `next-dreamfall: the settings save…` |
| Funnel strip, stages side by side / tabs on phone, collapsible groups (newest 40) | ✅ | `BoardRenderer` claims the stage boards; Navigation first | `next-dreamfall: a board shows the night…` |
| Idea detail: brief, verdict + score, Provenance, Trail | ✅ | extension page `dreamfall-idea`; the core reads `GET dreamfall/card/<id>` when the record or lane changes | `next-dreamfall: an idea's page…` |
| Pick (worktree/project dir, start now), Park, Back to the queue, promote | ✅ | `DecideIdea`; the card stays busy until the bridge's state shows the change (its `rev`) | `next-dreamfall: Pick copies an idea…`, `…Park takes it out of the queue` |
| ⋮ run a stage now, pause/resume, settings, open stage as Kanban, Remove Dreamfall | ✅ | Kanban: the shared board screen for that stage while the route is open | menu entries in `next-dreamfall: the settings save…` |
| "Added outside Dreamfall" flag, "Finished · still in Running" + Review button | ✅ | rules in the add-on core | `DreamfallTest` (JVM) |
| Adding an idea by hand on Enumeration or Implementation | ✅ | "New idea" / "New task" in the stage's head opens New task on that stage's board; an idea starts on "Add to Todo" (fork: the form takes the intent's lane) | `next-dreamfall: a board shows the night…` |
| `dreamfall` command, schedules, KV records | n/a | unchanged (server side) | `the Enumeration agent adds ideas…`, `Navigation waits for Enumeration…` |

Upstream path: the Dreamfall proposal (`docs/proposals/dreamfall.md`, draft) points at this Compose implementation
as the reference.

#### J. Home Assistant side: unchanged

`sensor.dieter` via `/api/ha`, `rest_command.dieter_*`, events `dieter_turn_finished` / `dieter_card_review`
/ `dieter_question`, push notifications. They stay in the bridge. Only change: the push link must open the
card in whichever page is the default (A covers the new page).
Test: `the HA summary endpoint stays consistent`, `hass-cli is in the image…`.

### Order inside Phase 4

1. **4.0 Foundation**: web host, `RemoteMobileCore`, sign-in/pairing
2. **Read-only parity**: A, B, D (viewing), F (viewing), G, H Files/Usage/Changes/Devices
3. **Core actions**: C (built-in parts), D actions, E, F sending, H Schedules and Agent instructions
4. **Extension API + add-on-only screens**: questions, Claude sign-in, Gateway, SSH key, Title model, work
   folders, Add repository / New folder, board order
5. **Dreamfall** (I)
6. **Switch** ✅ (0.6.431.9, without the planned `/classic/` release): the add-on's root forwards to `next/`, `app.js`
   and `app.css` are removed, the two repositories are one. The bridge's endpoints that only the classic page used go
   in step 2, area by area
7. **Upstream proposals**: web target for `:ui`, the extension points, the `platformMonospace()` fix

Each group is done when its rows are ✅ and its tests pass against `/next/`. Tick the rows in this file as you go.

## Step 2: the bridge into the web host

The Compose page talks to the web host only; the web host's add-on core still reaches Dieter's add-on features through
the Python bridge (`addon/…/Bridge`: loopback HTTP and the event stream).

**Two processes, one jar** (decided 2026-10-30): the bridge's work moves to the *operator* (`host/…/operator`), not
into the web host itself. The operator runs as `dieter`, the agents' user, like the bridge: it is the daemon's local
operator through the store's identity, owns `/data/projects`, `/data/.ssh` and the Claude sign-in, and holds no secret.
The web host stays `dieter-web` with the gateway session, which agents can neither read nor reach. Folding both into
one process would need the web host to run as `dieter` (the session within the agents' reach) or a setuid `sudo` in
the image. Until the operator serves port 8099 itself the bridge forwards the moved paths (`OPERATOR_PATHS`); the
web host keeps calling `DIETER_WEB_BRIDGE_URL`, so nothing changes for it. The bridge also serves Ingress on 8099 and
answers Home Assistant. Its areas move to the operator one at a time, each its own add-on release
with the e2e suite green, and the last one deletes `dieter/bridge/` and Python from the image:

1. ✅ 0.6.431.10: the operator (run.sh starts it, the bridge forwards) and Agent instructions (`api/prompts`)
2. ✅ 0.6.431.11: settings, title model, board order (`/data/bridge_settings.json`, written by the operator; the bridge's
   titles, housekeeping and state stream read it, and the bridge follows a change after the forward: `after_operator`)
3. ✅ 0.6.431.12: projects: clone, new repository, adoption (`api/project`; the options' `projects` at start go through
   the operator too)
4. ✅ 0.6.431.13: the add-on's start and the daemon: harness runtime staging and the managed Bun, what the options
   configure (provider env file, git and gh, Claude Code's note, Antigravity), the daemon started and restarted (after
   a sign-in or enrollment the bridge asks: `daemon/restart`); moved ahead of the sign-in, which restarts the daemon
5. ✅ 0.6.431.16: Claude sign-in, gateway enrollment, SSH key (`api/claude/…`, `api/gateway…`, `api/ssh…`; the key is
   made at the operator's start). The bridge only follows up: the quota is probed again after a sign-in or sign-out,
   and the pages get the daemon's state after an enrollment (seen in the `gateway` status the page polls meanwhile)

6. Agent questions (the harness's `DIETER_QUESTIONS_URL`) and attachments
7. Dreamfall: the API, and the agents' `dreamfall` command (a mode of the jar instead of `dreamfall.py`; its records
   stay in Dieter's key-value store)
8. Housekeeping (work folder retention) and the Ollama titles
9. Home Assistant's side: `/api/ha`, the rest_commands' endpoints (`/api/card`, `…/send`, `…/move`, `/api/focus`,
   `/api/state`) and the events `dieter_turn_finished`, `dieter_card_review`, `dieter_question`, on port 8099 with
   the same paths and answers, so `homeassistant/packages/dieter.yaml` does not change
10. Ingress on 8099, the page's files and the relay to the web host; then `dieter/bridge/` goes

The e2e contract tests (`dieter/tests/e2e/bridge-api.spec.mjs`, `dreamfall-api.spec.mjs`) pin what Home Assistant and
the agents rely on; they run unchanged against every step.

---

## Open questions

1. **Gateway-less installs (D2).** Keep the old page for them, or build a "local mode" (the web host speaking
   to the daemon without an account)? The core has no such mode today.
2. **Sign-in callback.** Can the gateway redirect to an Ingress URL, or does the web host need a paste step?
3. **Pi resources.** Is ~200 MB for a JVM acceptable next to the harnesses, or go native-image from the start?
4. **Older iPads/iPhones** (iOS < 18.2): worth the compatibility-mode JS build?
5. **Multi-user HA.** The web host is one device for the account. Should different HA users see different
   things, or is the add-on single-user as today (admin panel)?

---

## Sources

- Spike: `~/dieter/spike/web` (README, `build.gradle.kts`, `DemoData.kt`, `watch.sh`, `ha/dashboard.py`)
- Add-on: [JustinNeumann/dieter-addon](https://github.com/JustinNeumann/dieter-addon): `dieter/DOCS.md`, `dieter/bridge/app.js` (removed in 0.6.431.9),
  `dieter/bridge/dieter_bridge.py`, `dieter/tests/e2e/*.spec.mjs`, `docs/dreamfall/CONCEPT.md`
- Dieter: [dbpprt/dieter](https://github.com/dbpprt/dieter): `apps/kotlin/ui/.../store/MobileCore.kt`, `navigation/Navigation.kt`,
  `core/runtime/.../platform/Platform.kt`, `CoreRuntime.kt` (loopback routes)
- [Compose Multiplatform 1.9: Compose for Web Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/)
  (accessibility, HTML interop, browser navigation, fallback for older browsers)
- [Kotlin/Wasm and web targets](https://kotlinlang.org/docs/web-overview.html)
- [MaterialKolor on klibs.io](https://klibs.io/project/jordond/MaterialKolor) (seed-color schemes, wasm/js support)
- [Introducing Hass.io Ingress](https://www.home-assistant.io/blog/2019/04/15/hassio-ingress/)
