# Changelog

Version = bundled Dieter release + add-on revision (`0.6.429.1` = Dieter 0.6.429, add-on revision 1).

## 0.6.429.24 (2026-10-28)

- Bundled Dieter is now the fork release `0.6.429-antigravity.4`: after **Re-enroll** (a new enrollment under
  the same account) the daemon registers its checkouts again under its new daemon ID at the first peer sync,
  so new tasks and the apps find the add-on's projects again instead of failing with "project has no
  checkout on the target machine". Conversations and schedules of the previous daemon ID stay as read-only
  history (the Re-enroll confirmation now says so). Until that first sync the daemon refuses writes instead
  of publishing records no peer would accept.
- The harness runtime archive of this release is unchanged, so the bridge takes over the already staged
  runtime instead of downloading it again (~775 MB).

## 0.6.429.23 (2026-10-28)

- The gateway sign-in dialog also shows the link as text with a **Copy link** button, for app views where
  the "Open sign-in page" button (a new-tab link) does nothing.

## 0.6.429.22 (2026-10-28)

- Fix: **Re-enroll** failed with `type object 'Enroll' has no attribute 'unenroll'`; the method had been
  added to the wrong class.

## 0.6.429.21 (2026-10-28)

- Tools → Machines → Gateway offers **Re-enroll** when another device has removed the add-on's daemon from
  the account (Devices shows "Removed"). The daemon still held its gateway credential, so the page showed it
  as enrolled and no **Enroll** button. Re-enroll runs `dieter daemon unenroll` (projects and conversations
  stay) and shows the Enroll form again; sign in with GitHub, then approve the new pairing code.

## 0.6.429.20 (2026-10-28)

- Tools → Settings now has a **Title model** choice for tasks and chats created without a title:
  *OpenAI* (default, Dieter's auto-title as before) or *Ollama (HA)*, a short summary of the task from a
  local model through Home Assistant's AI Task. The bridge finds the Ollama integration's AI Task entity
  by itself (`integration_entities('ollama')`); the task is saved at once and renamed in the background,
  and a title changed in the meantime is kept. Stored in `/data/bridge_settings.json`.

## 0.6.429.19 (2026-10-28)

- Devices no longer lists removed devices. The roster keeps every revoked device (flag `revoked`), so an
  app that was paired again after a reinstall showed up once per pairing, with a Remove button that could
  only fail. The list now shows the active devices and how many removed ones it leaves out; two active
  devices with the same name are told apart by the end of their device id.

## 0.6.429.18 (2026-10-28)

- Oh My Pi model discovery no longer re-downloads the harness runtime. The daemon runs OMP with its managed
  Bun (`/data/dieter/runtime/tools/bun/1.4.3`) and, when that is missing, fetches the whole 775 MB runtime
  archive just to get it, ignoring `DIETER_HARNESS_RUNTIME_DIR`. On the Pi that never finished within the
  10 min discovery limit, so it started over every round (about every 6 minutes), around the clock: 43 GB
  received in 5 hours, plus a 775 MB leftover download. At startup the bridge now installs the Bun from the
  staged archive the way the daemon does (postinstall, `bun --version` check, rename under the daemon's
  install lock) and removes leftovers of interrupted daemon attempts.
- Staged harness runtimes of earlier versions (`/data/dieter/harness-staged/<version>`, 2.2 GB each) are
  removed once the current one is staged.

## 0.6.429.17 (2026-10-28)

- Edit tasks in the UI: the card menu has an "Edit…" entry (as in the Dieter app). It changes the title, and
  the task of a Todo card that has not been started; a started card keeps its task, a chat can only be renamed.

## 0.6.429.16 (2026-10-28)

- Antigravity usage: the quota is shown again when the daemon is enrolled in a gateway. The bridge only probed
  `agy` when the daemon's `quota list` was empty, and the gateway's list (Claude, Codex) never contains
  Antigravity, so the Inbox bar said "no quota data". Local probes now fill in every provider the gateway lacks
  (Claude Code too), each cached for 5 minutes.
- Per-model usage for Antigravity: agy's quota groups (Gemini Flash/Pro, Claude Opus/Sonnet and GPT-OSS,
  Gemini 4 Argon) are read from `agy --print=/quota --output-format json` and matched to the models. The model
  pickers (New task, New schedule, a conversation's agent) show each model's remaining share, in warning color
  below 20 % and with the reset time at 0 %. Usage lists the models each window covers, and the Inbox bar
  follows the group of the model a new task preselects instead of the tightest group (an exhausted Argon week
  no longer turns the whole bar to 0 %).

## 0.6.429.15 (2026-10-28)

- Dieter from fork release `v0.6.429-antigravity.3`: the daemon reports its version as
  `0.6.429+antigravity.3` (build metadata) instead of the pre-release `0.6.429-antigravity.N`, which a
  gateway with minimum 0.6.429 refused ("Dieter client update required"); after enrolling, the add-on's
  daemon applied that refusal to the bridge as well ("Bridge offline"). A gateway policy at 0.6.429 now
  admits the add-on without exceptions.

## 0.6.429.14 (2026-10-27)

- Gateway enrollment: Tools → Machines → Gateway enrolls the add-on's daemon with your Dieter gateway
  (`dieter daemon enroll`, sign-in link shown, daemon restarted afterwards), so the Dieter apps list
  the add-on as a machine with its projects. Options `gateway_url`, `gateway_host_ip` (hosts entry for a
  name the container cannot resolve, e.g. Tailscale) and `machine_name`. Bridge `/api/gateway`.

## 0.6.429.13 (2026-10-27)

- Devices: Tools → Devices shows the trust state of the add-on's daemon, the account's devices, the
  devices waiting for approval, and a field for the code a new app or machine shows (`dieter device
  status/requests/approve/revoke`, bridge `/api/devices`). Note: an add-on daemon that is not
  enrolled in a Dieter account has no roster, so the page says so and codes must be approved on an
  enrolled machine or in the app.

## 0.6.429.12 (2026-10-27)

- Antigravity: a card with an effort other than its model's own level (e.g. Gemini 3.8 Flash (High) with
  "Extra high") no longer fails with `--model … conflicts with --effort=…`. agy's model ids already carry the
  level (`-low`/`-medium`/`-high`) and agy rejects any other `--effort`, so the runtime now omits the flag for
  those models. Applied as a build-time patch (`patches/patch_runtime.py`) on the pinned fork sources until the
  fork release contains it.

