# dieter-addon

Home Assistant add-on repository with one add-on, **Dieter** (`dieter/`): the
[Dieter](https://github.com/dbpprt/dieter) coding-agent daemon behind HA Ingress, with Claude Code, Codex and an
Antigravity harness, and Dieter's own Compose Multiplatform UI (`apps/kotlin/ui` of Dieter) compiled for the browser
with Kotlin/Wasm as its page. Supported: aarch64 (Raspberry Pi 4/5, HA Green/Yellow) and amd64.

## Install

Settings → Add-ons → Add-on store → ⋮ → **Repositories** → add
`https://github.com/JustinNeumann/dieter-addon`, then install **Dieter**. The Supervisor pulls a
prebuilt multi-arch image `ghcr.io/justinneumann/dieter`. Options, provider login, signing the page in to your
Dieter gateway and the optional Home Assistant package (sensors, push notification, services) are described in the
add-on's Documentation tab (`dieter/DOCS.md`).

## Layout

| Path | What |
|---|---|
| `repository.yaml` | add-on repository metadata |
| `dieter/` | the add-on: `config.yaml` (Ingress on 8099, options, `image:`), `Dockerfile` (HA Debian base, the fork's daemon release sha256-pinned and cosign-verified, `claude`, `codex`, `agy`, `gh`, Java 21 for the web host), `run.sh`, `apparmor.txt`, `DOCS.md` (Documentation tab), `CHANGELOG.md` (Changelog tab), `patches/` (the add-on's Antigravity adapter) |
| `dieter/bridge/` | the bridge (`dieter_bridge.py`): starts the daemon, serves Ingress on 8099 (the root forwards to the page at `next/`), relays the page to the web host, the REST + SSE API of the add-on's own features and Home Assistant's side (`/api/ha`, the rest_commands); `dreamfall.py` is the agents' `dreamfall` command. It moves into the web host step by step (`docs/PLAN.md`) |
| `dieter/tests/` | the add-on's end-to-end suite (Playwright against the image, below) and the bridge's unit tests |
| `fork/` | Git submodule: [JustinNeumann/dieter](https://github.com/JustinNeumann/dieter) (upstream + the add-on's changes). The UI, the core and the Wire models are compiled from here, not copied |
| `web/` | the browser build (`wasmJs`): the browser actuals of the UI's platform hooks, the loader page, the entry point, `RemoteCore` (the UI's core and the add-on core over a WebSocket) and the add-on's screens (`com.getdieter.addon`, plugged into the fork's `MobileExtensions`) |
| `host/` | the web host (JVM): Dieter's Kotlin core (`ClientApi`) with the JVM platform and the add-on core, serving both to browsers over one WebSocket; sign-in relay |
| `addon/` | the add-on core: the add-on's own features (gateway enrollment, SSH key, Claude sign-in, settings, agent questions, repositories, Dreamfall boards) as commands and slices, and their rules (`AddonRules`, also compiled into the page). Runs in the web host; the page only draws its slices ([ADR 0005](https://github.com/JustinNeumann/dieter/blob/web-addon/docs/adr/0005-web-client.md)) |
| `proto/` | `dieter/web/v1/host.proto` (browser ↔ web host: dispatch/observe of `dieter.client.v1` plus `addon_dispatch`/`addon_observe`) and `dieter/addon/v1/addon.proto` (the add-on core's commands and slices) |
| `tests/` | browser tests (Playwright) of the packed page: selection, find, HTML islands, keyboard, the add-on's screens; `npm run measure` writes the accessibility checklist of `docs/PLAN.md` Phase 2 |
| `tools/` | `package.sh` packs the page (hashed file names, `.wasm.gz` for the loader, `.br`/`.gz` copies); `addon-dist.sh` builds and packs the page and the web host into `dieter/web-dist` for the image |
| `homeassistant/` | optional HA package (sensors, rest_commands, push) and status dashboard |
| `.github/workflows/builder.yaml` | builds and tests the page and the web host, then builds, signs and publishes the image on every push to main |

The fork's own Gradle build is not used (it needs the Android SDK); `web/build.gradle.kts` compiles the needed
source sets directly and takes every version from the fork's version catalog.

## Development

```bash
git clone --recurse-submodules https://github.com/JustinNeumann/dieter-addon.git && cd dieter-addon
./gradlew :web:wasmJsBrowserDistribution           # the page, ~3 min the first time
./gradlew :host:jvmTest :host:fatJar               # web host + add-on core tests and the host jar
./gradlew :web:wasmJsBrowserDevelopmentRun -t      # dev server with rebuilds on change (open with ?demo)
tools/addon-dist.sh                                # both, packed into dieter/web-dist for the image
```

JDK 21 (with `javac`). Gradle downloads Node and webpack for the wasm build; `npm` must be on the `PATH` as well (the
build bundles Dieter's offline diagram renderer, `fork/apps/apple/mac/MarkdownPreview`: mermaid, vega, vega-lite).

**Browser tests** (Chromium from Playwright, against the newest `build/package/dieter-web-*` or `DIETER_WEB_DIR`):

```bash
cd tests && npm ci && npx playwright install chromium
npm test                                           # selection, find, islands, keyboard, add-on screens
MEASURE_LABEL=after npm run measure                # accessibility checklist -> tests/measure-results/
```

URL options of the page: `?demo` uses demo data instead of the web host, `?theme=dark|light` overrides the theme,
`?script=tab projects; board Main` replays the app's debug navigation script, `?islands=off` draws mail bodies and
Markdown files with Compose instead of HTML islands.

**Against real data without your account:** Dieter's isolated fixture gateway
(`go run ./tools/fixtures/gateway -showcase-fixture` in `fork/`, prints a session token and a pairing code) plus the
host jar; the host adopts the fixture session with `AdoptSession` over its WebSocket.

**End-to-end tests of the add-on (run before every release):**

```bash
cd dieter/tests && npm install && npx playwright install chromium   # once
./run.sh            # builds the amd64 image (and dieter/web-dist first when it is empty), starts it with Dieter's
                    # mock harness (DIETER_ENABLE_MOCK_HARNESS=1) behind an Ingress-like proxy, runs e2e/*.spec.mjs
./run.sh -g "Dreamfall"   # a subset; --trace on for traces (npx playwright show-trace …)
```

The container uses the host network (bridge :18099, daemon :4342, proxy :18100) and keeps its `/data` in
`~/.cache/dieter-addon-e2e/data` (`E2E_DATA` for another one). The suite covers the page behind Ingress (loading,
deep links, sign-in relay, the add-on's screens, Dreamfall) and the contract Home Assistant and the agents rely on
(`/api/ha`, the rest_commands' endpoints, `api/focus`, the `dreamfall` command).

**Reading on the web.** The mail message body and Markdown files are HTML islands: real DOM inside the Compose
layout, so the browser's selection, translation and screen readers work there, and mermaid, vega and vega-lite
diagrams render offline in sandboxed frames. Everywhere else the page is a canvas: text is selectable inside the UI's
selection areas (replies, tool output, subagent details), and Ctrl/⌘+F opens the app's own find bar in a
conversation, a mail message or a file. On other screens Ctrl/⌘+F stays the browser's.

**Browsers.** Kotlin/Wasm needs WebAssembly GC: current Chrome, Edge and Firefox, Android System WebView (Companion
app on Android), Safari and the iOS/iPadOS WebView from 18.2. Older browsers get a message saying so.

### Releasing

Every change that should reach installs needs a new `version` in `dieter/config.yaml`
(`<bundled Dieter release>.<add-on revision>`, e.g. `0.6.431.9`) and a `CHANGELOG.md` entry. The push to `main`
builds the page and the web host, runs their tests, builds aarch64 and amd64 and publishes the multi-arch manifest
to ghcr.io tagged with that version; installs see the update after the next store reload. The plan and its progress:
[docs/PLAN.md](docs/PLAN.md); building on a device instead (local add-on) and bumping the fork: `DEPLOY.md`.

## License

MIT (`LICENSE`), like Dieter. JetBrains Mono (`web/src/wasmJsMain/composeResources/files`) is under the SIL Open
Font License 1.1 (`OFL.txt` next to it).
