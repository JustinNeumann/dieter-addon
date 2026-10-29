# Proposal for Dieter: a supported web client

Status: draft from dieter-addon-kotlin, for discussion upstream (dbpprt/dieter). Everything below already runs in
this repository on top of an unmodified Dieter checkout; the proposal lists the small upstream changes that would
remove its workarounds and let other hosts (not only Home Assistant) offer Dieter in a browser.

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

1. **Per-attempt OAuth redirect.** `RuntimeConfig.oauthRedirectUri` is fixed per runtime, and `SignIn.isCallback`
   compares the callback's prefix with it. A server-side core is reached under several addresses (LAN, a tunnel,
   a reverse proxy), each of which the gateway must list. Proposal: `BeginSignIn.redirect_uri` (optional; must be
   one of the runtime's allowed redirects or any when the host opts in), stored with the pending attempt, and
   `CompleteSignIn` matching against the stored one. Today the host starts the attempt with its own `SignIn`
   instance and completes it under a fixed internal callback.
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
5. **UI extension points** (decision D3 of the plan): `extraToolPages`, `extraSettingsSections`,
   `boardRenderers`, `conversationWidgets`, plus a way to route `Command`s the core does not know to the host. That
   lets add-on-only features (agent questions relay, Dreamfall boards, provider sign-in) live outside the fork's
   screens.

## Security notes for a web host

- The host holds a full account session: it should run as its own OS user with private state and answer only
  its front end (here: a per-boot secret passed on an inherited descriptor, the front end non-dumpable).
- The browser holds nothing secret; the device key and session stay on the host. One host = one paired device.
