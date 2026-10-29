# Releasing and local deployment

## Release (what users install)

`config.yaml` sets `image: ghcr.io/justinneumann/dieter`, so the Supervisor never builds the
add-on on a user's device: it pulls `<image>:<version>`. Hence every change that should reach
installs needs

1. a new `version` in `dieter/config.yaml` (`<bundled Dieter release>.<add-on revision>`) and a
   `dieter/CHANGELOG.md` entry;
2. `dieter/tests/run.sh` green;
3. a push to `main`: `.github/workflows/builder.yaml` builds aarch64 and amd64 on native runners with
   the `home-assistant/builder` actions, signs them with Cosign and publishes the multi-arch manifest
   `ghcr.io/justinneumann/dieter:<version>` (plus `{arch}-dieter`); pull requests only build.
   A version whose tag already exists is not pushed again (`skip-existing`).

One-time: after the first successful run make the packages public (GitHub → Packages →
`dieter`, `aarch64-dieter`, `amd64-dieter` → Package settings → Change visibility), and the repository
itself must be public for the Supervisor to fetch it.

Installs pick up the new version at the next store reload (Settings → Add-ons → ⋮ → Check for
updates, or automatically within a day).

## Local add-on on the Pi (building on the device)

The `dieter/` folder copied to `/addons/dieter` becomes the local add-on `local_dieter`. Because
`image:` makes the Supervisor pull instead of build, the copy must drop that line:

```bash
tar -C ~/dieter-addon/dieter -czf - --exclude=__pycache__ --exclude=tests/node_modules --exclude=tests/test-results . \
  | ssh hassio@192.168.0.10 'sudo sh -c "rm -rf /addons/dieter && mkdir -p /addons/dieter && tar -xzf - -C /addons/dieter && sed -i \"/^image:/d\" /addons/dieter/config.yaml"'
ssh hassio@192.168.0.10 'bash -lc "ha store reload && ha apps rebuild local_dieter"'
```

The same folder is exported over NFS to the Pi as `/share/dieter`.

The local add-on has the slug `local_dieter` (hostname `local-dieter`), the repository install
`4d8f2a84_dieter`; `homeassistant/packages/dieter.yaml` must match the one in use.

Caveat on this Pi: the scheduled `sync_from_github.sh` resets `/config` from the
`Home-Assistant-Sem2` repository, so HA-side changes (`packages/dieter.yaml`, `dashboards/dieter.yaml`,
`configuration.yaml`) must be pushed there to stay. `/addons/dieter` is not touched by the sync.

Dieter itself is the fork release `vX.Y.Z-antigravity.N` of `JustinNeumann/dieter` (workflow "Fork server
release", triggered by pushing such a tag on a branch that carries the Antigravity adapter). Bumping it
means: new `DIETER_VERSION`, `DIETER_COMMIT`, tarball checksums from its `SHA256SUMS` and the runtime
sources checksum in `dieter/Dockerfile`, the two archive checksums in `dieter/bridge/dieter_bridge.py`,
and `dieter/patches/patch_runtime.py`: it replaces the fork's `antigravity-runtime.mjs` with the add-on's
copy and fails the build when the fork's file no longer has the recorded sha256, so changes of the fork's
adapter get merged into `dieter/patches/antigravity-runtime.mjs` (then record the new hash). Run
`dieter/tests/unit.sh` (needs a staged runtime's `node_modules`) after touching the adapter.
The sources checksum is computed from the fork checkout at the tag:

```bash
cd internal/harness/runtime
find . -maxdepth 1 -type f \( -name '*.mjs' -o -name 'package.json' -o -name 'package-lock.json' \) ! -name '*.test.mjs' \
  | LC_ALL=C sort | xargs sha256sum | sha256sum
```

Verify `SHA256SUMS` before taking checksums from it. The add-on has no cosign, but the workstation can fetch
the binary from `sigstore/cosign` (check it against `cosign_checksums.txt`) and run, in the release's download
directory:

```bash
cosign verify-blob --bundle SHA256SUMS.sigstore.json SHA256SUMS --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity https://github.com/JustinNeumann/dieter/.github/workflows/fork-server-release.yml@refs/tags/vX.Y.Z-antigravity.N
```

## Upstream screening

Weekly check of upstream `dbpprt/dieter` for changes to the daemon CLI, daemon API/env, harness runtime
and release artifacts that affect the add-on (scheduled card).

- **2026-10-29**, fork release **`0.6.431-antigravity.6`** (`2c576408`): `fork-release` merged with upstream main
  (v0.6.431, `7f2afded`, the range screened below), the fork's CI fixed and green, bumped to it in 0.6.431.1.
  Adapter file (`antigravity-runtime.mjs`), runtime sources checksum and both harness archives are the same as in
  `0.6.429-antigravity.5`; tarball checksums from its `SHA256SUMS`, verified with cosign.
- **2026-10-28**, screened through upstream **v0.6.431** (`7f2afded`, = main). Baseline: the pinned fork
  release `0.6.429-antigravity.3` already contains upstream through `2d736128` (after v0.6.430), so the range
  was `2d736128..7f2afded`: only Mail rendering in the apps (#171, #172), tests and fixtures; no change to
  `internal/cli`, the daemon, `internal/harness` or the release artifacts. Nothing to adapt, and no rebase of
  `antigravity-pr` needed for the add-on. The fork had a newer `0.6.429-antigravity.4` (`116680d2`, re-homes
  checkouts after a same-account re-enrollment, `daemon unenroll --help`): bumped to it in 0.6.429.24
  (tarball checksums from its `SHA256SUMS`; the sigstore bundle's digest and workflow identity checked, not
  cryptographically verified (no cosign here); runtime sources checksum and harness archives unchanged; the
  bridge reuses the staged runtime of an equal archive), Re-enroll texts in the page and DOCS.md.

## The new page's bundle (dieter-addon-kotlin)

`dieter/Dockerfile` downloads `dieter-web-<version>.tar.gz` from the releases of
[JustinNeumann/dieter-addon-kotlin](https://github.com/JustinNeumann/dieter-addon-kotlin) and checks its sha256.
A new bundle: tag `vX.Y.Z` there (its CI builds and publishes the tarball, the web host jar and `SHA256SUMS`), then
set `DIETER_WEB_VERSION`, `DIETER_WEB_SHA` and `DIETER_WEB_HOST_SHA` (the lines of `SHA256SUMS`) and bump the add-on
version. For a local test of unreleased builds: `tools/package.sh` and `./gradlew :host:fatJar` there, then
`WEB_BUNDLE=<unpacked dir> WEB_HOST_JAR=<jar> dieter/tests/run.sh e2e/next.spec.mjs`.
