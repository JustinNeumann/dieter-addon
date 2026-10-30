// Dreamfall on the new page (next/): the add-on core in the web host decides what a board, an idea and the
// settings show; the bridge keeps the records. The ideas come from the `dreamfall` command in the add-on, as an
// agent run writes them. `?demo` keeps Dieter's own data in the page; Dreamfall reaches the real bridge.
import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

test.describe.configure({ mode: "serial" });

const stamp = Date.now().toString(36);
const PROJECT = `Garden-${stamp}`;
const aria = (page) => page.locator("body").ariaSnapshot();
let pid = "", pipe = null;

async function state(request) {
  return (await request.get("api/state")).json();
}

function inBox(card, ...args) {
  const env = ["-e", "HOME=/data", "-e", "DIETER_HOME=/data/dieter", ...(card ? ["-e", `DIETER_CARD_ID=${card}`] : [])];
  return execFileSync("docker", ["exec", "-u", "dieter", ...env, "dieter-e2e", ...args], { stdio: "pipe" }).toString();
}

async function runCard(request, role, after) {
  const s = pipe.stages.find((x) => x.role === role);
  let card;
  await expect.poll(async () => {
    card = (await state(request)).cards.filter((c) => c.board_id === s.board_id && c.title === s.run_title && Date.parse(c.created_at) > after)[0];
    return !!card;
  }, { timeout: 30_000 }).toBe(true);
  return card.id;
}

async function open(page, route) {
  await page.goto(`next/?demo&route=${route}`);
  await page.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await page.waitForTimeout(1500);
}

