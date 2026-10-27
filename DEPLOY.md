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
sources checksum in `dieter/Dockerfile`, the two archive checksums in `dieter/bridge/dieter_bridge.py`.
The sources checksum is computed from the fork checkout at the tag:

```bash
cd internal/harness/runtime
find . -maxdepth 1 -type f \( -name '*.mjs' -o -name 'package.json' -o -name 'package-lock.json' \) ! -name '*.test.mjs' \
  | LC_ALL=C sort | xargs sha256sum | sha256sum
```
