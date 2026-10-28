# dieter-addon

Home Assistant add-on repository with one add-on, **Dieter** (`dieter/`): the
[Dieter](https://github.com/dbpprt/dieter) coding-agent daemon and its page behind HA Ingress,
with Claude Code, Codex and an Antigravity harness. Supported: aarch64 (Raspberry Pi 4/5, HA Green/Yellow)
and amd64.

## Install

Settings → Add-ons → Add-on store → ⋮ → **Repositories** → add
`https://github.com/JustinNeumann/dieter-addon`, then install **Dieter**. The Supervisor pulls a
prebuilt multi-arch image `ghcr.io/justinneumann/dieter`. Options, provider login and the
optional Home Assistant package (sensors, push notification, services) are described in the add-on's
Documentation tab (`dieter/DOCS.md`).

## Layout

```
repository.yaml   add-on repository metadata
dieter/           the add-on
  config.yaml       metadata, Ingress on 8099, options/schema, image: (prebuilt image)
  Dockerfile        HA Debian base image (multi-arch), node 22 + dieter (sha256-pinned, cosign-verified release), claude 2.1.311,
                    codex 0.163.0, agy 1.4.8 (Antigravity), git, tmux, python3; bridge in /opt/bridge
  run.sh            entry point (exec the bridge, which runs the daemon)
  apparmor.txt      AppArmor profile (Docker's default capabilities, no mounts)
  bridge/           the bridge and page (dieter_bridge.py: REST + SSE API, Ingress, /api/ha)
  DOCS.md           user docs shown in the add-on's Documentation tab
  CHANGELOG.md      shown in the add-on's Changelog tab
  tests/            Playwright end-to-end suite (see below)
homeassistant/    optional HA package (sensors, rest_commands, push) and status dashboard
.github/workflows/builder.yaml   builds, signs and publishes the image on every push to main
```

## Development

### End-to-end tests (run before every release)

```bash
cd dieter/tests && npm install && npx playwright install chromium   # once
./run.sh            # ~1 min: builds the amd64 image, starts it with Dieter's mock harness
                    # (DIETER_ENABLE_MOCK_HARNESS=1) behind an Ingress-like proxy, runs e2e/*.spec.mjs
./run.sh -g "chat"  # a subset; --trace on for traces (npx playwright show-trace …)
```

The container uses the host network (bridge :18099, daemon :4342, proxy :18100) and keeps its
`/data` (staged runtime, test cards) in `~/.cache/dieter-addon-e2e/data`. The tests cover: page via
Ingress, add repository (chunked POST), task on the mock harness with streaming, chat field focus +
send, model/effort pickers, Inbox, Tools (Machines/Usage/Changes, Settings → Agent instructions), push deep link, `/api/ha`.

### Releasing

Every change that should reach installs needs a new `version` in `dieter/config.yaml`
(`<bundled Dieter release>.<add-on revision>`, e.g. `0.6.429.1`) and a `CHANGELOG.md` entry. The
push to `main` builds aarch64 and amd64, publishes the multi-arch manifest to ghcr.io tagged with that version; installs see
the update after the next store reload. Building on a device instead (local add-on) is in `DEPLOY.md`.
