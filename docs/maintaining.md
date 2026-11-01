# Releasing and maintaining

Notes for whoever publishes releases or builds the add-on on a device. Users: see the [README](../README.md).

## Release (what users install)

`config.yaml` sets `image: ghcr.io/justinneumann/dieter`, so the Supervisor never builds the
add-on on a user's device: it pulls `<image>:<version>`. Hence every change that should reach
installs needs

1. a new `version` in `dieter/config.yaml` (`<bundled Dieter release>.<add-on revision>`) and a
   `dieter/CHANGELOG.md` entry;
2. `dieter/tests/run.sh` green;
3. a pull request from this repository, merged when green: `.github/workflows/builder.yaml` builds the page and the
   web host from `web/`, `host/` and `addon/` (with the `fork` submodule), runs the web host's tests and the page's
   browser tests, hands both to the image in `dieter/web-dist` (`tools/addon-dist.sh`), then builds aarch64 and
   amd64 on native runners with the `home-assistant/builder` actions, signs them with Cosign and publishes the
   multi-arch manifest `ghcr.io/justinneumann/dieter:<version>` (plus `{arch}-dieter`).

   **The image comes first.** The Supervisor offers a version as soon as `config.yaml` on `main` names it, and a pull
   it cannot find fails ("Can't install …: not found"). So a pull request that changes the version publishes that
   version's image itself, labelled `io.dieter.tree` with the tree it was built from (the pull request merged into
   `main`). After the merge, `main` only moves `:latest` when its tree is the same, and builds and publishes the
   version again when it is not (`main` moved in between: merge release pull requests one at a time, each up to
   date). Pull requests that keep the version only build, and on `main` a version whose tag already exists is not
   pushed again (`skip-existing`). Pull requests from other repositories never publish.
4. nothing more for the GitHub Release: when `main` gets a new version, the workflow's last job tags that commit
   `<version>` and publishes a Release with the version's `CHANGELOG.md` section (marked Latest), after the image is out.


One-time: after the first successful run make the packages public (GitHub → Packages →
`dieter`, `aarch64-dieter`, `amd64-dieter` → Package settings → Change visibility), and the repository
itself must be public for the Supervisor to fetch it.

Installs pick up the new version at the next store reload (Settings → Add-ons → ⋮ → Check for
updates, or automatically within a day).

## Local add-on (building on the device)

The `dieter/` folder copied to `/addons/dieter` becomes the local add-on `local_dieter`. Because
`image:` makes the Supervisor pull instead of build, the copy must drop that line. The image takes the page and the
web host from `dieter/web-dist`, so build them first:

```bash
tools/addon-dist.sh                       # -> dieter/web-dist/dieter-web.tar.gz, dieter-web-host.jar
tar -C <checkout>/dieter -czf - --exclude=__pycache__ --exclude=tests/node_modules --exclude=tests/test-results . \
  | ssh <user>@<ha-host> 'sudo sh -c "rm -rf /addons/dieter && mkdir -p /addons/dieter && tar -xzf - -C /addons/dieter && sed -i \"/^image:/d\" /addons/dieter/config.yaml"'
ssh <user>@<ha-host> 'bash -lc "ha store reload && ha apps rebuild local_dieter"'
```

The local add-on has the slug `local_dieter` (hostname `local-dieter`), the repository install
`4d8f2a84_dieter`; `homeassistant/packages/dieter.yaml` must match the one in use.

If your Home Assistant configuration is synced from a repository, push HA-side changes (`packages/dieter.yaml`,
`dashboards/dieter.yaml`, `configuration.yaml`) there too, or the sync resets them. `/addons/dieter` is not touched
by such a sync.

Dieter itself is the fork release `vX.Y.Z-antigravity.N` of `JustinNeumann/dieter` (workflow "Fork server
release", triggered by pushing such a tag on a branch that carries the Antigravity adapter). Bumping it
means: new `DIETER_VERSION`, `DIETER_COMMIT`, tarball checksums from its `SHA256SUMS` and the runtime
sources checksum in `dieter/Dockerfile`, the two archive checksums in `host/src/jvmMain/kotlin/com/getdieter/operator/Harness.kt`,
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
and release artifacts that affect the add-on (a scheduled task).

- **2026-10-31**, fork release **`0.6.431-antigravity.10`** (`754e3d1c`): `antigravity.9` plus the daemon's refusal of
  `merge_pr` and `merge_local` from agent turns (PermissionDenied), the conflict banner only while a workspace is
  conflicted, and new tasks starting in a new worktree. Harness runtime sources and both harness archives unchanged;
  tarball checksums from its `SHA256SUMS`, verified with cosign. Bumped in 0.6.431.32.
- **2026-10-31**, fork release **`0.6.431-antigravity.9`** (`215c5403`): `antigravity.8` plus Home Assistant access
  per conversation (card, chat and schedule flag, `CardService.GetTurnCaller` / `dieter card caller`, fork proto
  fields from 1001) and the harness worker's question poll token. Adapter file unchanged; new runtime sources
  checksum and both harness archives (`host-questions.mjs`); tarball checksums from its `SHA256SUMS`, verified with
  cosign v3.1.4. Bumped in 0.6.431.28, which drops the `host-questions.mjs` override.
- **2026-10-30**, fork release **`0.6.431-antigravity.8`** (`2aafb54b`): `antigravity.6` plus two store/API changes
  (`.7`: a board whose cards are all archived can be retired; `.8`: those archived cards stay readable). Adapter
  file, runtime sources checksum and both harness archives unchanged; tarball checksums from its `SHA256SUMS`,
  verified with cosign. Bumped in 0.6.431.8 (`.7` was never pinned in a release).
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

## The page and the web host

They are built from this repository with every image (`tools/addon-dist.sh`: the page's production build packed by
`tools/package.sh`, and the web host's fat jar, copied to `dieter/web-dist`), so they always match the add-on's
commit; nothing is pinned. Their version is the add-on's (`DIETER_WEB_VERSION`, shown in the page's `BUILD.json`).
The UI is compiled from the `fork` submodule: moving it (`git -C fork checkout <commit>`, then commit the gitlink) is
how the page gets Dieter's newer screens; the daemon in the image stays the fork release pinned in the Dockerfile.

For a quick e2e run of a page built elsewhere: `WEB_BUNDLE=<unpacked dir> WEB_HOST_JAR=<jar> dieter/tests/run.sh`
builds the image without them and mounts both. Until 0.6.431.8 the page came from the separate repository
dieter-addon-kotlin (releases `vX.Y.Z`, pinned by sha256); its history is part of this repository.