## 0.6.429.11 (2026-10-27)

- The page remembers the most recently used project (opened, a board or card of it opened, a task
  created in it; stored in the browser): it is listed first under Projects, preselected in New task
  and New schedule, and opened when the Projects tab is tapped or the page starts on a wide screen.

## 0.6.429.10 (2026-10-27)

- Files: Tools → Files (or Workspace → Files on a project, Files in a conversation's menu) browses the
  checkout: folders, file sizes, a dotfile toggle; text files are shown, images displayed, other
  binaries and files above 5 MB described, every file downloadable (`dieter file list/stat/read`,
  bridge `GET /api/project/<id>/files|file`, `GET /api/card/<id>/files|file`). Until now the page
  pointed to the Dieter app.
- Schedules: Tools → Schedules (or Workspace → Schedules on a project) lists the daemon's recurring
  tasks, creates one (name, prompt, cron with presets and a live preview of the next times, timezone,
  project, board, agent, start or Todo, worktree or directory, enabled, vault and mail), runs it now,
  pauses and resumes it, shows the occurrence history with a link to each card, and deletes it
  (`dieter schedule …`, bridge `/api/schedules`). Until now the pane was a placeholder.

## 0.6.429.9 (2026-10-27)

- The Supervisor CLI `ha` and `hassio_api` are gone again (0.6.429.8 gave the agents add-on, restart
  and host management). `hass-cli` stays; HA events for the notifications and hass-cli only need the
  core proxy (`homeassistant_api`). The note in `/data/.claude/CLAUDE.md` is replaced while it is still
  the add-on's own text.

## 0.6.429.8 (2026-10-27)

- Home Assistant CLIs for the agents: `ha` 5.6.0 (Supervisor CLI, `hassio_api: true`, role manager)
  and `hass-cli` 1.0.0 (HA core REST API) are in the image; the bridge hands the workers
  `SUPERVISOR_TOKEN`, `HASS_SERVER` and `HASS_TOKEN`, and writes a note for Claude Code to
  `/data/.claude/CLAUDE.md` once. Base image Debian trixie (Python 3.13 for hass-cli).

## 0.6.429.7 (2026-10-27)

- Questions reach the bridge: the daemon forwards only whitelisted variables to its harness workers,
  so `DIETER_QUESTIONS_URL` is now named in `DIETER_HARNESS_ENV`. In 0.6.429.6 every question was
  declined at once.

## 0.6.429.6 (2026-10-27)

- Dieter from fork release `v0.6.429-antigravity.2`: the harness worker relays the agent's questions
  to the bridge, so Claude Code's AskUserQuestion is answered on the page instead of hanging the turn.
- Home Assistant package: the notification automation also fires on the event `dieter_question`.

## 0.6.429.5 (2026-10-27)

- The add-on has its own SSH key (ed25519, generated in `/data/.ssh` on first start). Tools → Machines
  shows the public key with a copy button and a connection test (`ssh -o BatchMode=yes user@host`);
  `openssh-client` is in the image, so Git remotes over SSH and other machines work with that key.
- Projects → + → New folder creates an empty Git repository under `/data/projects/<name>` (an existing
  folder there is adopted) and registers it as a project; until now only Git URLs could be added.
- Questions from the agent (Claude Code's AskUserQuestion) are answered on the page. Until now such a
  turn hung with the raw tool call shown. The fork release `v0.6.429-antigravity.2` relays the question
  to the bridge (`/api/questions`), the card shows it with its options and a free-form field, the Inbox
  lists the card under "Waiting for your answer", HA gets the event `dieter_question`, and the answer
  (or a decline) continues the turn.
- Attachments: images and files can be added to a message and to a new task or chat on the page
  (up to four, 20 MB each; uploaded to the bridge and handed to the CLI with `--attach`). Sent images
  show as previews in the conversation.
- Bridge logs why the Claude quota probe returned nothing; no setup-token fallback after a page sign-in.
- Wide screens: the default board shown at start no longer replaces a project or board opened in the
  first moment after loading.

## 0.6.429.4 (2026-10-27)

- Tasks and chats of every project are listed: the bridge asked the CLI for cards without a project, and
  `dieter card list` then answers for one project only, so cards created for any further project were
  created and run but never shown on the page (nor counted in `/api/ha`). The state is now collected per
  project and merged.
- Changes (conversation pane and Tools → Changes) show the added and removed lines per file; the CLI's
  change list has no counts, so the bridge counts them from each file's patch (at the changeset's
  revision) and the page no longer prints +0 −0 everywhere.

## 0.6.429.3 (2026-10-27)

- Inbox usage strip: one bar per configured LLM backend (Claude Code, Antigravity, OpenAI), showing the
  tightest window by name; backends without quota data show an empty bar instead of disappearing.
- Claude quota after a page sign-in no longer waits for a stale 5-minute cache (a poll that started before
  the sign-in could refill it).

## 0.6.429.2 (2026-10-27)

- Dieter now comes from the fork's own release `v0.6.429-antigravity.1` (github.com/JustinNeumann/dieter,
  workflow "Fork server release": daemon tarballs + harness runtime archives, SHA256SUMS signed keyless).
  The daemon reports that version and embeds the Antigravity harness and catalog; the vendored
  `runtime/` folder and the catalog override are gone. Runtime sources are fetched from the same tag at
  image build time and checksum-verified.
