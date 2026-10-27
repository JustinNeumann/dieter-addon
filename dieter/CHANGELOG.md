# Changelog

Version = bundled Dieter release + add-on revision (`0.6.429.1` = Dieter 0.6.429, add-on revision 1).

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
