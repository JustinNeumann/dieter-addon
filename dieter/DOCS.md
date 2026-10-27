# Dieter add-on

Runs the [Dieter](https://github.com/dbpprt/dieter) daemon and the Dieter page inside Home Assistant.
The page opens from the sidebar entry **Dieter** (Ingress: HA login, Nabu Casa and the Companion app
work, nothing else is needed on the phone). The sidebar entry is the add-on's "Show in sidebar"
switch (`ingress_panel`), enabled on install.

## Installation

1. Settings → Add-ons → Add-on store → ⋮ → **Repositories** → add
   `https://github.com/JustinNeumann/dieter-addon`.
2. Install **Dieter** (the Supervisor pulls a prebuilt image for aarch64 or amd64), fill in the
   options below (at least one provider credential), start it.
3. Optional: the Home Assistant package for sensors and push, see *Home Assistant side*.

The add-on's slug is `4d8f2a84_dieter` when the repository was added with exactly the URL above
(the prefix is a hash of the URL; a trailing slash or `.git` yields another one). It appears in the
add-on page's URL (`/hassio/addon/<slug>`) and is needed for the package below. A local copy in
`/addons/dieter` has the slug `local_dieter`.

## What runs in the container

The bridge, the daemon and every agent run as the unprivileged user `dieter` (uid 1000); `/data` is
handed to that user at start. Claude Code refuses its allow-all mode under root, and agents should
not be root anyway.

| Piece | Where |
|---|---|
| `dieter daemon start --addr 127.0.0.1:4242` | store `/data/dieter`, started and restarted by the bridge |
| bridge `/opt/bridge/dieter_bridge.py` | `0.0.0.0:8099` (Ingress port); REST + SSE API, serves the page, `/api/ha` for sensors |
| harness runtime (the daemon's worker scripts + Bun, ≈ 800 MB) | staged by the bridge on first start into `/data/dieter/harness-staged/<version>` (sha256-pinned download; the daemon's own download timed out on a Raspberry Pi) and passed as `DIETER_HARNESS_RUNTIME_DIR` |
| `claude` 2.1.311, `codex` 0.163.0, `agy` 1.4.8 (Antigravity) | `/usr/local/bin`, all pinned by checksum; their configs live under `/data` (HOME) |
| runtime sources of the same build (`/opt/dieter-runtime`, from the fork tag, checksum-verified) | `runner.mjs` + adapters incl. **Antigravity**, laid over the archive's `node_modules` |
| project checkouts | `/data/projects/<name>` |

Reads and writes to the bridge are accepted only from the Supervisor network (Ingress forwards
logged-in HA users; HA core calls `http://<slug with - instead of _>:8099/api/ha` and the
rest_commands). The container runs under an AppArmor profile (`apparmor.txt`): Docker's default
capability set, no mounts, no writes to kernel interfaces; file access is unrestricted because the
agents run arbitrary tooling under `/data`.

## Options

| Option | Meaning |
|---|---|
| `git_user`, `git_email` | commit identity for the agents (written to `/data/.gitconfig`) |
| `github_token` | token for github.com (fine-grained with *All repositories* or the repos you want, permission Contents read/write; or classic `repo`); stored in `/data/.git-credentials` and used for every clone and push |
| `claude_code_oauth_token`, `openai_api_key`, `antigravity_token` | provider credentials for the harnesses, see below |
| `projects` | optional list of `{name, git_url, branch}` cloned and registered on start. Any number of repositories can also be added from the page: Projects → **+** → *Add repository* (Git URL, optional name and base branch); *New folder* creates an empty Git repository `/data/projects/<name>` without a remote and registers it (an existing folder there is adopted) |
| `log_level` | bridge log level |

Agents work in `/data/projects/*` on branches; publishing means push branch + pull request. The
add-on has no access to `/config`.

## Home Assistant side

The add-on works on its own from the sidebar. The repository ships an optional package that adds:

- `sensor.dieter` (online/offline, attributes: lanes, running and review titles, quota, machine,
  last finished turn, projects, daemon) polled from `/api/ha` every 30 s, plus template sensors
  *running tasks*, *waiting for you*, *Claude week*, CPU/GPU;
- `rest_command.dieter_create_task`, `dieter_send_message`, `dieter_move_card`, `dieter_focus` to
  drive Dieter from automations;
- an automation that pushes to the Companion app when the bridge fires `dieter_turn_finished`
  (an agent ended its turn) or `dieter_card_review` (a card moved to Review). The tap opens the
  Dieter panel; because Ingress URLs carry no deep link, the automation first tells the bridge which
  card to show (`POST /api/focus`, remembered for 15 minutes).

Install: copy
[`homeassistant/packages/dieter.yaml`](https://github.com/JustinNeumann/dieter-addon/blob/main/homeassistant/packages/dieter.yaml)
to `<config>/packages/dieter.yaml`, make sure `configuration.yaml` loads packages
(`homeassistant: {packages: !include_dir_named packages}`), set the host name / slug if yours differs
from `4d8f2a84_dieter` and the `notify.mobile_app_…` service of your phone, then restart Home
Assistant. An optional status dashboard is in
[`homeassistant/dashboards/dieter.yaml`](https://github.com/JustinNeumann/dieter-addon/blob/main/homeassistant/dashboards/dieter.yaml).

## Provider login

The harnesses run inside the container, so they need their own credentials:

- **Claude Code**: either sign in on the Dieter page (**Tools → Usage → Sign in**: the add-on runs
  `claude auth login`, you open the link, allow Claude Code and paste the code back), or run
  `claude setup-token` on a machine where Claude Code is logged in and paste the long-lived token into
  the option **Claude Code token** (`CLAUDE_CODE_OAUTH_TOKEN` in `/data/dieter.env`, passed with
  `--env-file`). The page sign-in is the fuller one: it is stored in `/data/.claude/.credentials.json`,
  renewed by the bridge, and it can read your quota, which a setup-token cannot (the usage API answers
  401 for it, so nothing is shown under Usage with a token). When both exist, the sign-in wins.
- **Codex**: paste an OpenAI API key into **OpenAI API key** (`OPENAI_API_KEY`).
- **Antigravity**: the CLI keeps its sign-in in a small JSON file. On a computer where `agy` is
  signed in, print it and paste the output into **Antigravity credentials**:

  ```sh
  cat "$HOME/.gemini/antigravity-cli/antigravity-oauth-token"
  ```

  The bridge installs it in the add-on's own home so `agy` finds it there. The Antigravity harness runs one headless
  `agy` per turn with `--dangerously-skip-permissions` (Dieter's allow-all mode), continued with
  `--conversation`. Default model Gemini 3.8 Flash (High); Gemini 4 Argon, Claude Opus/Sonnet 5.5 and
  GPT-OSS are selectable. Not available through the CLI: host tools (present_content, processes),
  suspend/continue and compaction.

Restart the add-on after changing tokens.

## Questions from the agent and attachments

Claude Code may ask the user a question with options (its AskUserQuestion tool). Dieter's daemon has
no channel for that, so the fork's harness worker relays the question to the bridge
(`DIETER_QUESTIONS_URL`), the card shows it with its options and a free-form field, the Inbox lists the
card under "Waiting for your answer", and Home Assistant receives the event `dieter_question`
(`card_id`, `title`, `question`, `count`) for a notification. The answer, or a decline, continues the
turn. Unanswered questions expire after a day; cancelling the card cancels the question.

Messages, new tasks and new chats take attachments: images and files, up to four, 20 MB each. The page
uploads them to the bridge (`/data/dieter/uploads`, removed once sent) and passes them to the CLI with
`--attach`; the harness saves them in the conversation's workspace and tells the agent where they are.
Images up to about 450 KB show as previews in the conversation, larger ones as a pill with the name.

## SSH key of the add-on

On its first start the add-on generates an ed25519 key pair in `/data/.ssh` (it survives updates and
is never part of the image or this repository). **Tools → Machines** shows the public key with a copy
button and a connection test. Add the public key to `~/.ssh/authorized_keys` on the computers Dieter
should reach, for example a Git remote over SSH or another machine with the Dieter daemon; `git` and
`ssh` inside the container pick the key up through `/data/.ssh/config` (first connections record the
host key, later ones verify it). The test runs `ssh -o BatchMode=yes <target> uname -n` and prints
the host name on success.

## Backups

`/data` is backed up with the add-on, except the harness runtime and the project checkouts
(both are re-downloaded / re-cloned).