- Claude Code: sign in on the page (Tools → Usage → Sign in). The bridge runs `claude auth login`, keeps
  the token fresh and shows the real quota; a setup-token cannot read the usage API. Providers labelled.
- Antigravity credentials option text without a bare path.

## 0.6.429.1 (2026-10-27)

- Installable as a repository add-on: prebuilt multi-arch image `ghcr.io/justinneumann/dieter` built and
  signed by GitHub Actions (`image:` in config.yaml); the Supervisor pulls instead of building on the device.
  `build.yaml` folded into the Dockerfile (multi-arch HA base image).
- Antigravity CLI pinned to 1.4.8 (SHA512-verified download instead of `curl | bash` of the latest).
- AppArmor profile (Docker's default capability set, no mounts, no writes to kernel interfaces).
- Ships the Home Assistant package (sensors, rest_commands, push automation) and status dashboard in
  `homeassistant/`; DOCS explains the add-on slug/hostname for repository vs. local installs.
- No personal defaults: `git_user` empty, `url` points to this repository.

## 0.6.429 (2026-10-27)

- Antigravity harness (fork JustinNeumann/dieter, branch antigravity): forked runtime sources staged over the archive, catalog override, `antigravity_token` option.
- Add-on icon and logo.
- First version: Dieter 0.6.429 daemon + bridge behind Ingress, Node 22.23.4, git, tmux, Claude Code 2.1.311, Codex 0.163.0, Antigravity CLI.
- The bridge stages the harness runtime (sha256-pinned) and hands it to the daemon (DIETER_HARNESS_RUNTIME_DIR).
- Options for git identity, GitHub token, provider tokens (Claude Code, OpenAI) and projects (cloned and registered on start).
- Repositories can be added from the page (Projects → + → Add repository, `POST /api/project`), any number.
- HA events `dieter_turn_finished` / `dieter_card_review`; `/api/focus` for push deep links.
