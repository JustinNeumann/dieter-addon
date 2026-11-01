# Changelog

Version = bundled Dieter release + add-on revision (`0.6.429.1` = Dieter 0.6.429, add-on revision 1).

## 0.6.431.40 (2026-11-01)

- **Adopt projects, boards, cards, chats, checkouts, schedules, and Dreamfall pipelines across gateway modes (`external` ↔ `embedded`).**
  - Switching between `external` and `embedded` gateway modes now adopts the previous mode's CRDT peer replica records
    (`project/*`, `board/*`, `label/*`, `assignment/*`, `checkout/*`, `item/*`, `schedule/*`, and `kv.*` including
    Dreamfall pipelines) into the active mode's account scope, re-signs owner-bound records (`checkout.registration`,
    `item.identity`, `item.summary`, `schedule.summary`) with the active mode's daemon identity, and updates local
    card markdown files (`/data/dieter/cards/*.md`, `/data/dieter/archived-cards/*.md`), checkouts
    (`/data/dieter/checkouts/*.json`), and `schedules.db`.
  - If `0.6.431.39` previously initialized an empty `embedded` replica (with 0 items and duplicate auto-registered
    projects) before adopting `external` items, the empty replica is automatically reset and replaced with the full
    canonical projects, boards, cards, chats, and Dreamfall pipelines from `external` mode.

## 0.6.431.39 (2026-11-01)

- **Embedded standalone gateway (`gateway_mode: embedded` by default), switchable two ways (`embedded` ↔ `external`).**
  - **Standalone out of the box (`embedded`):** the add-on now bundles `dieter-gateway` and runs it on loopback
    (`127.0.0.1:4243`, state in `/data/gateway`, owned by `dieter-web`). Before the daemon starts, the operator
    automatically enrolls `/data/dieter` with the embedded gateway, binds its peer store, and initializes the device
    roster; the web host adopts a pre-seeded local session and pairs its device automatically via the operator. Opening
    the add-on in Home Assistant Ingress goes straight to the board without needing an external gateway server, TLS
    certificates, or a GitHub OAuth app.
  - **Two-way switchable (`embedded` ↔ `external`):** set `gateway_mode` in the add-on configuration to `external` to
    connect the web host and daemon to a remote Dieter gateway (`gateway_url`) shared with mobile/desktop apps, or back
    to `embedded` for standalone operation. Switching modes preserves each mode's daemon identity, device roster, peer
    binding, and checkouts under `/data/dieter/gateway-modes/<mode>/` (and the web host's state under `/data/web-host/`
    vs `/data/web-host/embedded/`), and automatically adopts existing `/data/projects/*` repositories in the active
    mode's replica.

## 0.6.431.38 (2026-11-01)

- **Mount `/share` (`share:rw`) and include `rsync`.** The add-on now maps Home Assistant's `/share` directory
  read-write into the container (for example `/share/dieter-desk` mounted over NFS from `dieter-desk@192.168.68.107`)
  and includes `rsync` in the image so agents can sync files with network shares without slow SSH/SCP transfers.

## 0.6.431.37 (2026-11-01)

