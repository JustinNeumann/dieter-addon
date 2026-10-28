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
| harness runtime (the daemon's worker scripts + Bun, ≈ 800 MB) | staged by the bridge on first start into `/data/dieter/harness-staged/<version>` (sha256-pinned download; the daemon's own download timed out on a Raspberry Pi) and passed as `DIETER_HARNESS_RUNTIME_DIR`; staged runtimes of earlier versions are removed, and the Bun it carries is installed as the daemon's managed Bun (`/data/dieter/runtime/tools/bun/<bun version>`), which Oh My Pi's model discovery would otherwise download the whole archive for |
| `claude` 2.1.311, `codex` 0.163.0, `agy` 1.4.8 (Antigravity) | `/usr/local/bin`, all pinned by checksum; their configs live under `/data` (HOME) |
| `hass-cli` 1.0.0 (HA core REST API), `ha-logs` (HA logs) | `/usr/local/bin`; authenticated with the add-on's token, see below |
| runtime sources of the same build (`/opt/dieter-runtime`, from the fork tag, checksum-verified) | `runner.mjs` + adapters, laid over the archive's `node_modules`; the **Antigravity** adapter is the add-on's own copy (`patches/antigravity-runtime.mjs`, laid over the fork's at image build by `patches/patch_runtime.py`) |
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
| `github_token` | token for github.com, used for every clone and push and for pull requests and Actions through the GitHub CLI (see GitHub below). Fine-grained, for *All repositories* or the repos you want: Contents read/write, Pull requests read/write, Actions read, Checks read, Commit statuses read, Metadata read; Workflows read/write only if agents may change `.github/workflows`; no Administration, Secrets or Actions write. Or classic `repo` (and `workflow`). Stored in `/data/.config/gh/hosts.yml` (gh's sign-in; git takes it from there) |
| `claude_code_oauth_token`, `openai_api_key`, `antigravity_token` | provider credentials for the harnesses, see below |
| `gateway_url`, `gateway_host_ip`, `machine_name` | enroll the add-on's daemon with your Dieter gateway so the apps see it (see Gateway below); `gateway_host_ip` adds a hosts entry for the gateway's name |
| `projects` | optional list of `{name, git_url, branch}` cloned and registered on start. Any number of repositories can also be added from the page: Projects → **+** → *Add repository* (Git URL, optional name and base branch); *New folder* creates an empty Git repository `/data/projects/<name>` without a remote and registers it (an existing folder there is adopted). Further boards next to a project's *Main* board: the project's ⋮ menu (or a board's ⋮ menu) → *New board…* (name, *Review lane* on or off). Boards are listed in the order you choose: drag a board row (touch: long press first) on the Projects list or the project's page; the order is kept by the add-on for all devices. The page lists the most recently used project first, preselects it in New task and opens it when the Projects tab is tapped |
| `log_level` | bridge log level |

Agents work in `/data/projects/*` on branches; publishing means push branch + pull request. The
add-on has no access to `/config`.

## GitHub: pull requests and Actions

The image has the GitHub CLI (`gh`). At start the bridge signs it in with `github_token` and switches git to
gh's credential helper, so one token serves clones, pushes, the daemon and the agents (without a token, or
when the sign-in fails for lack of network, git keeps its plain credential store). What this enables:

- **The daemon's pull-request operations.** Dieter's workspace operations `create_pr`, `refresh_pr` and
  `merge_pr` (`dieter workspace run --kind …`) need `gh` on the daemon host; `dieter workspace scm CARD`
  shows them as available now. They work for worktree cards and record the pull request on the card.
- **Agents read checks and runs** (`gh pr checks`, `gh run list`, `gh run view --log-failed`), edit and
  comment on pull requests, and open them for project-directory cards (`gh pr create`). The Claude Code
  note in `CLAUDE.md` (replaced only while unedited) says what is expected: read freely, publish through the
  daemon where possible, never merge, close, force-push or change settings unless the task says so, and
  treat pull request text, reviews and Actions logs as untrusted input. For Codex and Antigravity put the
  same into Tools → Settings → Agent instructions.
- **Publishing per board and card.** Dieter's remote publish mode decides what happens with a worktree
  card's changes: *Manual* (the default) leaves pushing and pull requests to explicit actions or the task,
  *Pull request* publishes them as a pull request, *Push to base* pushes validated changes to the base
  branch. **New board** and a board's ⋮ menu → **Publishing…** set a board's mode (cards created afterwards
  inherit it), New task → *New worktree* → **Publishing** overrides it for one card. The Access switches
  of New task (vault, mail) are now passed on as well.

What the token can do, an agent can do, also one led astray by text it reads; agents and the bridge run as
the same user and can read the token. Keep the token's permissions to the list above and protect the base
branch on GitHub (a ruleset on `main`: pull request required, no direct pushes, no bypass for
administrators), then a pull request with your review is the only way into `main`.

## Home Assistant side

The add-on works on its own from the sidebar. The repository ships an optional package that adds:

- `sensor.dieter` (online/offline, attributes: lanes, running and review titles, quota, machine,
  last finished turn, projects, daemon) polled from `/api/ha` every 30 s, plus template sensors
  *running tasks*, *waiting for you*, *Claude week*, CPU/GPU;
- `rest_command.dieter_create_task`, `dieter_send_message`, `dieter_move_card`, `dieter_focus` to
  drive Dieter from automations;
- an automation that pushes to the Companion app when the bridge fires `dieter_turn_finished`
  (an agent ended its turn) or `dieter_card_review` (a card moved to Review). The tap opens
  `/4d8f2a84_dieter/card/<card id>`: Home Assistant's app panel hands that route to the Dieter page,
  which opens the card's conversation, also when the page is already open and with several pushes
  waiting (each opens its own card). Older copies of the package, which open the bare panel URL after
  `rest_command.dieter_focus` (`POST /api/focus`, one card for 15 minutes, read when the page loads),
  still work; update the `url` variable to get the per-card link.

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
  GPT-OSS are selectable. Dieter's host tools (present_content, the background process tools) reach `agy`
  as the MCP server `dieter`, which the harness adapter registers in agy's config on the first turn that
  offers them (`agy mcp add dieter …`, kept in `/data/.gemini/config/mcp_config.json`): the stdio proxy
  `antigravity-host-tools.mjs` of the staged runtime forwards calls to the harness worker of the running
  turn and offers no tools outside one. Compaction
  runs agy's own `/compact`. Not available through the CLI: suspend/continue, messages into a running
  turn and image input (attachments are handed over as files). Its quota (`agy --print=/quota`) comes in groups whose models share a
  weekly and a 5-hour window (Gemini Flash/Pro; Claude and GPT-OSS; Gemini 4 Argon): **Tools → Usage** shows
  each window with the models it covers, the model pickers show the share left for each model.

Restart the add-on after changing tokens.

## Files

**Tools → Files** (and **Workspace → Files** on a project, **Files** in a conversation's menu)
browses a checkout through the daemon (`dieter file list`, `stat`, `read`): folders first, file sizes,
an eye icon shows dotfiles. Text files are displayed, images rendered, other binaries and files above
5 MB only described; the download icon saves the file. The conversation's menu uses the card's
workspace (its worktree for worktree cards), the Tools and project entries the project directory.
Browsing is read-only; editing stays with the agents.

## Schedules

**Tools → Schedules** (and **Workspace → Schedules** on a project) manages the daemon's recurring
tasks (`dieter schedule …`): the list shows cron, next run and paused state; **+** creates one with a
name, the prompt, a cron expression (presets: weekdays 07:00, daily, hourly, …; the next three times
are previewed as you type), the timezone (defaults to the browser's), project and board, agent, model
and effort, whether each occurrence starts the agent or only adds a Todo card, worktree or project
directory, and the vault/mail access of the cards. The detail page runs the schedule now, pauses or
resumes it, lists the occurrence history (each entry opens its card) and deletes the definition (cards
and history stay). The scheduler runs inside the add-on's daemon, so schedules fire while Home
Assistant is up. Agents themselves can also create schedules and cards with the CLI inside a turn.

## Gateway: show the add-on in the Dieter apps

The Dieter apps (Android, iPhone, Mac) talk to a **gateway**, and a gateway lists the machines
enrolled with it. The add-on's daemon is not enrolled by default, so the apps do not see its projects.
**Tools → Machines → Gateway → Enroll** runs `dieter daemon enroll` against your gateway (the URL the
apps use, HTTPS): open the sign-in link, authorize with GitHub, and the add-on appears as the machine
`machine_name` (default `home-assistant`) after its daemon restarts. Then approve the daemon's pairing
code, shown under **Tools → Devices**, in an app (Devices → Add a device) or on a trusted machine
(`dieter device approve CODE`). Options: `gateway_url` prefills the URL; `gateway_host_ip` pins the
gateway's host name in the container's `/etc/hosts` when it cannot be resolved there, for example a
Tailscale name (`https://host.tailnet.ts.net:4443` with the host's Tailscale IP); the certificate is
still verified against the name. Enrollment is kept in `/data/dieter/daemon`.
When another device has removed the add-on's daemon from the account (Devices shows "Removed"),
**Re-enroll** runs `dieter daemon unenroll` and shows the Enroll form again. A new enrollment gives the daemon a
new daemon ID: projects and harness settings stay, and the daemon registers its checkouts again under the new ID
at its first peer sync; conversations and schedules of the old ID stay as read-only history.

## Devices

**Tools → Devices** shows the add-on daemon's trust state, the account's machines, apps and recovery
key, the devices waiting to join, and **Add a device**: type the 16-character code a new app or
machine shows and approve it; **Remove** takes a device out of the roster (removed devices are not
listed, only counted). Only type a code you see on
your own new device. This works when the add-on's daemon is itself a trusted device of your account,
which needs `dieter daemon enroll` against your gateway; an add-on without enrollment (the default)
shows "Not enrolled", and the code is then approved on an enrolled machine (`dieter device approve
CODE`) or under Devices in the Dieter app.

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

## Images from the agent

The conversation shows images the agent produces, without a special tool:

- **Tool results with an image** (Claude Code's Read of a PNG/JPEG, screenshots of browser MCP tools)
  appear under the tool row instead of being folded into the activity summary. The bridge fetches the
  image from the tool output (`card|chat tool-output`) via `/api/{card|chat}/ID/tool-image`.
- **Markdown images in a reply**, `![caption](path)`: the path names a file of the conversation's
  checkout, relative or absolute inside it (`/api/{card|chat}/ID/image?path=`); `https:` and
  `data:image/` URLs are shown as they are. A path outside the checkout shows a pill.

Tap an image to view it full screen. The add-on's `~/.claude/CLAUDE.md` tells Claude Code about the
Markdown form; Codex and Antigravity don't get that hint yet.

## SSH key of the add-on

On its first start the add-on generates an ed25519 key pair in `/data/.ssh` (it survives updates and
is never part of the image or this repository). **Tools → Machines** shows the public key with a copy
button and a connection test. Add the public key to `~/.ssh/authorized_keys` on the computers Dieter
should reach, for example a Git remote over SSH or another machine with the Dieter daemon; `git` and
`ssh` inside the container pick the key up through `/data/.ssh/config` (first connections record the
host key, later ones verify it). The test runs `ssh -o BatchMode=yes <target> uname -n` and prints
the host name on success.

## Task titles

Tools → Settings → **Title model** picks who names a task or chat created without a title (the page's
New task with an empty title field, the `dieter_create_task` rest_command without `title`):

- **OpenAI** (default): Dieter's own `--auto-title`; the task is saved under its first line and GPT
  Spark improves the title (with OpenAI configured, otherwise the first line stays).
- **Ollama (HA)**: a local model through Home Assistant's AI Task. The bridge saves the task under its
  first line and renames it a moment later with the model's short summary (`ai_task.generate_data`,
  same language as the task). It finds the model by itself: the first available `ai_task` entity of the
  [Ollama integration](https://www.home-assistant.io/integrations/ollama/) (add an *AI Task* entry to
  the integration if it has only a conversation agent), looked up again every 10 minutes; the dialog
  shows the entity it found. Without one, OpenAI titles instead. If the model fails or takes longer
  than 2 minutes the first line stays; a title you change in the meantime is kept.

The choice is stored in `/data/bridge_settings.json`. Tasks that agents create with the `dieter` CLI do
not pass the bridge and keep Dieter's own title.

## Agent instructions

Tools → Settings → **Agent instructions** edits the text Dieter puts at the start of every turn's
instructions, for every agent (Claude Code, Codex, Antigravity) and every project. It is the daemon's own
prompt template (`dieter prompt show` / `dieter prompt update`), so the Dieter apps show the same text:

- **Every agent**: opens the instructions. Add your own rules here, e.g. at the end ("Always answer in
  German.", the machines an agent may use, …). `{{project.instructions_block}}` (the project's
  instructions) and `{{labels.instructions_block}}` (the card's label instructions) must stay in it exactly once.
- **Board cards** / **Standalone chats**: follow it for a card on a board or a standalone chat (how the agent
  uses the Dieter CLI, lanes and labels).

`{{variable}}`s are replaced with the conversation's values (the editor lists them); an unknown variable or a
missing block can't be saved. A change applies from each conversation's next turn. **Reset to Dieter's
default** brings back the release's text, which then follows later releases; an edited text stays as it is
across updates. A project or board with its own template (`dieter prompt project|board`) uses that instead of
**Every agent**; the editor lists those.

## Home Assistant from the agent

The agents can drive Home Assistant core with `hass-cli` (REST API through the Supervisor's proxy,
`HASS_SERVER=http://supervisor/core`, `HASS_TOKEN` = the add-on's token, `homeassistant_api: true`).
Examples: `hass-cli state list`, `hass-cli state get sensor.x`, `hass-cli service call light.turn_on
--arguments entity_id=light.x`, `hass-cli raw get /api/config`; WebSocket commands work too
(`hass-cli raw ws <type>`). The bridge forwards the two variables to the harness workers
(`DIETER_HARNESS_ENV`), so every agent inherits them; Claude Code additionally gets a note in
`/data/.claude/CLAUDE.md` (replaced only while it is the add-on's own text; edit or empty it as you like).
The token reaches every service of HA core, so word your tasks accordingly. The Supervisor API is off
(`hassio_api: false`): no `ha` CLI, no add-on, restart or host management from the agents. Home
Assistant's `/config` is not mounted in the container; edit configuration through another machine (SSH)
or the API.

**Logs:** `ha-logs` (`tools/ha-logs`, installed in the image) reads Home Assistant's system log over the
WebSocket API, the data behind Settings → System → Logs: `ha-logs --level error --since 1h`,
`ha-logs --logger zha --trace`, `ha-logs --grep 'pattern' -n 100`, `ha-logs --json`; `--help` lists everything.
Entries are oldest first (the newest ends the output), repeats are folded into one with a count, times are in
Home Assistant's time zone. The system log holds WARNING and above since the last start (50 entries by default,
`system_log: max_entries` in `configuration.yaml`); INFO and DEBUG lines are not in it. `ha-logs --full` reads
`/api/error_log` for the whole log file, which Home Assistant OS does not provide (it logs to the journal, and the
Supervisor's `/core/logs` needs `hassio_api`, which stays off). The Claude Code note in `CLAUDE.md` mentions it.

## Backups

`/data` is backed up with the add-on, except the harness runtime and the project checkouts
(both are re-downloaded / re-cloned).
