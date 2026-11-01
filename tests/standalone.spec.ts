// The standalone demo (?demo=standalone, the demo on GitHub Pages) needs no web host: it opens no WebSocket, and the
// add-on's own screens come from the add-on core running in the page over sample data (web/…/DemoAddon.kt).
import { test, expect, type Page } from "@playwright/test";
import { aria, settle, tap, COMPOSE_112, STALE_POPUPS } from "./helpers";

/** Opens the standalone demo, replays a debug navigation script; returns the WebSockets the page opens. */
async function standalone(page: Page, script = ""): Promise<string[]> {
  const sockets: string[] = [];
  page.on("websocket", (ws) => sockets.push(ws.url()));
  const params = new URLSearchParams({ demo: "standalone" });
  if (script) params.set("script", script);
  await page.goto(`./?${params}`);
  await page.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await settle(page, script ? 1800 : 600);
  return sockets;
}

async function atlasIdeas(page: Page): Promise<void> {
  await standalone(page, "tab more");
  await expect.poll(() => aria(page)).toContain("Dreamfall 5 waiting");
  await tap(page, page.getByRole("button", { name: /^Dreamfall/ }));
  await expect.poll(() => aria(page)).toContain("Atlas ideas");
  await tap(page, page.getByRole("button", { name: /Atlas ideas/ }));
  await expect.poll(() => aria(page)).toContain("12 judged, 6 promoted");
}

test("the standalone demo opens no connection to a web host", async ({ page }) => {
  const sockets = await standalone(page);
  await expect.poll(() => aria(page)).toContain("Needs you");
  await page.waitForTimeout(1500);
  expect(sockets).toEqual([]);
});

test("Dreamfall shows the sample board: last night's runs, five ideas waiting for a pick, one on Implementation", async ({ page }) => {
  await atlasIdeas(page);
  const board = await aria(page);
  expect(board).toContain("16 new ideas");
  expect(board).toMatch(/Navigation[\s\S]*Signed webhook timestamps with a 5-minute window, Waiting for you[\s\S]*Retry budget for outgoing webhooks, Waiting for you/);
  expect(board).toMatch(/Implementation[\s\S]*Scoped API keys per endpoint group, Todo/);
});

test("an idea shows its brief, where it came from and its trail", async ({ page }) => {
  await atlasIdeas(page);
  await tap(page, page.getByRole("button", { name: /Signed webhook timestamps with a 5-minute window, Waiting/ }));
  await expect.poll(() => aria(page)).toContain("Where it came from");
  const idea = await aria(page);
  expect(idea).toContain("Found by Enumeration agent");
  expect(idea).toMatch(/Judged: promote[\s\S]*Score 7\/10/);
});

test("Pick copies the idea to Implementation as a Todo draft", async ({ page }) => {
  test.skip(COMPOSE_112, `the outcome shows after a dialog: ${STALE_POPUPS}`);
  await atlasIdeas(page);
  await tap(page, page.getByRole("button", { name: "Pick", exact: true }).first());
  await expect.poll(() => aria(page)).toContain("Pick for Implementation");
  await tap(page, page.getByRole("button", { name: "Pick", exact: true }).last());
  await expect.poll(() => aria(page)).toContain("Picked, 2, folded");
  expect(await aria(page)).toMatch(/Implementation[\s\S]*Signed webhook timestamps with a 5-minute window[\s\S]*Scoped API keys per endpoint group/);
});

test("creating a task runs a simulated turn from Running to Review", async ({ page }) => {
  await standalone(page, "board Roadmap; new");
  await expect.poll(() => aria(page)).toContain("What should we do?");
  await page.keyboard.type("Add X-RateLimit-Remaining header to /v2/search responses.");
  await expect.poll(() => aria(page)).toContain('button "Start"');
  await tap(page, page.getByRole("button", { name: "Start", exact: true }).last());
  await expect.poll(() => aria(page), { timeout: 15_000 }).toContain("atlas-api · Roadmap · Review");
  expect(await aria(page)).toContain("Opened PR #43");
});