- **Fix: every changed file in Changes showed `+0 −0`.** The daemon's `GetChangeset` reads `git status
  --porcelain=v2`, which lists paths and statuses without line statistics. Both Changes views (a conversation's
  Changes pane and Project → Changes) now count added and removed lines from each file's patch at the changeset's
  revision, reuse those counts and first diff pages while the revision is unchanged, and omit the delta label on files
  with no line changes (fork `web-addon`).
- **Fix: a project-directory conversation's Changes pane failed with `gRPC FAILED_PRECONDITION: project-directory
  changes are project-scoped; request them with project_id`.** Opening Changes on a conversation that runs in the
  project directory rather than a worktree now requests the changeset, diffs, and project-scoped Git operations by
  `project_id` and `checkout_id`, and hides the worktree-only *Merge readiness* section (fork `web-addon`).

## 0.6.431.36 (2026-10-31)

- **Antigravity parity with Claude Code (fork `0.6.431-antigravity.11` & `web-addon`):**
  - **Model discovery & context windows:** the daemon discovers models from `agy models` and `agy --help`, and sets
    `contextWindow` (`1048576` for Gemini 4 / 3.8, `200000` for Claude 5.5, `131072` for GPT-OSS 120B), `defaultEffort`,
    and fixed tier `efforts` across all 13 catalog models so the conversation context usage bar renders accurately.
  - **Subagents & task plans:** `subagents: lifecycle` (`invoke_subagent`) and `task-plan: host-tool` (`board_task_plan`
    via MCP) are enabled for Antigravity turns.
  - **Agent Channels:** write-scope detection, commit sweeps, and `dieter channel hook` enforce channel path ownership
    on Antigravity's `write_file`, `edit_file`, and `run_command` tools (`PreToolUse` hook payload).
  - **Live activity & channel visuals:** the page shows live activity summaries for `view_file`, `run_command`,
    `grep_search`, `find_by_name`, `search_web`, `read_url_content`, and `manage_task`, and gives Antigravity its own
    channel avatar badge.

## 0.6.431.35 (2026-10-31)

- **Fix: the Inbox's filters wrapped onto a second line.** In a narrow list pane (an iPad or Android tablet in
  portrait, with three-digit counts such as *Review 244*) the last filter dropped below the others. Dense filter
  pills now size to their label instead of a 48 dp minimum width, and the row scrolls sideways when a pane is still
  too narrow, like the board's lane and label filters (fork `web-addon`).

## 0.6.431.34 (2026-10-31)

- **New navigation from Dieter 0.6.432: Inbox, Projects, Chats and More.** Mail, Channels, Terminals, Usage, the
  machines, Devices and Settings are in More; Files, Changes and Schedules open from their project. On wide
  screens More stays beside the open page. The daemon is unchanged (0.6.432 changes only the apps).
- Add-on pages follow: **Dreamfall** is listed in More, **Gateway of this add-on** is on Devices, the add-on's
  **SSH key** in Settings. Schedules open from a project, with an *All projects* switch in their menu.
- Links from before (`tab/mail`, `tab/channels`, `tab/tools`, `dreamfall/<id>`) still open the right place.

## 0.6.431.33 (2026-10-31)

- **Fix: Ollama task titles were never applied.** With Settings → Task titles on Ollama, a new task or chat kept
  its first line: the page handed the add-on the conversation's local ID from before the daemon had it, and the
  rename failed (`card "local_…": not found` in the log). The page now waits until the daemon has the
  conversation and passes its real ID (fork `web-addon`).

## 0.6.431.32 (2026-10-31)

- **Merging is the person's.** Agents prepare pull requests; you merge them from the card's Changes pane (Git
  actions → Merge pull request). From an agent turn the daemon now refuses `merge_pr` and `merge_local` (fork
  0.6.431-antigravity.10), and the image's `gh` refuses `gh pr merge`, the REST merge endpoints and GraphQL merge
  mutations. The Claude Code note says so (an unedited note is replaced). A guardrail for agents that use the
  tools: the GitHub token itself can merge, see DOCS.
- **Fix: "This workspace conflicts with main" on every task.** The Changes pane showed the red conflict banner for
  any review; it now shows only while the workspace has conflicts (fork `web-addon`).

## 0.6.431.31 (2026-10-31)

- **The palette you pick is kept for every device.** Settings → Appearance → Palette was stored only in the browser,
  so each device, the Companion app and every address of Home Assistant (local or Nabu Casa) had its own and started
  in Monochrome. The add-on now keeps the pick in its settings (`palette` in `/data/bridge_settings.json`): every page
  load starts in it, and open pages on other devices switch along. A page that has a palette from before hands it over
  the first time it opens. Light/Dark stays per device.

## 0.6.431.30 (2026-10-31)

- **Fix: archived cards could not be restored.** A board's Archived Cards sheet sent the restore without its board, and
  Dieter's core then looked for the card among archived chats: "The archived card is no longer available." The sheet
  passes its board now (fork `web-addon`; the daemon is unchanged).
- **New task starts in a new worktree.** The form took the workspace chosen last, so after one task in the project
  directory every new task started there too. A chat still starts where the last one was made.

## 0.6.431.29 (2026-10-31)

- **The agent relay closes WebSockets whose turn has ended.** The turn's token was checked once, at the WebSocket's
  sign-in; a process the turn left running could keep Home Assistant access over an open socket. The web host now
  asks the daemon again every 30 s (past the 10 s cache of REST answers) and closes the socket once the turn is gone.
- After an update or restart, Dreamfall boards come back within 10 s of the daemon's start (the operator retried its
  Dreamfall watch up to a minute apart; until then the Projects overview and the Dreamfall list showed none).

## 0.6.431.28 (2026-10-31)

- **"Allow Home Assistant access" per task.** New task and the schedule editor show it next to vault access
  (`home_assistant: true` on `POST /api/card` and the package's `rest_command.dieter_create_task`). Like vault access
  it is fixed at creation, a schedule passes it on to its cards, and an agent can grant it only from a conversation
  that has it. Such a conversation's `hass-cli` and `ha-logs` reach Home Assistant through the web host's new relay
  (`127.0.0.1:8098`): they sign in with the turn's token, the web host asks the daemon which conversation it belongs
  to (fork release 0.6.431-antigravity.9: `dieter card caller`, the card flag), and only then passes REST calls and
  the WebSocket API on with the add-on's token, which the agents never see. Other conversations get 403.
- The option `agents_home_assistant` is now **Home Assistant for every task**: on, every agent gets the add-on's
  token as before.
- The relay for the operator's calls passes `dieter_turn_finished`, `dieter_card_review` and `dieter_question` only
  with a card id (`c_` and hex) in `id`/`card_id`: an agent can still send such an event with made-up text, but its
  notification's link can no longer point elsewhere in Home Assistant. The docs list a known limit of questions: an
  agent can ask under another card's name.
- **Fix: a Dreamfall board stayed black** once two of Implementation's lanes (Todo, Running, Review) had cards at
  the same time: both lane headings had the same list key, and Compose stopped drawing the page.
- Fork 0.6.431-antigravity.9 also carries the harness worker's question poll token, so the add-on no longer patches
  `host-questions.mjs`.

## 0.6.431.27 (2026-10-30)

- **The add-on's Supervisor token no longer reaches the agents by default.** Home Assistant treats it as its
  Supervisor user, an administrator, so an agent holding it could call the add-on's own rest_commands (Home
  Assistant then calls port 8099 from `172.30.32.1`): post a message into any card as you, create and start a
  task. The agents found it in `HASS_TOKEN`, and also in the operator's and the daemon's environment and in s6's
  world-readable copy of the container's environment. Now only the web host (user `dieter-web`) gets it and relays
  the operator's few Home Assistant calls (its three events, the Ollama entity lookup, task titles), refusing
  anything else. The new option **Home Assistant for agents** (`agents_home_assistant`, off) gives the agents
  `hass-cli` and `ha-logs` again; the docs say what that allows. Claude Code's note says when the agents have no
  Home Assistant.
- **Questions: the answer goes only to the worker that asked.** A registration gets a random token the worker
  sends back with every poll (the add-on's copy of the harness worker; fork `host-questions.mjs`). An id still in
  use cannot be registered again (409), so nobody can swap a waiting question's text. The event `dieter_question`
  no longer carries the question's id (it was not documented, and the package does not use it).
- Home Assistant's follow-up route on 8099 takes only `card` and `chat` (`POST /api/card|chat/<id>/send`).
- The docs add that an add-on with the Supervisor's admin role can open an Ingress session of its own.

## 0.6.431.26 (2026-10-30)

- **Agents can no longer act as you through the operator.** The operator (`127.0.0.1:8096`) runs as the agents'
  user, and it accepted every request from loopback: an agent could list another card's pending question and
  answer it (the answer reached that turn as yours), or unenroll the gateway without Dieter's turn token. Now it
  asks the kernel who opened the connection: root and the web host's user (the page) get every route, everyone
  else only what the harness worker needs (register a question, poll its answer).

## 0.6.431.25 (2026-10-30)

- **Boards are wide on desktops and tablets**, like Dreamfall boards: the board takes the whole width and its
  lanes stand side by side, sharing it (each at least 260 dp, scrolling below that). Before, a board opened in the
  narrow list pane and showed one lane at a time. A card opens over the board; Back returns to it. Phones keep
  paging through the lanes.
- **No more "Refreshing…" flicker under a running conversation.** The card's activity reaches the page a moment
  before the conversation's stream, and every such moment showed "Refreshing…". It now shows only when the lag
  lasts longer than half a second and the conversation is read again; opening a conversation, a broken stream and
  an offline machine still show it at once.

## 0.6.431.24 (2026-10-30)

- **Port 8099 answers only whom it must**, as Home Assistant's add-on rules ask (allow the Supervisor, deny the
  rest). Before, everything on the Supervisor's network (`172.30.32.0/23`, where other add-ons sit at
  `172.30.33.x`) and loopback (where the agents run) could use the whole API, and any add-on could open the
  page's core with the account session. Now:
  - Home Assistant's Ingress (`172.30.32.2`), i.e. a person signed in to Home Assistant: the page, its core and the
    whole API, as before.
  - Home Assistant itself (`172.30.32.1`), or a caller with the new **API key** option
    (`Authorization: Bearer <key>`): Home Assistant's routes only (sensor, board state, new card, follow-up, move,
    focus). The Home Assistant package keeps working unchanged.
  - Nobody else, not other add-ons and not the agents.
- Only Ingress can name a Home Assistant user to the add-on (`X-Remote-User-*`).
- The API key reaches the web host only: `/data/options.json` belongs to root now, and the operator, the daemon
  and the agents read a copy without the key.

## 0.6.431.23 (2026-10-30)

- **The bridge into Kotlin, step 10 of 10:** the add-on's front door on the Ingress port is the web host now. It
  serves the page and its core itself and passes `/api/` (Home Assistant's sensor and rest_commands, the page's
  add-on features) to the operator, with the same paths and answers. The Python bridge is gone from the image.
- The page's core no longer needs a secret between two processes: it lives in the web host, which agents can
  neither read nor reach (its core answers only Home Assistant's Ingress).
- The web host's log lines show local time, like the rest of the log (they were in UTC).

## 0.6.431.22 (2026-10-30)

- A retired board whose cards are all archived no longer stays in Projects with "0 cards". The daemon already
  allowed retiring such a board (fork release antigravity.7), but the page still counted the archived cards as
  keeping it, as upstream does, and showed it as if it could not be retired. The page now decides as the daemon
  does: an unarchived card, on any machine, still keeps a board visible.

## 0.6.431.21 (2026-10-30)

- **The bridge into Kotlin, step 9 of 10:** the board's live state is the operator's now: the polls and the
  daemon's change stream, the Dreamfall boards' live view, the quota and machine figures, and what Home Assistant
  asks of the add-on (`sensor.dieter`, creating a task, a follow-up, moving a card, opening a card from a push). The
  paths, answers and events (`dieter_turn_finished`, `dieter_card_review`, `dieter_question`) stay the same, so
  `homeassistant/packages/dieter.yaml` needs no change. The bridge is left with Ingress, the page's files and the
  relay to the web host, and forwards everything under `/api/` to the operator.
- **Security:** the bridge relayed the page's core connection (`next/core`, `next/signin`) for any caller on the
  container's loopback, the agents included, adding the web host's secret. An agent could thereby drive the web host's
  gateway session (every machine of your Dieter account), which the agents must not reach. The relay now answers only
  Home Assistant's Ingress (the Supervisor's network); the page works as before.
- The classic page's own endpoints are gone (the board's file browser and diffs, devices, machines, Dieter Mail's
  inbox, schedule editing over `/api/schedules/…`); the page at `next/` never used them. Home Assistant's
  `/api/schedules?project=` stays.

## 0.6.431.20 (2026-10-30)

- **The bridge into Kotlin, step 8 of 10:** the hourly removal of finished work folders (Tools → Settings → *Keep
  finished work folders*) and the renaming of new tasks by Ollama (*Task titles → Ollama (HA)*) are the operator's
  now. Both work as before; the log lines read `operator: housekeeping: …` and `operator: title: …`.
- An Ollama title whose closing quote was followed by a period kept the quote; a long first line without a space
  made an 81-character provisional title. Both fixed.

## 0.6.431.19 (2026-10-30)

- **The bridge into Kotlin, step 7 of 10:** Dreamfall boards belong to the operator now: creating a board, its
  settings, *Run now*, removing it, and your decisions on an idea (promote, release, pick, park, restore, start). So
  does the `dreamfall` command the stage agents run from their run cards (same subcommands, output and exit codes).
  The page shows a decision as quickly as before, and a pick still reuses a copy made earlier instead of listing the
  board. The records stay in Dieter's key-value store, so existing boards, ideas and their trails carry over as they
  are.
- Tools → Schedules: *Run history* in a schedule's menu showed the runs after the last schedule, below the screen, so
  the click seemed to do nothing. The runs now open right under their schedule, which moves to the top, and the same
  menu item hides them again. A run's time reads *1d ago* instead of a raw timestamp.

## 0.6.431.18 (2026-10-30)

- **The bridge into Kotlin, step 6 of 10:** the questions agents ask you (*Waiting for your answer* in the Inbox) and
  the files you attach to a message are kept by the operator now. Nothing changes on the page or for Home Assistant:
  a new question still fires `dieter_question`, and attachments still go with the message and are removed once sent
  (or after a day if never sent).

## 0.6.431.17 (2026-10-30)

- A conversation's header names the card's lane as well, e.g. *sem2_ha · Home Assistant Ideas · Navigation · Done*.
  Moving a card from the conversation (⋯ → Move to, or *Move to Review* above the message field) worked, but nothing
  on that screen changed, so it looked as if the move had failed. Now the lane changes the moment you tap.

## 0.6.431.16 (2026-10-30)

- **The bridge into Kotlin, step 5 of 10:** the Claude sign-in (Tools → Usage), the gateway enrollment and the
  add-on's SSH key with its connection test (Tools → Machines) belong to the operator now. The page, its buttons and
  the files stay the same (`/data/.claude/.credentials.json`, `/data/.ssh`); the sign-in still runs
  `claude auth login` in a terminal, and a sign-in, sign-out or enrollment still restarts the daemon.

## 0.6.431.15 (2026-10-30)

- **Tools → Schedules shows every project's schedules**, each under its project's name. Before, it showed only the
  first project in the list, so schedules looked lost when that project had none. The menu (⋯ → Project) narrows the
  list to one project and back to *All projects*. A new schedule asks for its project there.
- Files, Changes and the other project tools start in the project you worked in last (newest activity on a card or
  chat you started; cards a schedule created don't count) instead of the first project in the list.
- A Dreamfall board takes the whole width on a tablet or PC again, like on the old page, whether it is opened from
  Projects or from Tools → Dreamfall. Before, it was squeezed into the 400 px list column.
- **Usage → Antigravity:** each quota group has an *Include in summary* switch. A group that is switched off stays on
  the page but no longer pulls down the Antigravity bar in the summary.

## 0.6.431.14 (2026-10-30)

- A mouse wheel or trackpad that scrolls a list back past its top no longer leaves the round refresh arrow hanging
  there (Chrome on a PC); pulling down by touch still syncs again.
- New palette **Dreamfall Pearl** (Tools → Settings → Appearance): night-sky navy with a pearlescent sheen (cyan,
  periwinkle, violet, pink, peach) and a golden glow; pearl white in light mode.
- Every start of the page writes one line into the add-on log: how long it took on that device and which of the
  page's files came over the network. It shows whether the Companion app downloads the page again on each open (its
  WebView keeps only a small cache).

## 0.6.431.13 (2026-10-30)

- **The bridge into Kotlin, step 4 of 10:** the add-on's start and the daemon belong to the operator now. Staging the
  harness runtime (sha256-pinned, ≈ 775 MB once) and the managed Bun, the provider tokens for the daemon, git and gh
  with the GitHub token, Claude Code's note and Antigravity's credentials, then the daemon, started again when it
  exits, after a Claude sign-in and after a gateway enrollment. A daemon that an exited operator left behind is
  stopped by the next one, so there is never a second. The page is served while the first start downloads.
- Claude Code's note (`/data/.claude/CLAUDE.md`, replaced only if you never edited it) no longer promises that
  Markdown images in a reply are shown: the page shows images that tools return and links the others.
- The image sets `DIETER_VERSION`, its one pin of the fork release.

## 0.6.431.12 (2026-10-30)

- **The bridge into Kotlin, step 3 of 10:** repositories (Projects → Add: clone, new empty repository, an existing
  folder adopted) and the `projects` option are handled by the operator. Same folders (`/data/projects/<name>`), same
  checks and messages.

## 0.6.431.11 (2026-10-30)

- **The bridge into Kotlin, step 2 of 9:** the add-on's settings (Tools → Settings: Task titles, Keep finished work
  folders) and the board order dragged in Projects are kept by the operator now. The file stays
  `/data/bridge_settings.json`, so nothing is lost or changes on the page.

## 0.6.431.10 (2026-10-30)

- **The bridge into Kotlin, step 1 of 8:** the add-on gets its *operator*, the Kotlin half of the bridge (same jar
  as the web host, run as the add-on's user `dieter` on loopback). **Agent instructions** (Tools → Settings) are
  the first area it answers; the bridge forwards them to it. Nothing changes on the page or for Home Assistant.

## 0.6.431.9 (2026-10-30)

- **One page:** the add-on opens Dieter's Compose page directly; the classic page is gone. The sidebar entry,
  `<HA>/app/<slug>/next/…` deep links and push notifications open the Compose page as before. Older browsers without
  WebAssembly GC (iOS before 18.2) can no longer use the add-on.
- **One repository:** the page and its web host are now built from this repository with every image (they lived in
  dieter-addon-kotlin until 0.6.431.8), so they always match the add-on. Nothing changes for installs.
- The bridge still runs: the web host reaches the add-on's features through it, and Home Assistant's sensor and
  rest_commands (`homeassistant/packages/dieter.yaml`) talk to it unchanged. It moves into the web host step by step.
- **Dreamfall:** a picked or promoted idea no longer shows up for a moment on its new stage without the verdict it
  came with (the source's verdict is now recorded first).

## 0.6.431.8 (2026-10-30)

- **New page (`next/`), dieter-addon-kotlin 0.4.5:**
  - **Touch:** the browser no longer takes over a swipe on the page (iPadOS did, mid-swipe), so lists scroll on with
    Compose; pinch-zoom still works.
  - **Dreamfall in Projects:** a Dreamfall board's stage boards are one purple row with its ideas and a badge for
    the ones waiting for you, as on this page; it opens the Dreamfall board.
  - **Inbox:** Antigravity is the usage strip's third bar (its tightest quota group). The filters fit on one line on
    phones and narrow panes, and wrap instead of being cut off.
  - **Archive board** (board menu): Dieter retires a board without open cards; its archived cards stay with it.
    The project lists it under "Archived boards" with Restore. A board with open cards or a schedule stays, and
    the page says why.
  - Still on Compose Multiplatform 1.12, so screen readers keep a closed popup's entries until a reload. 1.13
    fixes that, but its beta 1.13.0-beta01 loses or reorders keys typed with a keyboard (the never-pinned 0.4.4
    used it).
- **Dieter fork release v0.6.431-antigravity.8:** a board whose cards are all archived can be retired (upstream
  keeps any board an archived card points at, and Dieter can neither delete cards nor move them to another board).
  Its archived cards and their conversations stay readable; tasks on a retired board don't start.

## 0.6.431.7 (2026-10-30)

- **New page (`next/`), dieter-addon-kotlin 0.4.3:**
  - **Images in the chat:** an attached image shows as the picture, and a tool's screenshot (Claude Code reading an
    image, browser screenshots) shows under its row. A tap opens it full screen over the page; a tap, ✕ or Escape
    closes it. The tool's details show the images above its output.
  - **Board order:** in Projects, hold a board and drag it into place. The order is the one this page keeps, so both
    pages and every device show it.
  - **New board** offers Publish (Manual, Push base, Pull request), as the board settings do.
  - **Usage** shows Antigravity's quota groups while the add-on has Antigravity credentials (one card per group,
    with its weekly and 5-hour windows).
  - Relative links in an agent's mail open the file in the sender's checkout (verified; it already worked).

## 0.6.431.6 (2026-10-30)

- **New page (`next/`), dieter-addon-kotlin 0.4.2:**
  - **Task titles with Ollama:** a task or chat started there without a title is named by Ollama, as on this page.
    It shows its first line until the name arrives. Tasks on other machines keep Dieter's auto-title (the add-on can
    rename only on its own machine). New bridge call `POST api/titles`; `GET api/settings` names the add-on's
    machine while Ollama names tasks (`titles_daemon_id`).
  - **The project used last** comes first in Projects ("Last used"), New task preselects it, and choosing the
    Projects tab from another tab opens it. Opening a project, board, Dreamfall board or conversation makes it the
    last used. The add-on keeps it for every browser, unlike this page, which keeps it per browser.

## 0.6.431.5 (2026-10-30)

- **New page (`next/`), dieter-addon-kotlin 0.4.1:**
  - Tools → Settings → **Agent instructions**: the parts Dieter puts at the start of every turn (every agent, board
    cards, standalone chats) can be edited and reset to Dieter's default, as on this page.
  - Dreamfall on a phone: the three stage names fit; the counts between the stages show on wider screens.

## 0.6.431.4 (2026-10-30)

- **New page (`next/`), dieter-addon-kotlin 0.4.0: the add-on's own features.**
  - Tools → Machines: enroll this add-on with your gateway, and see its SSH key with a connection test.
  - Tools → Usage: sign in to Claude Code.
  - Tools → Settings: the title model and how long finished work folders are kept.
  - Agent questions: under "Waiting for your answer" in the Inbox and in the conversation.
  - Projects → Add: Clone repository and New empty repository.
  - **Dreamfall:** Tools → Dreamfall lists the boards; a stage board opened from Projects shows its Dreamfall board
    with the funnel and the three stages side by side (tabs on phones). Ideas open with their brief, where they
    came from and their trail; Pick (own worktree or the project folder, start now or later), Park, Promote,
    Release, Back to the queue, Start and Review work as here. The ⋮ menu runs a stage, pauses the schedules, opens
    the settings or a stage as a Kanban board, and removes the Dreamfall. New boards come from Tools → Dreamfall or
    Projects → Add. "New idea" on Enumeration adds one by hand to the queue. Run times show in the board's time
    zone; a new board starts in the browser's.
  - The browser's Back button closes a sheet or goes back a screen.
  - Attach opens the browser's file dialog (up to 4 files, 5 MB each, 6 MB in total). An attachment or file preview
    opens in a new tab, or downloads when the browser cannot show the type. Share on a mail message uses the phone's
    share sheet, and on a computer downloads the message as Markdown.
- **How it works:** these features run in a new *add-on core* inside the web host, which talks to the bridge over
  loopback. The page only shows what that core sends, as Dieter's own screens do with Dieter's core.
- **Sign-in on the new page:** each attempt returns to the address the page was opened through. The fallback of
  pasting the address you landed on into the gateway field is gone (see the documentation).
- **Links:** `<HA>/app/<slug>/next/card/<id>` opens the Inbox when the card no longer exists;
  `…/next/dreamfall/<id>` and `…/next/idea/<id>` open a Dreamfall board or an idea. A push notification tapped while
  the new page is open opens its card there, as on this page.
- **Bridge:** `/api/events?fields=a,b` streams only those parts of the state, and only when they change. The web
  host follows questions, the Claude sign-in and Dreamfall this way; `dreamfall_cards` is the open cards of the
  stage boards with the start of their brief.
- **Known limit:** after a menu or dialog closes, screen readers keep seeing it until the next one opens. This is a
  bug in Compose Multiplatform 1.12, fixed in 1.13.

## 0.6.431.3 (2026-10-29)

- **New page (`next/`), dieter-addon-kotlin 0.3.0:** text in mail, files, tool output and subagent details can be
  selected and copied. Ctrl/⌘+F searches inside a conversation, a mail message or a file. Mail bodies and Markdown
  files render as real HTML, so the browser's own selection and find work there. Mermaid, vega and vega-lite
  diagrams render offline in a sandboxed frame. Screen readers get card and inbox titles instead of initials, and
  Escape goes back. `next/?islands=off` keeps the previous rendering.

## 0.6.431.2 (2026-10-29)

- **Preview of the new page at `next/`**: Dieter's own Compose screens (the Android/iOS app's UI) compiled for the
  browser with Kotlin/Wasm, from the new repository
  [dieter-addon-kotlin](https://github.com/JustinNeumann/dieter-addon-kotlin) (release 0.2.1, pinned by sha256).
  Open it from **Tools → Preview → New page** or at `<HA>/app/<slug>/next/`. It is served behind Ingress like
  this page, so it works over Nabu Casa and in the Companion app. Browsers without WebAssembly GC (Safari/iOS
  before 18.2) get a note with a link back to this page.
- **It is a real Dieter client:** its core runs in the add-on as the *web host* (Java 21 from Debian, own user
  `dieter-web`, state in `/data/web-host`), reached over one WebSocket through Ingress. It signs in to your Dieter
  gateway with GitHub and is paired like a new device; the sign-in belongs to the add-on, so every browser and
  the Companion app share it. GitHub's return goes to `<HA>/app/<slug>/next/signin`, which the gateway must list
  in `DIETER_NATIVE_REDIRECT_URIS` (see the documentation). Agents cannot read the web host's state or talk to it.
  `next/?demo` shows demo data without an account.
- **Theme:** light/dark follows your HA profile without a reload; with Settings → Dynamic color on, the colours come
  from HA's primary colour.
- The bridge serves the bundle with immutable caching for content-hashed files and revalidation for the rest. Home
  Assistant's Ingress drops `Accept-Encoding` and does not compress WebAssembly, so the page loads `.wasm.gz` and
  inflates it in the browser: about 5 MB on the first load instead of 15 MB.
- New option `web_sign_in_callback` (optional): pins the return address after sign-in.
- `/api/ws/echo`: a WebSocket echo that checks WebSockets pass Ingress.

## 0.6.431.1 (2026-10-29)

- **Dieter 0.6.431 from fork release v0.6.431-antigravity.6.** The bundled daemon now contains upstream Dieter through
  v0.6.431 (Mail chart rendering, table layout and Android scrolling in the apps); upstream changed nothing in the
  daemon, its CLI or the harness runtime in between, so the add-on needed no other change. The fork release is
  also the first whose own CI is green (harness catalog test for the sixth harness, release-scoped caches for the
  publishing job). The harness runtime archive and its sources are unchanged, so the bridge reuses the staged runtime
  instead of downloading ~775 MB again. `SHA256SUMS` was verified with cosign against the fork's release workflow at
  the tag. The version now starts at the bundled release again: 0.6.431.1 follows 0.6.429.57.

## 0.6.429.57 (2026-10-29)

- **Dieter from fork release v0.6.429-antigravity.5: the board state rebuilds ~4× faster** (0.96 s → 0.22 s on a Pi 5
  with 340 cards). The daemon rebuilds the state of all projects after every metadata change, several times a second
  while agents run, and holds its writer lock while it does; the page's board poll, `dieter status` and `dieter project
  list` asked for it each time, and card creates, moves and transcript appends waited behind it. A peer-store query
  walked the whole table from the start once per card, and every card opened its own snapshot; both are fixed in the
  fork, and the state is byte-identical. Measured with a profiling harness on a copy of this store (see the fork's
  changelog). The harness runtime archive is unchanged, so the bridge reuses the staged runtime instead of downloading
  ~775 MB again. `SHA256SUMS` was verified with cosign against the fork's release workflow at the tag.

## 0.6.429.56 (2026-10-29)

- Fixed: scrolling down the Tools tab (and other pages with a large title) in a tall window, e.g. the full-page kiosk view,
  threw you back to the top, so the end of the list was out of reach. Collapsing the big title made the list taller; when it
  overflowed by less than the title's height, the scroll range vanished and the page reset to the top. The title now
  collapses only when the list still scrolls afterwards.

## 0.6.429.55 (2026-10-29)

- Dreamfall: a **weekday picker** below the times in New Dreamfall board and Settings, one check box per day, Monday to
  Sunday, all selected by default. The two agents run only on the checked days (at least one is required); the stage
  headers show them ("Mon Wed Fri 02:00" instead of "daily 02:00").

## 0.6.429.54 (2026-10-29)

- **Work folder retention is a page setting**: Tools → Settings → **Keep finished work folders** (Never remove, 7, 14, 30,
  60, 90, 180 or 365 days; any whole number 0-3650 through `POST /api/settings`). 0 keeps every folder, 30 removes a
  folder 30 days after its task went to Done or was archived. It replaces the add-on option `worktree_retention_days`
  of 0.6.429.52 (an install that changed it falls back to the default of 14 days until set here). A change is
  applied at once, not at the next hourly sweep.
- The same retention applies to the cards of a **Dreamfall Implementation board**: once started (any lane but Todo) and
  untouched for that long, their work folder goes whatever lane they sit in (nobody moves picked cards to Done; they stay
  in Review). Merged or commit-free work only, like every folder: a picked card with unmerged commits stays.

## 0.6.429.53 (2026-10-29)

- The Inbox list is 60 px wider on desktop (460 instead of 400 px): the **Review** filter chip, with its count, was cut off
  at the right edge.

## 0.6.429.52 (2026-10-29)

- **Housekeeping of work folders**: the daemon keeps the folder of every worktree card forever (28 folders, 940 MB after
  two days). The bridge now removes, once an hour, the folder and branch of a card that has been in Done or archived and
  untouched for 14 days (option `worktree_retention_days`, 0 = off). It uses the daemon's own operations (`workspace run
  --kind cleanup`; for a squash-merged pull request, whose branch Git does not see as merged, `discard` once GitHub
  reports a merged pull request ending at the folder's last commit). Unmerged work and running cards stay.
  `python3 -m unittest discover -s dieter/tests -p 'housekeeping_test.py'`.

## 0.6.429.51 (2026-10-29)

- New task and New chat now start with **New worktree** and **Publishing → Pull request** selected (before: Project
  directory and the board's default). Switch back to Project directory, or pick Board default / Manual / Push to base
  under Publishing, for a single card.

## 0.6.429.50 (2026-10-29)

- **Start (and Add) in New task and New chat answer in about 0.1 s** instead of several seconds. The bridge created the
  card in ~0.1 s, then waited for a full state poll that started after the create so the page would find it in
  `api/state`; one poll takes ~5 s on a Pi 5 with three projects (`card list` per project), and a poll already running
  had to finish first, so 5-10 s in all. The bridge now puts the new card or chat into its state at once, from the
  create's answer (a started one as running), keeps it there through polls that began before the create and lets the
  next poll that reads it (since 0.6.429.46 that is a `card show` of the new card, ~0.05 s, not a full listing)
  replace it with the daemon's copy; a poll that re-read only other cards keeps it.
  `python3 -m unittest discover -s dieter/tests -p 'state_test.py'` (8, on the fake CLI of `bridge_poll_test.py`).

## 0.6.429.49 (2026-10-29)

- Dreamfall: picked cards said **Running** for hours after their agents had finished. Dieter's board instructions move
  a card to Review only once its pull request exists, and in a project without a git remote there is none, so the
  agents left their cards in Running. A picked card's brief now says to move it to Review when the work is committed
  and checked, without a pull request if the project has no remote. On the board, an Implementation card whose agent is
  idle in Running shows **Finished · still in Running** with a **Review** button, and the column counts those as
  finished, not running.

## 0.6.429.48 (2026-10-29)

- **Dreamfall's Pick and Start answer the click at once.** Until now nothing happened for 1-5 s after Pick (in its
  dialog) or Start: the page waited for the action, then fetched the whole state (~700 KB) again before it drew
  anything. Now the card shows "Picking…", "Starting…" (also Promote, Release, Park, Back to the queue) with a
  spinner within ~0.1 s, ignores further clicks and stays busy until the state that shows the change has arrived. The
  bridge's answer names that state (`rev`), and the page waits for it on the event stream instead of fetching the
  state again; only when the stream is down or 5 s late does it fetch.
- The event stream sends at most one state per 0.3 s (`BRIDGE_PUSH_GAP_S`). A click came as a burst of 8-10 full states
  (~800 KB each) within 2 s, and the page parsed and drew every one. Now 3-6 are sent.
- The action itself still takes as long as the daemon needs: on a Pi with 300 cards `card create` alone takes ~2 s,
  a move ~0.5 s, a start ~0.6 s, and more while agents run. Running the move next to the create saved nothing (the
  daemon was slower at both).

## 0.6.429.47 (2026-10-29)

- **Mail tab**: the page lists Dieter Mail. Until now the tab was a placeholder that showed "No mail" while the
  add-on's mailbox held messages. Modelled on the Android app: Inbox, Flagged, Unread, Archive and Trash with
  counts, date groups, search, a project/sender/important filter, an unread badge on the tab, read/unread, flag,
  archive, Trash and delete with Undo, Mark all read and Empty Trash. A message shows its sender, **Open
  conversation** and the body as GitHub-flavored Markdown, with vega-lite, vega and mermaid charts drawn as SVG
  (renderers from jsDelivr, pinned with integrity hashes; external data blocked; a broken chart keeps its source).
  Bridge: `/api/state` carries the mail headers (listed every 10 s), `GET /api/mail/ID`, `POST /api/mail/mark`,
  `POST /api/mail/delete`.
- **vl-convert** for the agents (1.9.0, pinned by checksum, ≈ 80 MB): `vl-convert vl2png -i chart.json -o chart.png`
  draws a Vega-Lite or Vega chart with bundled fonts and no browser. Agents that mail reports used to assemble their
  own Vega setup with npm and an SVG rasterizer to see their charts first. Claude Code's note (`/data/.claude/CLAUDE.md`,
  replaced while unedited) now tells agents to render a chart and look at it before mailing it as a `vega-lite` fence.

## 0.6.429.46 (2026-10-29)

- **The bridge no longer keeps a Pi's CPU busy** (load 4-5 on 4 cores, 72 °C, overnight since 0.6.429.41). Every
  `card list` costs the daemon 1-6 CPU-seconds on a Pi with ~300 cards, since it reads every card file whatever the
  filters, and the bridge sent many of them. Since .41 every change frame of `dieter watch changes` (one every few
  seconds per running agent) started a full poll, and since .42 the Dreamfall cache listed every lane of every stage
  board every 10 s. Now:
  - a change frame re-reads only the cards and chats it names (`card show`, ~0.05 s); another machine's card re-reads
    its project's list; projects, boards and checkouts still list everything. Frames whose only news is an owned card's
    `updatedAt` and token usage, kv records (Dreamfall watches those itself) and schedules start no poll at all;
  - the full poll runs every 60 s while the change stream runs (30 s before), and after one that listed, the next waits
    four times as long as that one took, unless only cards are re-read or a click waits for it;
  - the Dreamfall cache makes no `card list` calls of its own: a project with stage boards is listed whole in the full
    poll and the stage boards' lanes are taken from that list; changed cards come from the `card show` re-reads.

  Measured on the Pi with a second bridge next to the live one: the .45 bridge added 78 points of daemon CPU (66 `card
  list` in 2 min), the fixed one 20 (6 `card list`, 3 `card show`); both served the same `/api/state`, except that
  cards now show their token count: `card list` sends it in snake_case and the board read 0. `python3 -m unittest
  discover -s dieter/tests -p 'bridge_poll_test.py'` (16), `dreamfall_test.py` (32).

## 0.6.429.45 (2026-10-29)

- Dreamfall: a fourth section in New Dreamfall board and Settings, **Implementation**: instructions for every card you
  pick (added to the picked card's brief, e.g. "add a chart of the result and mail me a short report with it") and
  **Picked cards may mail me**, which creates picked cards with mail access (`--mail`, fixed at creation; before, a picked
  card's agent could not send mail at all). The brief then also says how to mail and that vega-lite and mermaid fences
  show as charts in Dieter Mail. The Implementation column notes when picked cards get them.

## 0.6.429.44 (2026-10-29)

- Dreamfall: the board counted every card on its three stage boards as an idea, so an idea promoted to Navigation and
  picked for Implementation counted three times (76 instead of 56). It now counts each idea once, by the card it started
  as, from the records; released ideas beyond the newest 40 shown on the board are included.

## 0.6.429.43 (2026-10-29)

- **Dreamfall: Pick, Park, Back to the queue and Start answer in well under a second** (5-10 s before on a Pi). A pick
  listed the Implementation board twice (`card list` takes ~1 s against the live daemon), then waited for all four lanes
  of both stage boards and a full state poll before the page saw it. The transfer now scans the bridge's cached cards of
  the destination board for an earlier copy, the action reports the cards' new lanes, and the bridge applies them to its
  cache and state at once; the boards are re-read in the background. `python3 -m unittest discover -s dieter/tests -p
  'dreamfall_test.py'` (24).

## 0.6.429.42 (2026-10-29)

- **Dreamfall boards**: a funnel for ideas next to a project's Kanban boards (project ⋮ → New Dreamfall board…, or New
  board → Dreamfall board). Its three inputs: the categories to enumerate, the enumeration goal (new ideas per category
  and night) and the navigation logic (the person's plain-language rules, prefilled and editable in Settings). Three stage
  boards and two schedules: an Enumeration agent writes new ideas every night, a Navigation agent judges each one by
  that logic later that night (strong ones are copied to Navigation with a link to their source,
  the rest released to Review), and only the person picks what goes to Implementation. The page shows the stages side by
  side under a funnel (tabs on a phone), the run of each night, verdicts with the agent's reason and score, and for every
  idea its provenance across the stages and its trail (who did what, when and why). Pick, Park, Back to the queue,
  promote a released idea, run a stage now, pause the schedules, settings, remove (the boards stay). The agents use the new
  `dreamfall` command, which records every step in Dieter's kv store (namespace `dreamfall`), makes reruns idempotent,
  lets Navigation wait for a still running Enumeration and refuses any agent write to Implementation. Bridge:
  `POST /api/dreamfall[/ID/update|delete|run|act]`, `GET /api/dreamfall/card/ID`; the stage boards' cards are read per
  board, so a busy pipeline is not cut off by the 200-card project list. Tests: `tests/dreamfall_test.py`,
  `tests/e2e/dreamfall.spec.mjs`.

## 0.6.429.41 (2026-10-28)

- Faster page. The bridge polled the daemon every 8 s with nine `dieter` calls one after the other (~7 s on a
  Pi 5 with three projects), and every action (move, start, rename, archive, send, stop, create) waited for such a
  full poll before it answered: a change reached the page after 8–20 s, a click took 7–19 s. Now the bridge follows
  the daemon's change stream (`dieter watch changes`) and polls right after a change, with the calls in parallel and
  one `chat list` for all projects instead of one per project (~1–1.5 s); the timed poll is a 30 s safety net (8 s
  while the stream is down). A change reaches the page in about a second. Move, start, rename and archive show at
  once and answer in under 0.1 s, send and stop no longer wait for a poll, creating waits for one fresh poll. A card
  summary whose only news is its activity time does not count as a change (a running agent bumps it every few
  seconds). Machines, quota and harnesses are polled on their own thread, so a slow quota probe (up to a minute) no
  longer holds up the board.
- Conversation of a running agent: after the first complete conversation the stream sends only what changed
  (`GET /api/{card|chat}/ID/stream?delta=1`, event `delta`): changed fields, new messages, and of a message the page
  already has the parts from the first changed one. A busy agent's conversation sent ~36 MB in 40 s (the whole
  ~200 KB conversation about three times a second, each parsed and drawn by the phone), now ~1 MB. Pages from
  before the update keep getting complete conversations.

## 0.6.429.40 (2026-10-28)

- Board: the lanes stand side by side only when all of them fit the screen (at least 240 px each). Otherwise
  the board shows the phone's lane tabs, so an iPad in portrait (744–1024 px) no longer has to be scrolled
  sideways. Turning the iPad switches between the two layouts. In landscape (from about 1110 px) the lanes stay
  side by side and share the width.

## 0.6.429.39 (2026-10-28)

- Tools → Machines: a machine's screen showed the add-on host's own system and load figures (Cortex-A76,
  4 cores, 8 GB) for every machine. It now asks for that machine's telemetry (`GET /api/machine/ID/info`,
  `dieter machine info ID`, refreshed every 20 s while open), so the workstation shows its own CPU, memory,
  storage and GPU. The route caption no longer reads "loopback": a daemon's loopback address is of no use to
  anyone else, so a machine without a LAN or Tailscale candidate shows "relay", which is how the apps and
  the add-on reach it.

## 0.6.429.38 (2026-10-28)

- GitHub CLI for the daemon and the agents: `gh` 2.103.3 is in the image, signed in at start with the
  `github_token` option (`/data/.config/gh/hosts.yml`; git takes the token from gh instead of
  `/data/.git-credentials`, which is removed; without `gh` or network the plain store stays). Dieter's own
  pull-request operations (`create_pr`, `refresh_pr`, `merge_pr`, until now "GitHub CLI is not installed on the
  daemon host") work, and agents read checks and Actions runs and edit pull requests. The Claude Code note in
  `CLAUDE.md` (replaced only while unedited) explains the expected use. The token needs Pull requests
  read/write and Actions read on top of Contents, see the Documentation tab.
- Publishing mode (Dieter's remote publish mode: manual, pull request, push to base) on the page: New board sets
  it, a board's ⋮ menu → **Publishing…** changes it (`POST /api/board/ID/git`), New task → New worktree →
  **Publishing** overrides it for the card. New task now also passes the Access switches (vault, mail) and the
  base branch to the card, which it had silently dropped.

## 0.6.429.37 (2026-10-28)

- Push notifications open their own task: the Home Assistant package now links each push to
  `/4d8f2a84_dieter/card/<card id>`. Home Assistant's app panel hands that route to the Dieter page, which
  opens the card's conversation, also when the page is already open (before, the card came from
  `/api/focus`, read only when the page loaded, and that one slot held the last push's card, so an older
  notification opened the newest card). The page then sets the panel URL back to `/4d8f2a84_dieter`, so the
  next tap works again. `/api/focus` stays for older copies of the package. Update the automation's `url`
  variable in your copy of `homeassistant/packages/dieter.yaml`.

## 0.6.429.36 (2026-10-28)

- Agent instructions in Settings: Tools → Settings → **Agent instructions** edits the text that opens every
  turn's instructions for every agent and project (Dieter's global prompt template), plus the parts for board
  cards and standalone chats. The editor lists the template variables and the projects or boards with their own
  template, refuses what the daemon would reject (unknown variable, missing instructions block), and resets a
  text to Dieter's default. Bridge: `GET`/`POST /api/prompts` (`dieter prompt show`/`update`).

## 0.6.429.35 (2026-10-28)

- Agents can read Home Assistant's logs: new command `ha-logs` in the image. It prints the system log (warnings
  and errors, as in Settings → System → Logs) over the WebSocket API with filters instead of `hass-cli raw ws
  system_log/list`'s unfiltered YAML dump: `--level`, `--logger`, `--grep`, `--since 1h`, `-n`, `--trace`, `--json`;
  `--full` reads the log file where core serves one (not on Home Assistant OS). The Claude Code note in
  `CLAUDE.md` (replaced only while unedited) mentions it, and no longer says WebSocket commands don't work through
  the proxy: they do.

## 0.6.429.34 (2026-10-28)

- Boards in any order: drag a board row on the Projects list or a project's page to a new place (mouse: press
  and drag; touch: long press, then drag; a plain swipe still scrolls). The order applies everywhere the page
  lists boards (pickers, Switch board) and is kept by the bridge in `/data/bridge_settings.json`, so every device
  shows the same order; a new board is added at the end of its project. Bridge: `POST /api/boards/order`.

## 0.6.429.33 (2026-10-28)

- Chats are sorted newest first (by last activity), in the Pinned section and inside each project group.

## 0.6.429.31 (2026-10-28)

- New board, as in the Dieter app: the project's ⋮ menu (Projects list and project page) and the board's ⋮ menu
  have **New board…**, a sheet with the name and the **Review lane** switch (on: Todo, Running, Review, Done; off:
  without Review). The board is created next to the project's other boards and opens. Bridge: `POST /api/board`.

## 0.6.429.30 (2026-10-28)

- The check badge on a conversation's project avatar (done and unread rows) is green instead of grey.

## 0.6.429.29 (2026-10-28)

- The chat shows images from the agent. Tool calls whose output carries an image (Claude Code's Read of an
  image file, browser screenshots) show it under the tool row and are no longer folded into the activity
  summary; Markdown `![caption](path)` in a reply shows the file from the conversation's checkout (relative
  path, or absolute inside the checkout or worktree). Tap to view full screen. New bridge endpoints
  `/api/{card|chat}/ID/tool-image` and `/api/{card|chat}/ID/image`.
- The add-on's `~/.claude/CLAUDE.md` (replaced only while unedited) tells Claude Code that it can put images in its reply.

## 0.6.429.28 (2026-10-28)

- Tools → Machines no longer shows "No machines" for up to a minute after the add-on starts: the first
  `dieter machine info` often runs before the daemon listens, and the bridge only retried quickly while the
  harness catalog was empty. It now also retries every 10 s while the machine list is empty, and logs a failed
  `machine info` once (it was silent). This was the intermittent e2e failure "Tools pages: Machines…".
- architecture.mmd: the daemon box names the bundled release (0.6.429-antigravity.4).

## 0.6.429.27 (2026-10-28)

- Antigravity harness, now the add-on's own copy of the fork's adapter (`patches/antigravity-runtime.mjs`,
  laid over the pinned fork sources at image build; the build fails when the fork's file changes so the
  two get merged), with unit tests (`tests/unit.sh`):
  - **Host tools**: present_content and the background process tools work with Antigravity. The adapter
    serves a turn's tools on a private local endpoint, and `agy` reaches them as the MCP server `dieter`
    (stdio proxy `antigravity-host-tools.mjs`, which the adapter registers once with `agy mcp add`; it
    offers no tools outside a Dieter turn). agy's own `call_mcp_tool` step and its read of the tool
    schema are not shown twice.
  - **Context usage**: each model request reports its own usage (`finish-step`), so the context shown for a
    running turn is the request's input (e.g. 7 k tokens) instead of the sum of all requests of the turn
    (which grew to 20–30 k within a few steps). The turn total stays the sum.
  - **Compaction**: Dieter's compaction runs agy's own `/compact` on the stored conversation.
  - **Lost conversation**: when agy no longer has the stored conversation (it warns `conversation … not
    found` and would silently start a fresh one without Dieter's instructions), the turn restarts in a
    fresh conversation with the instructions and says so in the chat.
  - agy's `system_message` and `checkpoint` steps are no longer passed through as raw events.
  - The `--effort` fix of 0.6.429.12 is part of the copy (the string patch is gone).
  - `dieter/.dockerignore` keeps the e2e tests, the unit test file and Python caches out of the image build context.
- Bridge: a fork release whose harness runtime archive is unchanged (same sha256) takes over the staged
  runtime directory of the previous version by renaming it instead of downloading ~775 MB again. This is
  the reuse 0.6.429.26 announced; that release only changed the version, so the bridge downloaded the
  archive once more under the new name. DOCS.md describes Re-enroll (new daemon ID,
  checkouts re-registered at the first peer sync); DEPLOY.md keeps the upstream screening notes.
- Merge of two lines of work of 2026-10-28: 0.6.429.24–.26 published from another machine (removed-machine
  handling, fork release 4) and the add-on's local commits for the same fork bump and the Antigravity
  harness, which had reused the numbers .24 and .25.

## 0.6.429.26 (2026-10-28)

- Dieter from fork release v0.6.429-antigravity.4: after **Re-enroll** (a new daemon ID under the same
  account) the daemon registers the add-on's project checkouts again under its new ID at the first peer
  sync round and drops the stale local registrations, so New task works without re-attaching anything by
  hand. Conversations and schedules created before the re-enroll stay with the old ID as read-only
  history; shared ownership cannot change, so that part is permanent (the Removed-machine badge from
  0.6.429.24 marks them). Between the new enrollment and the first sync round (device trust must be
  approved first) the daemon refuses writes instead of publishing them as the old ID; such writes used
  to stall the sync with other machines for good.
- The harness runtime archives of the new release are byte-identical to the previous one, so the staged
  runtime is reused; only the daemon binaries change.

## 0.6.429.25 (2026-10-28)

- Fix for 0.6.429.24: the "removed machine" detection only applies to an enrolled daemon (an unenrolled
  daemon owns every record it holds, so its cards and checkouts stayed usable before but were wrongly
  marked as orphaned, which hid the composer). The daemon's own id (`runtime/daemon.json`) counts as known.
- E2E note: "Edit… changes the title and task" and "Schedules: create … pause" fail on 0.6.429.23 too
  (same 25 passed / 2 failed with the shared test cache); unrelated to this change.

## 0.6.429.24 (2026-10-28)

- Checkouts and cards of a machine identity the account no longer knows (the add-on's daemon was removed
  from the account and re-enrolled, so its records stay owned by the old identity) are handled: such
  checkouts are hidden from "Run on", so a new task no longer fails with `rpc error: code = NotFound`;
  such cards show "Owned by a removed machine" instead of polling a conversation that cannot be opened,
  and carry a "Removed machine" badge on the board.

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
