# Dieter for Home Assistant

Run coding agents from your Home Assistant. This add-on puts [Dieter](https://github.com/dbpprt/dieter), a
daemon and app that runs **Claude Code**, **Codex** and **Antigravity** agents on boards of tasks, behind Home
Assistant's sidebar. Hand a task to an agent from your phone, let it work in its own Git worktree on your Raspberry
Pi or mini PC, and get a push notification when it needs you or is ready for review.

[![Open your Home Assistant instance and show the add add-on repository dialog with a specific repository URL pre-filled.](https://my.home-assistant.io/badges/supervisor_add_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_add_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2FJustinNeumann%2Fdieter-addon)

**[Try it in your browser](https://justinneumann.github.io/dieter-addon/)**: the add-on's page with sample projects,
conversations and a Dreamfall board you can pick ideas on — nothing to install, no account.

<p align="center">
  <img src="docs/screenshots/task-to-review.gif" alt="A task on the Atlas API Roadmap board from creation through the agent's live tool calls to Ready for review" width="90%">
</p>
<p align="center"><sub>A task on the Atlas API Roadmap board from creation to Review, in the live browser demo.</sub></p>

<p align="center">
  <img src="docs/screenshots/dreamfall-board.png" alt="A Dreamfall board for the Atlas API project: Enumeration, Navigation and Implementation stages side by side, in dark mode with the Dreamfall Pearl palette" width="90%">
</p>
<p align="center">
  <img src="docs/screenshots/phone-board.png" alt="The Navigation stage of the Dreamfall board on a phone, in dark mode with the Dreamfall Pearl palette" width="30%">
  &nbsp;
  <img src="docs/screenshots/phone-idea.png" alt="An idea's verdict, brief and provenance on a phone, in dark mode with the Dreamfall Pearl palette" width="30%">
</p>
<p align="center"><sub>A Dreamfall board with sample ideas for an example project, Atlas API, as the add-on shows it (Dreamfall Pearl palette, dark mode, desktop and phone).</sub></p>

## What you get

- **A task board per project.** Todo, Running, Review, Done; several agents work in parallel, each card in its own
  Git worktree. Chat with an agent while it works, answer its questions, attach images and files.
- **Three agent harnesses in one image:** Claude Code, Codex and Antigravity (Gemini, Claude and GPT-OSS models),
  with the quota of each shown in the page.
- **Pull requests included.** The image has the GitHub CLI: agents clone, push, open pull requests and read checks
  with one token you control; the daemon publishes a card's changes as a pull request.
- **Opens in HA, works on the phone.** The page is Dieter's own app compiled for the browser, served through
  Ingress: Home Assistant login, Nabu Casa and the Companion app work without opening a port.
- **Home Assistant on both sides.** Sensors for running and waiting tasks, a push notification that opens the
  card, services to create tasks from automations. Agents can use `hass-cli` and `ha-logs` to read and control
  your Home Assistant (optional, per conversation).
- **Schedules, Mail and Dreamfall.** Recurring tasks, reports from agents with charts in Dieter Mail, and
  *Dreamfall boards*: agents enumerate ideas every night, others judge them, and only your pick starts work.
- **Runs on a Raspberry Pi.** Prebuilt, signed images for aarch64 and amd64; no build on your device.

## What you need

- Home Assistant OS or Supervised (add-ons), on aarch64 (Raspberry Pi 4/5, Home Assistant Green/Yellow) or amd64.
- At least one provider login: a Claude subscription (sign in on the page), an OpenAI API key, or an Antigravity
  sign-in.
- Nothing else to install: the Dieter daemon, the agent CLIs (`claude`, `codex`, `agy`), `gh` and the page are all in
  the add-on's image.
- Access to a Dieter **gateway**, the one thing outside the add-on: the page, like the Dieter phone apps, signs in to
  one with your GitHub account. The default, `gateway.getdieter.com`, only admits accounts its operator has allowed;
  otherwise [run your own](https://github.com/dbpprt/dieter/blob/main/site/content/docs/gateway.md). See [the page](dieter/DOCS.md#the-page).
  The [live demo](https://justinneumann.github.io/dieter-addon/) (`…/next/?demo`) shows the page without any of it.
- Optional: a GitHub token, so agents can clone, push and open pull requests.

About 800 MB of agent runtime is downloaded on the first start.

## Install

1. Click the button above, or: Settings → Add-ons → Add-on store → ⋮ → **Repositories** → add
   `https://github.com/JustinNeumann/dieter-addon`.
2. Install **Dieter**, fill in the options you have (a Claude token, an OpenAI key or Antigravity credentials;
   optionally a GitHub token and your Git name and e-mail) and start it. The first start downloads the agent runtime.
3. Open **Dieter** in the sidebar and sign the page in to your Dieter gateway with GitHub, then approve the pairing
   code it shows on a device that is already trusted (the Dieter app, or `dieter device approve <code>`). A gateway you
   run yourself must accept your Home Assistant's address as a return address once; the steps are in
   [Signing in the page](dieter/DOCS.md#signing-in-the-page). Signed in, you can also sign in to Claude Code on the
   page (Tools → Usage) instead of pasting a token.
4. Optional: copy [`homeassistant/packages/dieter.yaml`](homeassistant/packages/dieter.yaml) into your
   configuration for sensors and push notifications.

Every option, the provider logins, the Home Assistant package, Dreamfall, schedules, the gateway and security notes
are in the add-on's Documentation tab ([`dieter/DOCS.md`](dieter/DOCS.md)); what changed in each release is in
[`dieter/CHANGELOG.md`](dieter/CHANGELOG.md).

## Security in short

Agents run arbitrary commands as an unprivileged user inside the container (AppArmor profile, no access to
`/config`, no Supervisor API). What a token can do, an agent can do: give the GitHub token only the permissions
[`DOCS.md`](dieter/DOCS.md#github-pull-requests-and-actions) lists and protect your base branch with a ruleset.

## Contributing

Issues and pull requests are welcome. The layout of the repository, building the page and the web host, and the
test suites are in [`docs/development.md`](docs/development.md); releasing and bumping the bundled Dieter fork in
[`docs/maintaining.md`](docs/maintaining.md); the design of the page in [`docs/PLAN.md`](docs/PLAN.md) and the
overall picture in [`docs/architecture.mmd`](docs/architecture.mmd).

## License

MIT ([`LICENSE`](LICENSE)), like Dieter. JetBrains Mono (`web/src/wasmJsMain/composeResources/files`) is under the SIL
Open Font License 1.1 (`OFL.txt` next to it).