async function tap(page, locator) {
  await expect(locator.first()).toBeVisible({ timeout: 30_000 });
  const box = await locator.first().boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

/** Scrolls until [locator] is drawn (lazy lists compose only what is on screen). */
async function scrollTo(page, locator, dy = 300, x = 650) {
  for (let i = 0; i < 60 && !(await locator.count()); i++) {
    await page.mouse.move(x, 500);
    await page.mouse.wheel(0, dy);
    await page.waitForTimeout(250);
  }
  await expect(locator.first()).toBeVisible();
}

const ideaOn = (st, role, title) => st.cards.find((c) => c.board_id === pipe.stages.find((s) => s.role === role).board_id && c.title.includes(title));

test.beforeAll(async ({ request }) => {
  // a paused board on the mock agent, and one night: Enumeration writes four ideas, Navigation promotes two
  const made = await (await request.post("api/project", { data: { folder: PROJECT } })).json();
  const project = made.id || (await state(request)).projects.find((p) => p.name === PROJECT).id;
  const df = await (await request.post("api/dreamfall", {
    data: { project, name: "Nightly ideas", categories: "Watering\nLighting", per_category: 2, enabled: false, timezone: "Europe/Berlin", agent: { provider: "mock" } },
  })).json();
  expect(df.ok, JSON.stringify(df)).toBe(true);
  pid = df.id;
  pipe = (await state(request)).dreamfall.pipelines.find((p) => p.id === pid);
  let t0 = Date.now() - 5000;
  await request.post(`api/dreamfall/${pid}/run`, { data: { stage: "enumeration" } });
  const run = await runCard(request, "enumeration", t0);
  inBox(run, "dreamfall", "run", "begin");
  for (const [cat, title] of [["Watering", "Skip watering after rain"], ["Watering", "Soil moisture per bed"], ["Lighting", "Path lights follow sunset"], ["Lighting", "Disco mode"]])
    inBox(run, "dreamfall", "add", "--category", cat, "--title", title, "--brief", `**Idea:** ${title}.\n\n**Why it is valuable:** test.`);
  inBox(run, "dreamfall", "run", "end", "--note", "4 ideas");
  t0 = Date.now() - 5000;
  await request.post(`api/dreamfall/${pid}/run`, { data: { stage: "navigation" } });
  const nav = await runCard(request, "navigation", t0);
  inBox(nav, "dreamfall", "run", "begin");
  const queue = JSON.parse(inBox(nav, "dreamfall", "queue", "--json"));
  const id = (t) => queue.find((c) => c.title.includes(t)).id;
  inBox(nav, "dreamfall", "promote", id("Skip watering"), "--score", "9", "--rationale", "Saves water every rainy week.");
  inBox(nav, "dreamfall", "promote", id("Path lights"), "--score", "7", "--rationale", "Small and noticed every evening.");
  inBox(nav, "dreamfall", "release", id("Soil moisture"), "--score", "4", "--rationale", "Needs new hardware per bed.");
  inBox(nav, "dreamfall", "release", id("Disco"), "--score", "1", "--rationale", "Fun, but no improvement.");
  inBox(nav, "dreamfall", "run", "end", "--note", "4 judged, 2 promoted");
});

test("Tools → Dreamfall lists the board with the ideas waiting for the person", async ({ page }) => {
  await open(page, "dreamfall");
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Nightly ideas");
  const tree = await aria(page);
  expect(tree).toContain(`${PROJECT} · 4 ideas · paused`);
  expect(tree).toContain("2 waiting");
});

test("a board shows the night: the funnel, each stage's run, Navigation's queue with its verdicts", async ({ page }) => {
  await open(page, `dreamfall/${pid}`);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Navigation, 2 waiting");
  let tree = await aria(page);
  expect(tree).toContain("Enumeration, 0 waiting");
  expect(tree).toContain("2 of 4");                                  // of Enumeration's four judged ideas, two went on
  expect(tree).toMatch(/Run · today \d\d:\d\d – \d\d:\d\d · 4 judged, 2 promoted/);
  expect(tree).toContain("Promoted · 9/10 · Saves water every rainy week.");
  expect(tree).toContain("from Enumeration");
  // Enumeration: the queue is empty, the released ideas are folded
  await tap(page, page.getByRole("button", { name: /^Enumeration, / }));
  await expect.poll(() => aria(page)).toContain("No ideas waiting.");
  await tap(page, page.getByRole("button", { name: /^Released, / }));
  await expect.poll(() => aria(page)).toContain("Needs new hardware per bed.");
  // an idea by hand: New task on Enumeration's board, saved to the queue (Todo) instead of started
  await tap(page, page.getByRole("button", { name: "New idea", exact: true }));
  await expect.poll(() => aria(page)).toContain('textbox "What should we do?"');
  const form = await aria(page);
  expect(form).toMatch(/button "Add"\n/);                            // not "Start": no agent works on the idea
  expect(form).not.toMatch(/button "Start"\n/);
});

test("Pick copies an idea to Implementation as a Todo draft; the card waits there with Start", async ({ page, request }) => {
  await open(page, `dreamfall/${pid}`);
  const card = page.getByRole("button", { name: /Lighting, Path lights follow sunset/ });
  await expect(card).toBeVisible({ timeout: 30_000 });
  await tap(page, card.getByRole("button", { name: "Pick", exact: true }));
  // Compose gives the dialog no dialog role; while it is open the accessibility tree holds only the dialog
  await expect.poll(() => aria(page)).toContain("Pick for Implementation");
  await tap(page, page.getByRole("textbox", { name: "Note" }));
  await page.keyboard.type("before winter");
  await page.waitForTimeout(300);
  await tap(page, page.getByRole("button", { name: "Pick", exact: true }));
  let st;
  await expect.poll(async () => {
    st = await state(request);
    return st.dreamfall.records[ideaOn(st, "implementation", "Path lights")?.id]?.created_by?.name;
  }, { timeout: 30_000 }).toBe("E2E Tester"); // the person Ingress names, through the page's WebSocket
  const copy = ideaOn(st, "implementation", "Path lights");
  expect(copy.lane).toBe("todo");
  expect(st.dreamfall.records[copy.id].source_verdict.rationale).toBe("before winter");
  await expect.poll(async () => ideaOn(await state(request), "navigation", "Path lights").lane).toBe("done");
  await open(page, `dreamfall/${pid}`);
  await tap(page, page.getByRole("button", { name: /^Implementation, / }));
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Path lights follow sunset");
  const tree = await aria(page);
  expect(tree).toContain("Picked by E2E Tester");
  expect(tree).toMatch(/button "Start"/);
});

test("an idea's page shows its brief, provenance and trail; Park takes it out of the queue", async ({ page, request }) => {
  const idea = ideaOn(await state(request), "navigation", "Skip watering");
  await open(page, `idea/${idea.id}`);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Where it came from");
  const tree = await aria(page);
  expect(tree).toContain("Navigation · Waiting for you");
  expect(tree).toContain("Created on Enumeration");
  expect(tree).toContain("Judged: promote");
  expect(tree).toContain("Saves water every rainy week. Score 9/10.");
  expect(tree).toContain("Now: Waiting for you on Navigation");
  await tap(page, page.getByRole("button", { name: "Park", exact: true }));
  await expect.poll(() => aria(page)).toContain("It leaves your queue for later");
  await tap(page, page.getByRole("button", { name: "Park", exact: true }));
  await expect.poll(async () => ideaOn(await state(request), "navigation", "Skip watering").lane, { timeout: 30_000 }).toBe("review");
  await open(page, `idea/${idea.id}`);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Now: Parked on Navigation");
  expect(await aria(page)).toMatch(/button "Pick anyway"/);
});

test("the settings save through the add-on core; a bad time is refused before it is sent", async ({ page, request }) => {
  await open(page, `dreamfall/${pid}`);
  await tap(page, page.getByRole("button", { name: "Dreamfall options" }));
  await expect.poll(() => aria(page)).toContain("Settings…");
  const menu = await aria(page);
  for (const entry of ["Run Enumeration now", "Run Navigation now", "Resume the schedules", "Open a stage as a Kanban board", "Remove Dreamfall…"]) expect(menu).toContain(entry);
  // the settings by their link: after a menu the accessibility tree stays on it (Compose 1.12)
  await open(page, `dreamfall/${pid}/settings`);
  await expect.poll(() => aria(page), { timeout: 30_000 }).toContain("Dreamfall settings");
  expect(await aria(page)).toContain("Nightly ideas");
  const time = page.getByRole("textbox", { name: "Navigation runs at" });
  await scrollTo(page, time);
  await tap(page, time);
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("5");
  await scrollTo(page, page.getByText("Times are HH:MM, e.g. 02:00.", { exact: true })); // next to Save, at the end
  await scrollTo(page, time, -300);
  await tap(page, time);
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("04:45");
  await page.waitForTimeout(500);
  await expect.poll(() => aria(page)).not.toContain("Times are HH:MM");
  await tap(page, page.getByRole("button", { name: "Save", exact: true }));
  await expect.poll(async () => (await state(request)).dreamfall.pipelines.find((p) => p.id === pid).stages.find((s) => s.role === "navigation").cron, { timeout: 30_000 }).toBe("45 4 * * *");
});
