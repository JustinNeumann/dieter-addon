// The add-on's own features on the new page (next/): the add-on core in the web host serves them from the
// bridge, the Compose UI only draws its slices and sends its commands. `?demo` keeps Dieter's data in the page
// (no gateway account in the test); the add-on features reach the real web host, bridge, daemon and mock harness.
import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

const stamp = Date.now().toString(36);
const aria = (page) => page.locator("body").ariaSnapshot();

async function open(page, query) {
  await page.goto("next/?demo&" + query);
  await page.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await page.waitForTimeout(1500);
}

/** Compose draws on a canvas; the accessibility DOM says where. */
async function tap(page, locator) {
  await expect(locator.first()).toBeVisible({ timeout: 30_000 });
  const box = await locator.first().boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

async function typeInto(page, locator, text) {
  await tap(page, locator);
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type(text);
  await page.waitForTimeout(500); // the accessibility tree follows the layout a moment later
}

// Compose 1.12 (web) keeps a closed popup's entries (menu, picker, dialog) in the accessibility tree until
// another popup opens (its semantics listener follows only the newest owner; fixed in 1.13). The add-on's
// state lives in the web host, so after a popup the tests open the page again.

/** Opens a page of More; a dialog left by an earlier run (enrollment, Claude sign-in: shown until put away) is closed first. */
async function openTool(page, tool, section, dialog) {
  const query = `script=more%20${tool}`;
  await open(page, query);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toMatch(new RegExp(`${section}|${dialog}`));
  if (!(await aria(page)).includes(dialog)) return;
  await tap(page, page.getByRole("button", { name: /^(Close|Cancel)$/ }));
  await open(page, query);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain(section);
}
// the gateway enrollment is on Devices, the add-on's SSH key in Settings (More has no Machines page since 0.6.432)
const openDevices = (page) => openTool(page, "devices", "Gateway of this add-on", "Enroll with the gateway");
const openUsage = (page) => openTool(page, "usage", "Claude Code account of this add-on", "Sign in to Claude");

/** Scrolls the detail pane until [locator] is on screen (lazy lists compose only what is near it, so a row can exist
 *  just below the visible part; a step of 400 px cannot pass it in a 900 px window). */
async function scrollTo(page, locator, x = 900) {
  for (let i = 0; i < 60 && !(await locator.first().isVisible().catch(() => false)); i++) { // the slice may still be on its way
    await page.mouse.move(x, 500);
    await page.mouse.wheel(0, 400);
    await page.waitForTimeout(250);
  }
  await expect(locator.first()).toBeVisible();
}

test("Settings: the add-on's SSH key, and the connection test reports a failure for an unreachable host", async ({ page, request }) => {
  const key = await (await request.get("api/ssh")).json();
  await open(page, "script=more%20settings");
  await scrollTo(page, page.getByRole("textbox", { name: "Test connection to user@host" }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain(key.public_key);
  expect(await aria(page)).toContain("SSH key of this add-on");
  await typeInto(page, page.getByRole("textbox", { name: "Test connection to user@host" }), "nobody@127.0.0.1:1");
  await tap(page, page.getByRole("button", { name: "Test", exact: true }));
  await expect.poll(() => aria(page), { timeout: 40_000 }).toMatch(/Failed:/);
});

test("Devices: the add-on offers enrollment with the gateway; an unreachable gateway is reported, Close puts it away", async ({ page, request }) => {
  await openDevices(page);
  // the add-on core's rule: an https origin (with a port of 2 to 5 digits)
  await typeInto(page, page.getByRole("textbox", { name: "Gateway address" }), "https://127.0.0.1:1");
  await expect.poll(() => aria(page)).toContain("The gateway must be an https origin");
  await typeInto(page, page.getByRole("textbox", { name: "Gateway address" }), "https://127.0.0.1:19");
  await tap(page, page.getByRole("button", { name: "Enroll", exact: true }));
  await expect.poll(() => aria(page), { timeout: 60_000 }).toMatch(/Enroll with the gateway[\s\S]*(refused|connect|enroll)[\s\S]*button "Close"/i);
  await tap(page, page.getByRole("button", { name: "Close", exact: true }));
  expect((await (await request.get("api/gateway")).json()).enrolled).toBe(false);
  await open(page, "script=more%20devices");
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Gateway of this add-on");
  expect(await aria(page)).not.toContain("Enroll with the gateway"); // the attempt was put away
});

test("Usage: Claude sign-in opens claude.com, Cancel aborts, a wrong code shows an error", async ({ page, request }) => {
  await openUsage(page);
  expect(await aria(page)).toContain("Not signed in");
  await tap(page, page.getByRole("button", { name: "Sign in", exact: true }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Open claude.com");
  expect((await (await request.get("api/claude/login")).json()).running).toBe(true);
  await tap(page, page.getByRole("button", { name: "Cancel", exact: true }));
  await expect.poll(async () => (await (await request.get("api/claude/login")).json()).running, { timeout: 10_000 }).toBe(false);
  await open(page, "script=more%20usage");
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Claude Code account of this add-on");
  expect(await aria(page)).not.toContain("Sign in to Claude");
  // a wrong code is rejected by the CLI; the dialog says why and offers another attempt
  await tap(page, page.getByRole("button", { name: "Sign in", exact: true }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Open claude.com");
  await typeInto(page, page.getByRole("textbox", { name: "Claude code" }), "not-a-real-code#state");
  await tap(page, page.getByRole("button", { name: "Sign in", exact: true })); // the dialog's (the tree shows only the dialog)
  await expect.poll(() => aria(page), { timeout: 60_000 }).toMatch(/button "Try again"/);
  expect((await (await request.get("api/state")).json()).claude_auth.signed_in).toBe(false);
  expect(await aria(page)).toContain("Sign in to Claude");
  await tap(page, page.getByRole("button", { name: "Close", exact: true }));
  await expect.poll(async () => (await (await request.get("api/claude/login")).json()).running, { timeout: 10_000 }).toBe(false);
});

test("Settings: the add-on's work-folder retention is saved to the bridge", async ({ page, request }) => {
  const before = await (await request.get("api/settings")).json();
  await open(page, "script=more%20settings");
  const row = page.getByRole("button", { name: /Keep finished work folders/ });
  await scrollTo(page, row);
  await tap(page, row);
  await tap(page, page.getByRole("button", { name: "30 days", exact: true }));
  await expect.poll(async () => (await (await request.get("api/settings")).json()).worktree_retention_days, { timeout: 15_000 }).toBe(30);
  await open(page, "script=more%20settings");
  await scrollTo(page, page.getByRole("button", { name: "Keep finished work folders 30 days" }));
  await request.post("api/settings", { data: { worktree_retention_days: before.worktree_retention_days } });
});

test("Questions: the Inbox lists the waiting conversation; the answer and a decline reach the worker's poll", async ({ page, request }) => {
  const cardId = "c-web"; // a card of the page's demo data
  for (const q of (await (await request.get("api/questions")).json()).questions.filter((q) => q.session === cardId))
    await request.post(`api/questions/${q.id}/answer`, { data: { action: "declined" } }); // left by an earlier run
  const ask = (id) => request.post("api/questions", { data: { id, session: cardId, input: { allowPartialAnswers: false, questions: [{ id: "question-1", question: "Which colour?", header: "Colour", options: [{ id: "red", label: "Red" }, { id: "blue", label: "Blue", description: "calm" }], allowFreeForm: true }] } } });
  // the worker polls with the token its registration returned
  const tokens = {};
  const register = async (id) => { const r = await ask(id); tokens[id] = (await r.json()).token; return r; };
  const poll = (id) => JSON.parse(execFileSync("docker", ["exec", "-u", "dieter", "dieter-e2e", "curl", "-s", "-H", `X-Question-Token: ${tokens[id]}`,
    `http://127.0.0.1:8096/api/questions/${id}`]).toString());   // as the harness worker polls, on the operator
  expect((await register(`q-${stamp}-1`)).ok()).toBeTruthy();
  await open(page, "");
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Waiting for your answer");
  await tap(page, page.getByRole("button", { name: /Compose web UI behind Ingress Which colour\?/ }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("The agent asks");
  await tap(page, page.getByRole("button", { name: "Blue calm" }));
  await typeInto(page, page.getByRole("textbox", { name: "Or your own answer" }), "and green");
  await tap(page, page.getByRole("button", { name: "Answer", exact: true }));
  await expect
    .poll(async () => poll(`q-${stamp}-1`), { timeout: 15_000 })
    .toEqual({ done: true, output: { action: "answered", answers: { "question-1": { optionIds: ["blue"], freeform: "and green" } } } });
  await expect.poll(() => aria(page)).not.toContain("The agent asks");
  // a second question, declined
  expect((await register(`q-${stamp}-2`)).ok()).toBeTruthy();
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("The agent asks");
  await tap(page, page.getByRole("button", { name: "Decline", exact: true }));
  await expect.poll(async () => poll(`q-${stamp}-2`).output?.action, { timeout: 15_000 }).toBe("declined");
});

test("Projects: Add → New empty repository creates and registers a project", async ({ page, request }) => {
  const name = `Empty-${stamp}`;
  await open(page, "script=tab%20projects");
  await tap(page, page.getByRole("button", { name: "Add", exact: true }));
  await tap(page, page.getByRole("button", { name: "New empty repository" }));
  // Compose (web) keeps a closed menu's entries in the accessibility tree until another popup opens, so the
  // form is filled by keyboard: Tab reaches the name, Enter submits.
  await page.waitForTimeout(1000);
  await page.keyboard.press("Tab");
  await page.keyboard.type(name);
  await page.keyboard.press("Enter");
  await expect.poll(async () => (await (await request.get("api/state")).json()).projects.map((p) => p.name), { timeout: 60_000 }).toContain(name);
});

test("Push: the new page takes over the card an HA automation registered for the next load (api/focus)", async ({ page, request }) => {
  // without a gateway account the card cannot open here; the page must still take the focus
  const id = `c_focus${stamp}`;
  expect((await request.post("api/focus", { data: { id } })).ok()).toBeTruthy();
  expect((await (await request.get("api/focus")).json()).id).toBe(id);
  await page.goto("next/");
  await page.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await expect.poll(async () => (await (await request.get("api/focus")).json()).id, { timeout: 15_000 }).toBeNull();
});

test("Settings: agent instructions are edited and reset through the add-on core", async ({ page, request }) => {
  const prompts = async () => (await request.get("api/prompts")).json();
  const before = await prompts();
  await open(page, "script=more%20settings");
  await scrollTo(page, page.getByRole("button", { name: /^Board cards/ }));
  expect(await aria(page)).toContain("Standalone chats");
  await tap(page, page.getByRole("button", { name: /^Board cards/ }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("{{card.title}}");      // the variables
  await typeInto(page, page.getByRole("textbox", { name: "Instructions" }), `Use the Dieter CLI. ${stamp}`);
  await tap(page, page.getByRole("button", { name: "Save", exact: true }));
  await expect.poll(async () => (await prompts()).board_skill, { timeout: 20_000 }).toBe(`Use the Dieter CLI. ${stamp}`);
  // Every agent: the two blocks must stay; the page says so before anything is sent
  await open(page, "script=more%20settings");
  await scrollTo(page, page.getByRole("button", { name: /^Every agent/ }));
  await tap(page, page.getByRole("button", { name: /^Every agent/ }));
  await typeInto(page, page.getByRole("textbox", { name: "Instructions" }), "Hello");
  await expect.poll(() => aria(page)).toContain("{{project.instructions_block}} must stay in the text exactly once.");
  expect((await prompts()).template).toBe(before.template);
  // Board cards back to Dieter's default
  await open(page, "script=more%20settings");
  await scrollTo(page, page.getByRole("button", { name: /^Board cards/ }));
  await tap(page, page.getByRole("button", { name: /^Board cards/ }));
  await scrollTo(page, page.getByRole("button", { name: /^Reset to Dieter's default/ }));
  await tap(page, page.getByRole("button", { name: /^Reset to Dieter's default/ }));
  await tap(page, page.getByRole("button", { name: "Reset", exact: true }));
  await expect.poll(async () => (await prompts()).board_skill, { timeout: 20_000 }).not.toContain(stamp);
});

test("Projects: a board dragged after a long press keeps its place for every device (the bridge's board order)", async ({ page, request }) => {
  // the demo's dieter project has Upstream, Releases and Website; start from that order
  expect((await request.post("api/boards/order", { data: { ids: ["b-dieter", "b-releases", "b-site"] } })).ok()).toBeTruthy();
  const shown = async () => {
    const tree = await aria(page);
    return ["Upstream", "Releases", "Website"].sort((a, b) => tree.indexOf(`button "${a}`) - tree.indexOf(`button "${b}`));
  };
  // the web host re-reads the bridge's settings when a page opens and its copy is older than 15 s
  await expect.poll(async () => { await open(page, "script=tab%20projects"); return shown(); }, { timeout: 60_000, intervals: [4_000] })
    .toEqual(["Upstream", "Releases", "Website"]);
  const row = (name) => page.getByRole("button", { name: new RegExp(`^${name} \\d`) }).first();
  const from = await row("Website").boundingBox(), to = await row("Upstream").boundingBox();
  await page.mouse.move(from.x + 60, from.y + from.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(900); // the long press
  await page.mouse.move(from.x + 60, to.y + to.height / 3, { steps: 12 });
  await page.mouse.up();
  await expect.poll(async () => (await (await request.get("api/settings")).json()).board_order.slice(0, 3), { timeout: 15_000 })
    .toEqual(["b-site", "b-dieter", "b-releases"]);
  await expect.poll(shown).toEqual(["Website", "Upstream", "Releases"]);
  await open(page, "script=tab%20projects"); // kept: another load (device) shows the same order
  await expect.poll(shown).toEqual(["Website", "Upstream", "Releases"]);
});
