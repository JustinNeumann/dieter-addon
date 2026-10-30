# Dieter add-on

Runs the [Dieter](https://github.com/dbpprt/dieter) daemon inside Home Assistant, with Dieter's own app as its
page: the screens of the Android and iOS apps (Compose Multiplatform) compiled for the browser. The page opens
from the sidebar entry **Dieter** (Ingress: HA login, Nabu Casa and the Companion app work, nothing else is needed
on the phone). The sidebar entry is the add-on's "Show in sidebar" switch (`ingress_panel`), enabled on install.

The page is a Dieter client like the apps: it signs in to your Dieter gateway once and then shows every machine
of your account, the add-on's own daemon included once it is enrolled (see *The page* and *Gateway* below).

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
| bridge `/opt/bridge/dieter_bridge.py` | `0.0.0.0:8099` (Ingress port): serves the page and relays it to the web host; REST + SSE API for the web host and Home Assistant (`/api/ha`, the rest_commands) |
| operator (same jar, `com.getdieter.operator`) | `127.0.0.1:8096`, user `dieter`: the bridge's duties already moved to Kotlin (Agent instructions, settings, board order, repositories so far); the bridge forwards them to it |
| web host `/opt/web-host/dieter-web-host.jar` (Java 21) | loopback, user `dieter-web`: Dieter's core for the page (signed in to your gateway) and the add-on's own features; state in `/data/web-host` |
| harness runtime (the daemon's worker scripts + Bun, ≈ 800 MB) | staged by the bridge on first start into `/data/dieter/harness-staged/<version>` (sha256-pinned download; the daemon's own download timed out on a Raspberry Pi) and passed as `DIETER_HARNESS_RUNTIME_DIR`; staged runtimes of earlier versions are removed, and the Bun it carries is installed as the daemon's managed Bun (`/data/dieter/runtime/tools/bun/<bun version>`), which Oh My Pi's model discovery would otherwise download the whole archive for |
| `claude` 2.1.311, `codex` 0.163.0, `agy` 1.4.8 (Antigravity) | `/usr/local/bin`, all pinned by checksum; their configs live under `/data` (HOME) |
| `hass-cli` 1.0.0 (HA core REST API), `ha-logs` (HA logs) | `/usr/local/bin`; authenticated with the add-on's token, see below |
| `vl-convert` 1.9.0 (Vega-Lite/Vega charts to SVG, PNG, PDF; bundled fonts) | `/usr/local/bin`, pinned by checksum; agents render a chart and look at it before they mail it or show it in the chat (Claude Code's note in `/data/.claude/CLAUDE.md` says so; for Codex and Antigravity add it to Tools → Settings → Agent instructions) |
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
| `projects` | optional list of `{name, git_url, branch}` cloned and registered on start. Any number of repositories can also be added from the page: Projects → **Add** → *Clone repository* (Git URL, optional name and base branch) or *New empty repository* (an empty Git repository `/data/projects/<name>` without a remote, registered; an existing folder there is adopted). Boards are listed in the order you choose: drag a board row (touch: long press first) in Projects; the add-on keeps the order for every device. Projects lists the most recently used project first and opens it when the Projects tab is tapped |
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
  inherit it), New task → *New worktree* → **Publishing** overrides it for one card. New task and New chat start
  with *New worktree* and *Pull request* selected; choose *Board default* there to follow the board instead. The Access switches
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

- **Claude Code**: either sign in on the page (**Tools → Usage → Sign in**: the add-on runs
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

## Files and schedules

**Files** browses a checkout, read-only (editing stays with the agents): text files are shown, images rendered and
Markdown files drawn as formatted text. **Schedules** manages the daemons' recurring tasks (`dieter schedule …`):
create, run now, pause, history, delete. Both are the Dieter app's screens. The add-on's scheduler runs inside its
daemon, so its schedules fire while Home Assistant is up; agents can also create schedules and cards with the CLI
inside a turn.

## Mail

The **Mail** tab is Dieter Mail: the read-only Markdown reports agents send with `dieter mail send` from cards,
chats and schedules created with mail access (the **Allow mail to you** switch of New task and Schedules), and what
scripts send as operator. It is the apps' Mail: the Inbox newest first, Flagged, Unread, Archive and Trash, search,
and a message's page with its sender, **Open conversation** for the card or chat that sent it, Archive and Delete.

The body is real HTML inside the page, so the browser's selection, translation and screen readers work there.
Charts in `vega-lite` (or `vegalite`), `vega` and `mermaid` fences are drawn offline (the renderers ship with the
page) in sandboxed frames; raw HTML stays text and images show as links.

## Dreamfall boards

A **Dreamfall board** is a funnel for ideas next to the project's Kanban boards: Projects → **Add** → **New
Dreamfall board** (or **Tools → Dreamfall**) creates three ordinary boards and two schedules. The form asks for three things: the
**categories** to enumerate (one per line, or a categories file of the project), the **enumeration goal** (new ideas per
category and night) and the **navigation logic**: in plain language, what makes an idea worth your time, what to drop
and how selective to be. It starts with sensible criteria (value, realism, novelty, risk; about one idea in four) for you
to rewrite. A fourth, optional section, **Implementation**, holds instructions for every card you pick (for example
"when done, add a chart of the result and mail me a short report with it"; they are added to the picked card's brief)
and the switch **Picked cards may mail me**, which creates picked cards with mail access so their agents can send you
reports in Dieter Mail, where vega-lite and mermaid charts are drawn. Both apply to cards picked from then on. A topic
line, the times, the weekdays (all checked by default), the agent and the stage boards are optional.

- **Enumeration**: an agent writes new ideas every night (at least *n* per category, as Todo cards
  `[Category] Title`), after checking every idea the board ever had so it does not repeat one.
- **Navigation**: a second agent, later that night, judges every idea in the Enumeration queue by your navigation
  logic (it is quoted in the agent's instructions and decides over the agent's own taste). A strong
  idea is *transferred*: copied to Navigation's Todo with a link back to its source, and the original moves
  to Done. A weak one is *released* to Enumeration's Review lane. Nothing stays undecided. The agent
  waits while that night's Enumeration run is still working.
- **Implementation**: yours. Only your **Pick** on a Navigation idea copies it here as a Todo draft (in a
  new worktree or the project directory, optionally started at once). **Park** keeps an idea for later,
  **Back to the queue** undoes a release or a park, and you can promote a released idea yourself.
  A picked card's brief tells its agent to move it to Review when the work is committed and checked, also
  without a pull request when the project has no git remote. A card whose agent finished but left it in
  Running shows **Finished · still in Running** and a **Review** button.

**Tools → Dreamfall** lists the Dreamfall boards with the ideas waiting for you, and Projects shows each as one
row. The board shows the night: a funnel (how many ideas pass from stage to stage), each stage's last run and
Navigation's queue with its verdicts. Tapping an idea opens its brief, the verdict with the agent's reason and
score, the **Provenance** (the copies across the stages) and the **Trail**: who created, judged, transferred,
picked, parked or started it, when, and why. The board's ⋮ runs a stage now, changes the settings (name, topic,
categories or a categories file of the project, ideas per category, navigation logic, implementation instructions
and mail, times, time zone, agent, paused or not) and **Remove Dreamfall…** deletes the schedules; the three boards
and their cards stay.

The agents work through the `dreamfall` command in the image (`dreamfall --help`; run from their run
cards): `run begin`/`run end`, `ideas`, `add`, `queue`, `promote`, `release`. It records every step and
refuses what a stage may not do: no agent creates, moves or judges cards on Implementation, and a rerun
neither duplicates an idea nor transfers one twice. The records (pipeline, card verdicts and trail,
runs) are kept in Dieter's account-wide key-value store, namespace `dreamfall`. The rules are enforced
by the command and visible on the board: an agent that bypasses it with `dieter card create` on the
Implementation board is not stopped by the daemon, but its card is flagged **Added outside Dreamfall**.
The agents need the `dreamfall` command, so the project's checkout must be on the add-on's machine.

## Gateway: show the add-on in the Dieter apps

The Dieter apps (Android, iPhone, Mac) talk to a **gateway**, and a gateway lists the machines
enrolled with it. The add-on's daemon is not enrolled by default, so the apps do not see its projects.
**Tools → Machines → Gateway of this add-on → Enroll** runs `dieter daemon enroll` against your gateway (the URL
the apps use, HTTPS): open the sign-in link, authorize with GitHub, and the add-on appears as the machine
`machine_name` (default `home-assistant`) after its daemon restarts. Then approve the daemon's pairing code
under **Devices → Add a device** (on the page or in an app) or on a trusted machine (`dieter device approve CODE`). Options: `gateway_url` prefills the URL; `gateway_host_ip` pins the
gateway's host name in the container's `/etc/hosts` when it cannot be resolved there, for example a
Tailscale name (`https://host.tailnet.ts.net:4443` with the host's Tailscale IP); the certificate is
still verified against the name. Enrollment is kept in `/data/dieter/daemon`.
When another device has removed the add-on's daemon from the account, **Re-enroll** runs `dieter daemon unenroll` and shows the Enroll form again. A new enrollment gives the daemon a
new daemon ID: projects and harness settings stay, and the daemon registers its checkouts again under the new ID
at its first peer sync; conversations and schedules of the old ID stay as read-only history.

## Devices

**Tools → Devices** is the Dieter app's Devices screen: your account's machines and apps, the devices waiting to
join, and **Add a device** to approve the code a new app or machine shows. Only type a code you see on your own
new device.

## Questions from the agent and attachments

Claude Code may ask the user a question with options (its AskUserQuestion tool). Dieter's daemon has
no channel for that, so the fork's harness worker relays the question to the bridge
(`DIETER_QUESTIONS_URL`), the card shows it with its options and a free-form field, the Inbox lists the
card under "Waiting for your answer", and Home Assistant receives the event `dieter_question`
(`card_id`, `title`, `question`, `count`) for a notification. The answer, or a decline, continues the
turn. Unanswered questions expire after a day; cancelling the card cancels the question.

Messages, new tasks and new chats take attachments (images and files); the harness saves them in the
conversation's workspace and tells the agent where they are.

## Images from the agent

The conversation shows images the agent produces, without a special tool:

- **Tool results with an image** (Claude Code's Read of a PNG/JPEG, screenshots of browser MCP tools)
  appear under the tool row instead of being folded into the activity summary. Tap one to view it full
  screen.
- **Markdown images in a reply**, `![caption](path)`, stay links: the file is in the conversation's
  checkout (Files).

## SSH key of the add-on

On its first start the add-on generates an ed25519 key pair in `/data/.ssh` (it survives updates and
is never part of the image or this repository). **Tools → Machines** shows the public key with a copy
button and a connection test. Add the public key to `~/.ssh/authorized_keys` on the computers Dieter
should reach, for example a Git remote over SSH or another machine with the Dieter daemon; `git` and
`ssh` inside the container pick the key up through `/data/.ssh/config` (first connections record the
host key, later ones verify it). The test runs `ssh -o BatchMode=yes <target> uname -n` and prints
the host name on success.

## Task titles

Tools → Settings → **Title model** picks who names a task or chat created without a title (New task with an
empty title field on this add-on's machine, the `dieter_create_task` rest_command without `title`):

- **OpenAI** (default): Dieter's own `--auto-title`; the task is saved under its first line and GPT
  Spark improves the title (with OpenAI configured, otherwise the first line stays).
- **Ollama (HA)**: a local model through Home Assistant's AI Task. The bridge saves the task under its
  first line and renames it a moment later with the model's short summary (`ai_task.generate_data`,
  same language as the task). It finds the model by itself: the first available `ai_task` entity of the
  [Ollama integration](https://www.home-assistant.io/integrations/ollama/) (add an *AI Task* entry to
  the integration if it has only a conversation agent), looked up again every 10 minutes. Without one, OpenAI
  titles instead. If the model fails or takes longer
  than 2 minutes the first line stays; a title you change in the meantime is kept.

The choice is stored in `/data/bridge_settings.json`. Tasks that agents create with the `dieter` CLI do
not pass the bridge and keep Dieter's own title.

## Work folders

A task with its own worktree (New task → New worktree) gets a folder `/data/dieter/worktrees/PROJECT/CARD`, one
checkout per task, and the daemon never removes it. Tools → Settings → **Keep finished work folders** sets how long
they stay: 0 (*Never remove*) keeps everything, 30 removes a folder 30 days after its task went to Done (or was
archived) without anything happening in it since. The default is 14 days; the choice is stored in
`/data/bridge_settings.json` and checked once an hour and right after you change it (log lines start with
`housekeeping:`). The folder and its branch are removed when the work is merged: the branch is merged into its base,
or GitHub has a merged pull request ending at the folder's last commit (squash merges; that removal keeps a recovery
copy in `/data/dieter/recovery`). Unmerged work and running tasks are kept.

The cards of a Dreamfall Implementation board follow the same retention from the moment they are started: nobody
moves a picked card to Done, so a started card whose last activity is older than the retention loses its folder
whatever lane it is in. A card that only produced a report, a chart or a mail has no commits and is removed; one with
unmerged commits stays.

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

## The page

Dieter's own app screens (the Compose UI of the Android and iOS apps) compiled for the browser, built from this
repository with the add-on. The add-on's root forwards to it at `next/`.

- Served behind Ingress: HA login, Nabu Casa and the Companion app work.
- **Its own sign-in.** The page is a Dieter *client* like the Android app: it signs in to your Dieter gateway
  with GitHub and is then paired like a new device. Its core runs inside this add-on (the *web host*, a small
  Java process), so the sign-in belongs to the add-on, not to a browser: sign in once and every browser and the
  Companion app that open the page use it. Until then it shows the sign-in screen; `next/?demo` shows demo data.
- **Theme:** Appearance *System* follows Home Assistant's light/dark mode (your HA profile, also when it differs
  from the device's), live without a reload. **Dynamic color** (Settings) takes Dieter's colours from Home
  Assistant's primary colour, including custom themes.
- **Browsers:** it needs WebAssembly GC: current Chrome, Edge and Firefox, Android's WebView (Companion app),
  Safari and iPhone/iPad from iOS 18.2. Older browsers get a note saying so.
- **Screen readers:** after a menu, dialog or sheet has closed, the page's accessibility tree keeps that popup's
  entries until a reload (Compose for Web 1.12; 1.13 fixes it once its text input is reliable).
- First load about 5 MB (the WebAssembly is fetched gzip-compressed and inflated in the browser, because Ingress
  neither forwards `Accept-Encoding` nor compresses WebAssembly), afterwards from the browser cache.
- `next/?theme=dark|light` overrides the theme, `next/?script=tab projects; board Main` replays the app's debug
  navigation. `<your HA address>/app/<this add-on's slug>/next/` opens the page directly; behind it
  `card/<id>` opens a conversation, `tab/<name>` a tab, `dreamfall/<id>` a Dreamfall board (`…/settings` its
  settings) and `idea/<id>` an idea. While the page is open, Dieter's push notifications open their card in it;
  the address then goes back to `…/next`, so a reload stays on the page.

### Signing in the page

GitHub sends you back to your gateway, and the gateway sends the browser back to the app. For the page that
return address is your Home Assistant: `https://<your HA address>/app/<this add-on's slug>/next/signin` (the
slug is the part after `/app/` when you open the add-on, e.g. `4d8f2a84_dieter`). The gateway accepts only
return addresses it lists, so once:

1. On the gateway's machine add the address to `DIETER_NATIVE_REDIRECT_URIS` (comma-separated, exact, keep the
   app's existing entries), e.g. in the gateway's `.env`, and restart the gateway. Add one address per way you
   open Home Assistant for signing in (e.g. the Nabu Casa address).
2. Open the page through that address, enter the gateway address, **Sign in**, approve on GitHub and on the
   gateway's confirmation page. The browser comes back to Home Assistant and the page says *Signed in*.
3. The page then shows a pairing code: approve it on a device that is already trusted (the Dieter app's Devices
   screen, or `dieter device approve <code>` on a paired machine).

Sign in from a browser where you are logged in to Home Assistant (a desktop browser is easiest; the Companion app
opens sign-in in your phone's browser, which then needs a Home Assistant login too). Each attempt returns only to
the address the page was opened through; if the return lands in a browser without your Home Assistant login,
start the sign-in again from one that has it. The option `web_sign_in_callback` pins the return address instead
of using the one you opened Home Assistant with.

**What the web host holds:** this install's device key and the gateway session, in `/data/web-host` (part of
the add-on's backups). It runs as its own user, so the agents (user `dieter`) can neither read that directory
nor talk to it: it answers only the bridge, which proves itself with a secret created at every start and kept
only in the bridge's memory (the bridge is non-dumpable, so processes of the agents' user cannot read it). The
session gives full access to every machine of your Dieter account, like any signed-in app. Sign out in the
page's Settings, or revoke the device in the Dieter app.

## Backups

`/data` is backed up with the add-on, except the harness runtime and the project checkouts
(both are re-downloaded / re-cloned).
