# Proposal for Dieter: a supported web client

Status: draft from dieter-addon-kotlin, for discussion upstream (dbpprt/dieter). Everything below already runs in
this repository on top of the fork JustinNeumann/dieter. Items 1, 5 and 6 are implemented in the fork (branch
`web-addon`, [ADR 0005](https://github.com/JustinNeumann/dieter/blob/web-addon/docs/adr/0005-web-client.md), which
also amends the two invariants a web client touches). The proposal lists the small upstream changes that would let
other hosts (not only Home Assistant) offer Dieter in a browser.

## What exists here

- **The shared UI in the browser.** `apps/kotlin/ui` compiles to Kotlin/Wasm unchanged except two source filters
  (`FontFamily.Monospace` has no browser font; `materialScheme` is private) and a handful of `expect` actuals
  (no-op native surfaces, platform colour scheme). About 15 MB of wasm, 5 MB gzip.
- **A web host.** A JVM process runs `ClientApi` over `CoreRuntime` with the existing JVM platform pieces
  (OkHttp transport, `GrpcTunnelFactory`, `SoftwareDeviceKeys`, JCA signatures) and a file-backed secure store,
  and serves `MobileCore` to browsers over one WebSocket: `proto/dieter/web/v1/host.proto`, which is the iOS
  byte bridge's contract (`Command` → `Reply{result|failure}`, `observe(slice, scope)` → `Update`) with request ids.
- **`RemoteCore`** in the browser implements `MobileCore` over that socket: reconnect with backoff, observe again
  after a reconnect (a fresh snapshot), a synthesized "reconnecting" session while the socket is down.

## Proposed upstream changes

1. **Per-attempt OAuth redirect** (done in the fork). `RuntimeConfig.oauthRedirectUri` is fixed per runtime, and
   `SignIn.isCallback` compares the callback's prefix with it. A server-side core is reached under several
   addresses (LAN, a tunnel, a reverse proxy), each of which the gateway must list. In the fork:
   `RuntimeConfig.oauthRedirectPerAttempt` lets `BeginSignIn.redirect_uri` name the return address of one attempt;
   the core checks it (http/https, a host, no query or fragment), stores it in `PendingSignIn.redirect_uri` and
   completes only a callback to it. Off by default, so the apps keep their fixed scheme.
2. **A web redirect allow-list on the gateway that is easier to operate.** `DIETER_NATIVE_REDIRECT_URIS` is exact
   match, which is right; documenting the web case (an `https://host/…/signin` callback) next to the app schemes
   would help, and so would a per-account setting instead of a server environment variable.
3. **Foreground as a count.** `SetForeground(bool)` assumes one app. A host with many tabs keeps a per-tab flag
   and calls `SetForeground(true)` while any tab is shown (and `false` after a grace period). A
   `SetForeground{client_id, foreground}` with the counting inside the core would serve every multi-window host
   (the Mac app's windows too).
4. **A `:core:web-host` module.** The host here is ~600 lines (WebSocket server, connection bridge, sign-in relay,
   foreground counting). Upstream could ship it next to `core/apple`'s byte bridge, so the protocol evolves with
   `client.proto` instead of in a downstream repository.
5. **UI extension points** (done in the fork): `MobileExtensions` (`apps/kotlin/ui/…/extensions`) with Tools pages,
   sections on built-in tool pages, Inbox sections, conversation widgets, Projects menu actions and a board
   renderer; empty by default. Extensions are presentation: a host's own features bring their own core. Here that
   is the add-on core (`addon/`, protocol `proto/dieter/addon/v1/addon.proto`), which runs in the web host next to
   Dieter's core and shares the WebSocket (`addon_dispatch` / `addon_observe` frames). Upstream would need only the
   extension points; how a host serves its features stays its own. Two features use them by now: agent questions
   (Inbox section, conversation widget) and Dreamfall (Tools page, board renderer, Projects action; see
   `dreamfall.md`).
6. **Deep links through the core** (done in the fork). `OpenConversationRequest{card_id}` returns the
   `NotificationTarget` a link should open (the conversation, or the Inbox when the card is unknown or archived),
   the resolution a notification tap uses. The web page maps `…/next/card/<id>` to it instead of opening a card it
   cannot check.

7. **Browser actuals for `:ui`.** If upstream builds `apps/kotlin/ui` for wasmJs, these could live there instead
   of in each host: the attachment picker (`<input type=file>` into `Attachments.part`, the apps' limit messages),
   the attachment viewer (a Blob URL in a new tab, or a download for types a browser does not show), the text
   sharer (Web Share where there is one, else a Markdown download) and the system Back (browser history through
   `NavigationEventInput`). Each is a few dozen lines in `web/src/wasmJsMain`.

## Found while checking parity (not web-specific)

- **Usage shows raw reset times.** `QuotaRows.window` copies `ProviderQuotaWindow.resets_at` into the row
  (`QuotaRowsTest` expects the ISO stamp) and `UsageScreen` prints it as "resets 2026-10-30T01:29:59.711563+00:00".
  The widget already formats it with `Quotas.resetText(it, now)`; the row could carry that text instead.
- **A host's title model.** A host that names tasks itself (the add-on: Ollama through Home Assistant) could not hook
  New task: a task without a title always got the daemon's auto-title. The fork adds `ConversationNamer`
  (`CreationModel.submit`): for the machines the host names, the task is created under `Titles`' placeholder
  without `auto_generate_title`, then handed to the host. Upstream could take it as is, or as a `CreationIntent` flag.
- **The project used last.** Every app already remembers the project last created in (`CreationPreferences.project_id`)
  and `remember_creation` exists, but nothing reports opening a project. The fork reports opens from navigation, lists
  the project first ("Last used") and opens it when the Projects tab is chosen from another tab.

## Security notes for a web host

- The host holds a full account session: it should run as its own OS user with private state and answer only
  its front end (here: a per-boot secret passed on an inherited descriptor, the front end non-dumpable).
- The browser holds nothing secret; the device key and session stay on the host. One host = one paired device.

## Known limits

- Compose Multiplatform 1.12's web accessibility follows only the newest semantics owner: after a popup (menu,
  dialog, picker) closes, the browser's accessibility tree keeps the popup's nodes until the next popup opens.
  Compose 1.13 tracks every owner. Until the fork moves to 1.13, screen readers lose the page after a popup.
