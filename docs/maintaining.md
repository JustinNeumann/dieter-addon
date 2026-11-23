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

- **2026-11-23**, screened through upstream **v0.7.452** (`e02a287d`, latest GitHub release; also **v0.7.451**
  `1ff325b9`) and main (`526f3387`), from v0.7.450 (`f2f27576`, the screen of 2026-11-16): 7 commits. Relevant ones:
  - Worktree lifecycle in empty repositories (`526f3387`, #227): `internal/git/unborn.go` detects unborn `HEAD` in
    repositories with no commits yet; `internal/git/workspace/manager.go` creates orphan branches (`git worktree add
    --orphan -b <branch> <path>`) when provisioning worktrees from an empty repository; `internal/git/changeset/service.go`
    diffs against the empty tree and reports no target conflicts when the base branch is unborn; and
    `internal/git/operations/manager.go` fast-forwards or initializes the unborn base branch on `merge_local` and skips
    `origin/<base>` fetch when that remote branch does not exist yet. No CLI flags or proto schemas change.
  - Missing Codex rollout recovery and sandbox session migration (`071f756f`, #226): in `internal/harness/runtime/runner.mjs`,
    `HarnessAgent` no longer takes `sandbox` directly (`sessionOptions` passes `sandboxSession` to
    `agent.createSession`), and a Codex turn whose `thread/resume` fails with `no rollout found for thread id …` (for
    example after provider accounts moved `CODEX_HOME` into `DIETER_HOME/codex`) buffers early chunks, destroys the
    stale resume session, starts a fresh Codex thread with a missing-context notice prepended to the user prompt, and
    guards `suspend()` with `sessionReplacement` so `SIGUSR1` never checkpoints the gap between destroying the stale
    session and starting the replacement turn.
  - Apps (Kotlin, `1ff325b9` v0.7.451, `e02a287d` v0.7.452, #224/#225): floating liquid-glass navigation bar and
    elevated card/glass composer in `apps/kotlin/ui` (`FloatingNavigation.kt`, `Composer.kt`, `Glass.kt`,
    `GlassEffects.kt`, `LayoutModel.kt`, `SettingsScreen.kt` Appearance → Navigation / Composer), plus a new `internal
    expect fun createGlassEffects(): GlassEffects` in `com.getdieter.mobile.platform` (`GlassEffects.kt`, with
    `.android.kt`, `.ios.kt` and `.jvm.kt` actuals; `GlassEffects.jvm.kt` uses `org.jetbrains.skia.RuntimeEffect` /
    `ImageFilter.makeRuntimeShader` on `GLASS_LENS_SHADER`).
  - Release assets and gateway compatibility: v0.7.451 and v0.7.452 keep the same 19 asset names (`SHA256SUMS` +
    sigstore bundle). `deploy/gateway/compatibility-policy.json` remains at `0.7.449`.

  Nothing to adapt with what is pinned (`0.6.431-antigravity.11`, submodule `b1ce51fb`). The fork has no release newer
  than `antigravity.11` and `web-addon` has not moved. The `ProjectEditor.kt` override is unchanged upstream. The pinned
  runtime sources still match `RUNTIME_SOURCES_SHA`, and `patch_runtime.py` applies to them. Follow-ups:
  - `fork-release` needs upstream **v0.7.452** (`e02a287d`) or main (`526f3387`) merged, then tag
    `v0.7.452-antigravity.12`. All of `patch_runtime.py`'s anchors in `local-sandbox.mjs` and `runner.mjs` (except the
    fork-only `host-questions.mjs` import) still match `526f3387` once, and the merge conflicts and provider-account
    follow-ups noted on 2026-11-16 still apply.
  - Moving the submodule (`web-addon` <- `526f3387`, together with the daemon bump) needs, in addition to the items
    listed on 2026-11-16, a `web/src/wasmJsMain` actual for
    `com.getdieter.mobile.platform.createGlassEffects(): GlassEffects` (either compiling `GlassEffects.jvm.kt`'s Skia
    `RuntimeEffect`/`ImageFilter` shader implementation on Kotlin/Wasm or providing a `GlassEffects` actual).
- **2026-11-16**, screened through upstream **v0.7.450** (`f2f27576`, = main; latest GitHub releases **v0.7.448**
  `76442c3e` and pre-release **v0.7.449** `9dcd1707`), from main `e93d4f37` (the screen of 2026-11-09): 20 commits.
  Relevant ones:
  - Provider accounts (`b425c255`, v0.7.447): replaces the gateway-scoped quota store (`internal/providerquota` and the
    gateway's quota RPCs/tables) with replicated provider accounts and per-machine logins (`internal/engine/accounts`,
    `api/proto/dieter/v1/provider_accounts.proto`, `DIETER_HOME/provider-accounts/<id>/`, shared Codex home
    `DIETER_HOME/codex`). `dieter quota` (`list`, `watch`, `refresh`, `include`, `exclude`, `reset`) is replaced by
    `dieter provider list|add|rename|include|exclude|default|remove|login|logout|check|usage|reset|token`, and
    `card create|send|update`, `chat create|send` and `schedule create|update` gain `--account ID|LABEL`. In
    `internal/harness/runtime`, `quota-claude.mjs` and `quota-openai.mjs` are replaced by `usage-claude.mjs` (SDK
    prompt-less session, `{loggedIn, identity, usage}` output), `smol-toml` is dropped from `package.json`, and
    `runner.mjs` forwards `providerAuth(request)`, `provider-usage` and `auth-failure` frames.
    `DIETER_CODEX_ACCOUNT_HOMES` and `DIETER_CLAUDE_ACCOUNT_HOMES` are removed from `config.Daemon`.
  - Live conversation Changes (`9dcd1707`, v0.7.449): project-directory conversations and mobile chats get Changes in
    place ("Shared checkout"), `dieter workspace watch-changes` streams live changesets (`WatchChangeset` RPC in
    `git.proto`), `workspace run` pins per-file revisions (`--expect PATH=REVISION`), `workspace diff` and
    `workspace comment` drop their changeset revision flag, and worktree Changes list branch commits and per-file line
    counts.
  - Synced folders (`d2f86612`, v0.6.446): `dieter folder` (`list`, `show`, `create`, `update`, `archive`, `attach`,
    `member`, `pause`, `resume`, `detach`, `status`, `rescan`, `deletions`, `trash`, `index`, `blocks`, `apply`,
    `client`), `dieter file cp --folder`, `internal/folders` + `internal/host/folders` + `internal/store/folders`,
    `api/proto/dieter/v1/folders.proto`, and linked-folder prompt instructions.
  - Strix security-scan harness (`d5bae373` v0.6.444, `fa229f66`): new `strix` adapter in `harnesses.yaml`,
    `internal/harness/strix.go` (downloads pinned Strix 1.7.1 into `DIETER_HOME/runtime/tools/strix`),
    `strix-runtime.mjs`, `runner.mjs` dispatch (`DIETER_STRIX_BIN`), `capabilities.mjs` `upsertSubagent`/`replaceTasks`,
    and `DOCKER_HOST`/`DOCKER_CONFIG`/`DOCKER_CONTEXT`/`DOCKER_TLS_VERIFY`/`DOCKER_CERT_PATH` worker pass-through env
    vars.
  - Fast file transfers over WebRTC and the relay (`93b8822e`, v0.6.443), gateway session extension on use (`f5eeb246`)
    and mobile re-sign-in after expiry (`0b757997`), iOS attention badges and APNs push (`8732ae0d`, disabled until App
    Store profile in `082f6e80` v0.6.445), and Go 1.26.11 / 1.27.4 toolchain bump (`0669f6aa`).
  - Release assets and gateway compatibility: v0.6.445–446 and v0.7.447–449 keep the same 19 asset names (`SHA256SUMS` +
    sigstore bundle; v0.6.443–444 and v0.7.450 are tags without a GitHub release). `deploy/gateway/compatibility-policy.json`
    raises `minimumClientVersion` and `minimumDaemonVersion` to `0.7.449` (after `0.7.0-dev.0` in v0.7.447).
  - Apps (Kotlin): `UsageScreen.kt` and `core/quotas/*` are replaced by `mobile/providers/*` (`ProvidersScreen.kt`,
    `ProviderAccountScreen.kt`, `AddAccountSheet.kt`, `SignInSheet.kt`, `UsageMeters.kt`) and `core/providers/*`;
    `InboxScreen.kt` (`76442c3e`, v0.7.448) drops the count tiles and usage strip in favour of dense two-line rows
    (`Needs you`, `Running`, `Pinned`, `Recent`), and `f2f27576` (v0.7.450) applies that list style across mobile list
    pages (`Lists.kt`); `Platform` gains optional `attentionNotifications` and `folders` parameters; `core/folders/Nfc.kt`
    adds `internal expect fun nfc(value: String): String` (with `.jvm.kt` and `.apple.kt` actuals); `NativeSurfaces.kt`
    adds `expect` functions `rememberFolderFileOpener`, `rememberFolderLocationPicker` and
    `rememberNotificationPermission`.

  Nothing to adapt with what is pinned (`0.6.431-antigravity.11`, submodule `b1ce51fb`). The fork has no release newer
  than `antigravity.11` and `web-addon` has not moved. The `ProjectEditor.kt` override is unchanged upstream. The pinned
  runtime sources still match `RUNTIME_SOURCES_SHA`, and `patch_runtime.py` applies to them. Follow-ups:
  - `fork-release` needs upstream **v0.7.450** (`f2f27576`) merged, then tag `v0.7.450-antigravity.12`. Besides the
    v0.6.442 conflicts noted below, expect conflicts in `api/proto/dieter/v1/{cards,common,schedules}.proto` (and
    generated `.pb.go`/Swift files), `internal/api/{conversation_snapshots,convert}.go`,
    `internal/cli/{rpc_schedules,help_contract_test}.go`, `internal/engine/conversation_creation.go`,
    `internal/engine/scheduler/scheduler.go`, `internal/harness/catalog/harnesses.yaml`,
    `internal/harness/harness_test.go`, `internal/harness/runtime/capabilities{.mjs,.test.mjs}`, `internal/model/model.go`,
    `internal/prompt/prompt.go`, `internal/replication/peersync/peer.go`, `internal/store/kanban/{cards,shared_test}.go`,
    `internal/store/schedules/schedules.go` and `site/content/docs/harnesses.md`. All of `patch_runtime.py`'s anchors in
    `local-sandbox.mjs` and `runner.mjs` (except the fork-only `host-questions.mjs` import) still match `f2f27576` once.
  - Provider accounts and `claude_code_oauth_token` on the bump: in v0.7.447+, `accounts.Service`
    (`internal/daemon/services.go`, `internal/engine/accounts/turns.go`) resolves every `claude-code` and `codex` turn
    through provider accounts, masks `CLAUDE_CODE_OAUTH_TOKEN` on `host`/`claude-code` logins, and only adopts
    `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` from the environment (ignoring `CLAUDE_CODE_OAUTH_TOKEN`). Either teach
     the fork's `accounts` engine to adopt `CLAUDE_CODE_OAUTH_TOKEN` as an environment account, or map
    `claude_code_oauth_token` in `Setup.kt`. Update `Board.kt`, `Usage.kt` and `dieter/operator/quota_claude_token.mjs`
    together with the daemon bump: `dieter quota list` is replaced by `dieter provider list --format json` (and
    `provider usage`), and `quota-claude.mjs` is replaced by `usage-claude.mjs` (`{loggedIn, identity, usage}` output).
    Also check whether `dieter/codex/**` should join `backup_exclude` in `dieter/config.yaml`.
  - Moving the submodule (`web-addon` <- `f2f27576`, together with the daemon bump) needs:
    1. `MobileApp` in `jvmSharedMain` resolved (as noted below);
    2. `web/build.gradle.kts` updated where it filters `UsageScreen.kt` (deleted in `b425c255`, replaced by
       `com/getdieter/mobile/providers/UsageMeters.kt` calling `ProviderExports.resetText`), and `Antigravity.kt` /
       `Extensions.kt` adapted from `core.quotas.Quotas`, `ToolPage.USAGE` and `usageBars` to `ProviderExports.resetText`
       and `ToolPage.PROVIDERS`;
    3. `web/src/wasmJsMain` actuals for `com.getdieter.core.folders.nfc(value: String): String` (`value.normalize("NFC")`)
       and for `NativeSurfaces.kt`'s `rememberFolderFileOpener`, `rememberFolderLocationPicker` and
       `rememberNotificationPermission`;
    4. `web-addon`'s own `WorkspaceReview.kt` and `ProjectChanges.kt` commits (`8eb7fae3`, `5d4f75e6`) reconciled with
       upstream's live conversation Changes (`9dcd1707`), which now implements project-directory Changes and per-file
       line counts upstream.
  - External gateways running 0.7.447+ enforce `minimumClientVersion` / `minimumDaemonVersion` (`0.7.449` at `f2f27576`)
    and refuse 0.6 daemons and web hosts until the add-on bumps (embedded gateway mode sets `0.0.0-dev.0` in
    `tools/embedded-gateway/main.go` and is unaffected).
- **2026-11-09**, screened through upstream **v0.6.442** (`0b2bac3a`) and main (`e93d4f37`), from main `d748c049` (the
  screen of 2026-11-02): 40 commits. Relevant ones:
  - Agent channels are on for every project and card (`ebf8330c`). `dieter channel settings` and the
    `GetChannelSettings`/`SetChannelSetting` RPCs are gone, `enabled` is reserved in `channels.proto`, `channel list` drops its
    ENABLED column, the `experimental` KV namespace is no longer validated, and every card's prompt carries the channel
    instructions.
  - Opt-in cleanup of archived worktrees (#194): `project update --delete-worktrees-on-archive=true|false`,
    `Project.delete_worktrees_on_archive`, a cleanup loop in the scheduler (every minute), longer `card archive` help.
  - Peer sync goes on past records whose owner proof does not verify (#196); `peer status` lists them as `held`.
  - The change stream sends at most one data frame per 250 ms.
  - Harness runtime: dependency bumps (`@ai-sdk/harness` 1.0.173, `claude-agent-sdk` 0.3.317, `@openai/codex` 0.166.0,
    omp 18.16.0, …), a new `acp-runtime.mjs` wrapper for `createACP`, prettier reformatting of `capabilities.mjs`, and
    managed Bun 1.4.4.
  - Release assets of v0.6.437–442 have the same names as before (19 assets, `SHA256SUMS` + sigstore bundle). The
    gateway changes only in `deploy/gateway` base images.
  - Apps (Kotlin): channel settings removed from the core and the UI (`ChannelSettings.kt`, `SLICE_CHANNEL_SETTINGS`),
    sidebar projects ordered by activity with manual rearrangement (`ProjectOrdering`, navigation KV), the mobile Inbox leads
    with running work and swipeable cards, queued sends pinned above the input (`QueuedMessageState`), an "Archive storage"
    switch in project settings, workspace hyperlinks and image previews; Wire 7.1.2, serialization 1.12.0, Gradle 9.8.2,
    `-Xmx5g`. iOS, Mac and Android: screen sharing fixes, Mac CPU fix, dSYMs, Maestro flows (noted only).

  Nothing to adapt with what is pinned. The add-on calls none of the removed commands, RPCs or fields (no `channel` CLI, no
  `experimental` KV). `Harness.kt` reads the Bun version from the archive's manifest. The `ProjectEditor.kt` override is
  unchanged upstream. The fork has no release newer than `antigravity.11`. The pinned runtime sources still match
  `RUNTIME_SOURCES_SHA`, and `patch_runtime.py` applies to them. Follow-ups:
  - `fork-release` needs upstream v0.6.442 (or main) merged, then tag `v0.6.442-antigravity.12`. Expect conflicts in
    `capabilities.mjs` (upstream's reformatting and Codex provider overrides vs the fork's task plans and channel write
    scope), `internal/engine/channels.go` and `internal/prompt/prompt.go` (channels always on vs the fork's write-scope
    enforcement), `internal/cli/rpc_cards.go` and `help_contract_test.go`. All of `patch_runtime.py`'s anchors except the
    fork-only `host-questions.mjs` import still match upstream main's `runner.mjs` and `local-sandbox.mjs`. The adapter imports only
    `HarnessCapabilityUnsupportedError`, which `@ai-sdk/harness` 1.0.173 still exports. On the bump, record
    `RUNTIME_SOURCES_SHA`, the archive checksums in `Harness.kt`, and the adapter hash if the fork's adapter changes.
  - Bump the daemon and move the submodule together. With the submodule at `b1ce51fb`, a v0.6.442 daemon no longer sends
    `enabled`, so the page shows every channel as "Off" and its channel switches in Settings fail (RPCs gone). A moved
    submodule against `antigravity.11` has no switch left, while that daemon keeps channels off until switched on.
  - The submodule (`web-addon`) needs upstream `e93d4f37` merged, on top of its own Changes line counts and
    `WorkspaceReview` commits. It is still blocked by `MobileApp` living in `jvmSharedMain` (`MaterialShell` is internal to
    `commonMain`), so `web/.../Main.kt` needs a host of its own first.
  - Decide after the bump whether DOCS.md should describe archive cleanup. It is off by default, and when switched on it
    permanently deletes ignored files and build output of archived worktrees under `/data/projects`.
- **2026-11-02**, screened through upstream **v0.6.436** (`2d0a8d85`) and main (`d748c049`), from v0.6.431
  (`7f2afded`, the base of the pinned `0.6.431-antigravity.11`): 13 commits, all app-side (Android, iOS, Mac apps,
  Maestro e2e, CI timeouts, release notes). Nothing changed in `internal/` (CLI, daemon, harness runtime, registry),
  `cmd/` or `api/proto`. The release assets of v0.6.432 and v0.6.433 have the same names as before (v0.6.434–436 are
  tags without a GitHub release). Nothing to adapt. The fork has no release newer than `antigravity.11`, and
  `fork-release` does not need a rebase for the daemon (it would only change the version string). The pinned
  runtime sources still match `RUNTIME_SOURCES_SHA`, and `patch_runtime.py` applies to them. For the page (the `fork`
  submodule, at `b1ce51fb`, which contains upstream v0.6.432): 0.6.433 put Mail beside Inbox in the bar and made the fold's
  panes draggable away, 0.6.434–435 added Android's desktop shell (DeX: sidebar, every lane side by side, Ctrl+K/N/B/W,
  Settings → Appearance → Interface). v0.6.432's navigation-edit delivery (`22701f9c`) and terminal routing fix
  (`1c9a4fe6`) are already in the submodule. Moving the submodule past `d748c049` breaks `web/`: `MobileApp`
  (with the desktop shell) moved to `jvmSharedMain`, and `commonMain` keeps only `MaterialShell`/`RouteContent`, so
  `web/src/wasmJsMain/.../Main.kt` would need a host of its own.
- **2026-10-31**, fork release **`0.6.431-antigravity.11`** (`23f605ef`): `antigravity.10` plus Antigravity parity with
  Claude Code (MCP host tools proxy `antigravity-host-tools.mjs`, per-step usage, `/compact`, lost-conversation
  recovery, `agy models` discovery, model `contextWindow` / `defaultEffort` / `efforts`, `invoke_subagent` lifecycle,
  `board_task_plan` host tool, channel write-scope & `PreToolUse` hook enforcement, and live tool UI summaries).
  Tarball and harness archive checksums from its `SHA256SUMS`, verified with cosign. Bumped in 0.6.431.36.
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
