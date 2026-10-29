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
| `web/` | The browser build (`wasmJs`): the browser actuals of the UI's platform hooks, the loader page and the entry point |
| `tools/package.sh` | Packs the production build into `dieter-web-<version>.tar.gz` (hashed file names, `.wasm.gz` for the loader, `.br`/`.gz` copies) |
| `.github/workflows/web.yml` | Builds every push; a tag `vX.Y.Z` publishes the tarball and `SHA256SUMS` as a release |

The submodule's own Gradle build is not used (it needs the Android SDK); `web/build.gradle.kts` compiles
the needed source sets directly and takes every version from the submodule's version catalog.

## Build

```bash
git clone --recurse-submodules https://github.com/JustinNeumann/dieter-addon-kotlin.git
cd dieter-addon-kotlin
./gradlew :web:wasmJsBrowserDistribution          # production build, ~3 min the first time
tools/package.sh 0.0.0-local                       # -> build/package/dieter-web-0.0.0-local.tar.gz
./gradlew :web:wasmJsBrowserDevelopmentRun -t      # dev server with rebuilds on change
```

JDK 17 or newer. Gradle downloads Node and webpack for the wasm build.

URL options of the page: `?theme=dark|light` overrides the theme, `?script=tab projects; board Main`
replays the app's debug navigation script.

## Release and the add-on

1. Tag `vX.Y.Z` and push the tag; CI publishes `dieter-web-X.Y.Z.tar.gz` and `SHA256SUMS`.
2. In `dieter-addon`, set `DIETER_WEB_VERSION` and `DIETER_WEB_SHA` in `dieter/Dockerfile` (the sha256 from
   the release's `SHA256SUMS`) and bump the add-on version.

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
