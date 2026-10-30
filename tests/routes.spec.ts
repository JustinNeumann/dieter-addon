// Deep links into the page: ?route= at load (the add-on's root page forwards <HA>/app/<slug>/next/<route>), and the routes
// Home Assistant's app panel reports while the page is open (a push opening /<panel>/card/<id>).
import { test, expect, type Page } from "@playwright/test";
import { open, aria } from "./helpers";

test("?route=card/<id> opens that conversation; a card that is gone opens the Inbox", async ({ page }) => {
  await open(page, "", "route=card/c-web");
  expect(await aria(page)).toContain('textbox "Message"');
  expect(await aria(page)).toContain("text: Compose web UI behind Ingress dieter-addon");
  await open(page, "", "route=card/c-gone");
  expect(await aria(page)).not.toContain('textbox "Message"');
});

/** Plays HA's app panel: answers subscribe-properties with its route, resends it on every navigation. */
async function panel(page: Page, query: string, path = "/next") {
  const src = new URL(`./?demo&${query}`, test.info().project.use.baseURL).href;
  await page.setContent(`<script>
    let route = { prefix: "/app/4d8f2a84_dieter", path: ${JSON.stringify(path)} }, sub = null; window.navigated = [];
    const send = () => sub && sub.postMessage({ type: "home-assistant/properties", narrow: false, route }, "*");
    addEventListener("message", (e) => {
      if (e.data?.type === "home-assistant/subscribe-properties") { sub = e.source; send(); }
      if (e.data?.type === "home-assistant/navigate") { navigated.push(e.data.path); route = { prefix: "/app/4d8f2a84_dieter", path: e.data.path.slice(20) }; send(); }
    });
    window.tap = (path) => { route = { prefix: "/app/4d8f2a84_dieter", path }; send(); };
  </script><iframe src="${src}" style="width:1200px;height:800px;border:0"></iframe>`);
  const frame = page.frames()[1] ?? (await page.waitForEvent("frameattached"));
  await frame.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await page.waitForTimeout(1500);
  return () => frame.locator("body").ariaSnapshot();
}

test("a push to /<panel>/card/<id> opens the card in the open page; the panel's address goes back to next", async ({ page }) => {
  const tree = await panel(page, "");
  expect(await tree()).not.toContain('textbox "Message"');
  await page.evaluate(() => (window as any).tap("/card/c-web"));
  await expect.poll(tree, { timeout: 15_000 }).toContain('textbox "Message"');
  expect(await page.evaluate(() => (window as any).navigated)).toEqual(["/app/4d8f2a84_dieter/next"]);
  await page.evaluate(() => (window as any).tap("/next/tab/mail"));
  await expect.poll(tree, { timeout: 15_000 }).toContain("Nightly build");
});

test("opened at /<panel>/next/dreamfall/<id>: the board over the Dreamfall list, the panel's address back to next", async ({ page }) => {
  const tree = await panel(page, "route=dreamfall/df-test", "/next/dreamfall/df-test");
  await expect.poll(() => page.evaluate(() => (window as any).navigated)).toEqual(["/app/4d8f2a84_dieter/next"]);
  const back = async () => { await page.frames()[1].evaluate(() => history.back()); await page.waitForTimeout(800); };
  await back();
  await expect.poll(tree, { timeout: 10_000 }).toContain('button "New Dreamfall board"');
  await back();
  await expect.poll(tree, { timeout: 10_000 }).toContain("No tool selected");
});
