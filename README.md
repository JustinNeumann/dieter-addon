# dieter-addon-kotlin

The Kotlin side of the [Dieter Home Assistant add-on](https://github.com/JustinNeumann/dieter-addon):
Dieter's real Compose Multiplatform UI (`apps/kotlin/ui` of [Dieter](https://github.com/dbpprt/dieter))
compiled for the browser with Kotlin/Wasm, served by the add-on behind Home Assistant Ingress at
`/next/`, next to the classic page.

The plan and its progress: [docs/PLAN.md](docs/PLAN.md).

## Layout

| Path | What |
|---|---|
| `dieter/` | Git submodule: [JustinNeumann/dieter](https://github.com/JustinNeumann/dieter) (upstream + the add-on's harness changes), pinned to the commit the add-on runs. The UI, the core and the Wire models are compiled from here, not copied |
| `web/` | The browser build (`wasmJs`): the browser actuals of the UI's platform hooks, the loader page, the entry point and `RemoteCore` (the UI's core over a WebSocket) |
| `host/` | The web host (JVM): Dieter's Kotlin core (`ClientApi`) with the JVM platform, serving it to browsers over one WebSocket; sign-in relay; `host/build/libs/dieter-web-host-<version>.jar` |
| `proto/` | `dieter/web/v1/host.proto`: the browser ↔ web host protocol (dispatch/observe of `dieter.client.v1`, as the iOS byte bridge) |
| `tests/` | Browser tests (Playwright) of the packaged bundle: selection, find, HTML islands, keyboard; `npm run measure` writes the accessibility checklist of docs/PLAN.md Phase 2 |
| `tools/package.sh` | Packs the production build into `dieter-web-<version>.tar.gz` (hashed file names, `.wasm.gz` for the loader, `.br`/`.gz` copies) |
| `.github/workflows/web.yml` | Builds and tests every push; a tag `vX.Y.Z` publishes the tarball, the host jar and `SHA256SUMS` as a release |

The submodule's own Gradle build is not used (it needs the Android SDK); `web/build.gradle.kts` compiles
the needed source sets directly and takes every version from the submodule's version catalog.

## Build

```bash
git clone --recurse-submodules https://github.com/JustinNeumann/dieter-addon-kotlin.git
cd dieter-addon-kotlin
./gradlew :web:wasmJsBrowserDistribution          # production build, ~3 min the first time
tools/package.sh 0.0.0-local                       # -> build/package/dieter-web-0.0.0-local.tar.gz
./gradlew :host:jvmTest :host:fatJar               # web host tests and host/build/libs/dieter-web-host-*.jar
./gradlew :web:wasmJsBrowserDevelopmentRun -t      # dev server with rebuilds on change (open with ?demo)
```

JDK 21 (with `javac`; the host targets Java 21). Gradle downloads Node and webpack for the wasm build. `npm` must be on the `PATH`
as well: the build bundles Dieter's offline diagram renderer (`dieter/apps/apple/mac/MarkdownPreview`:
mermaid, vega, vega-lite) with `npm ci` and `node build.mjs`.

**Against real data without your account:** Dieter's isolated fixture gateway
(`go run ./tools/fixtures/gateway -showcase-fixture` in `dieter/`, prints a session token and a pairing code)
plus the host jar; the host adopts the fixture session with `AdoptSession` over its WebSocket.

**Browser tests** (Chromium from Playwright, against the newest `build/package/dieter-web-*` or `DIETER_WEB_DIR`):

```bash
cd tests && npm ci && npx playwright install chromium
npm test                                           # selection, find, islands, keyboard
MEASURE_LABEL=after npm run measure                # accessibility checklist -> tests/measure-results/
```

URL options of the page: `?demo` uses demo data instead of the web host, `?theme=dark|light` overrides
the theme, `?script=tab projects; board Main` replays the app's debug navigation script, `?islands=off`
draws mail bodies and Markdown files with Compose instead of HTML islands.

**Reading on the web.** The mail message body and Markdown files are HTML islands: real DOM inside the
Compose layout, so the browser's selection, translation and screen readers work there, and mermaid,
vega and vega-lite diagrams render offline in sandboxed frames. Everywhere else the page is a canvas:
text is selectable inside the UI's selection areas (replies, tool output, subagent details), and
Ctrl/⌘+F opens the app's own find bar in a conversation, a mail message or a file (Enter / Shift+Enter
or the arrow keys step through the hits, Escape closes it). On other screens Ctrl/⌘+F stays the browser's.

## Release and the add-on

1. Tag `vX.Y.Z` and push the tag; CI publishes `dieter-web-X.Y.Z.tar.gz` and `SHA256SUMS`.
2. In `dieter-addon`, set `DIETER_WEB_VERSION`, `DIETER_WEB_SHA` and `DIETER_WEB_HOST_SHA` in `dieter/Dockerfile`
   (the lines of the release's `SHA256SUMS`) and bump the add-on version.

The bridge serves the unpacked bundle from `/opt/web` at `/next/`: hashed files as immutable,
`index.html` and the Compose resources with `no-cache` and an ETag. Home Assistant's Ingress removes
`Accept-Encoding` and does not compress `application/wasm`, so the loader fetches `X.wasm.gz` and inflates
it in the browser; the first load is about 5 MB instead of 15 MB.

## Browsers

Kotlin/Wasm needs WebAssembly GC: current Chrome, Edge and Firefox, Android System WebView (Companion app
on Android), Safari and the iOS/iPadOS WebView from 18.2. Older browsers get a message with a link to the
classic page.

## License

MIT, like Dieter. JetBrains Mono (`web/src/wasmJsMain/composeResources/files`) is under the SIL Open Font
License 1.1 (`OFL.txt` next to it).
