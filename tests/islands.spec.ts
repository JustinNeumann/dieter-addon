// Phase 2 step 3: mail bodies and Markdown files as HTML islands (real DOM in the Compose layout),
// diagrams through the offline renderer, and Compose's menus, sheets and modals kept above them.
import { test, expect } from "@playwright/test";
import { open, aria, tap, hit } from "./helpers";

test("mail body is real HTML with the Markdown profile", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit");
  const island = page.locator(".dieter-island");
  await expect(island.locator("h1")).toHaveText("Dependency audit");
  await expect(island.locator("table tbody tr")).toHaveCount(3);
  await expect(island.locator("table th").first()).toHaveText("Package");
  await expect(island.locator(".code .lang")).toHaveText("bash");
  await expect(island.locator("pre code")).toContainText("sed -i");
  await expect(island.locator("strong")).toHaveText("sigstore");
  await expect(island.locator("ul > li")).toHaveCount(3);
  // The island is what the reader sees and hits.
  const box = (await island.locator("h1").boundingBox())!;
  expect(await hit(page, box.x + 10, box.y + box.height / 2)).toMatch(/^h1/);
});

test("diagrams render offline in sandboxed frames", async ({ page }) => {
  await open(page, "tab mail; mail Nightly build");
  const island = page.locator(".dieter-island");
  await expect(island.locator('.diagram[data-rendered="mermaid"]')).toHaveCount(1, { timeout: 20_000 });
  await expect(island.locator('.diagram[data-rendered="vega-lite"]')).toHaveCount(1, { timeout: 20_000 });
  const frames = island.locator(".diagram iframe");
  await expect(frames).toHaveCount(2);
  for (const sandbox of await frames.evaluateAll((all) => all.map((f) => f.getAttribute("sandbox"))))
    expect(sandbox).toBe("allow-scripts");
  const height = await island.locator('.diagram[data-rendered="vega-lite"] .frame').evaluate((e) => e.getBoundingClientRect().height);
  expect(height).toBeGreaterThan(50);
});

test("menus over the island stay visible and clickable", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit");
  const island = page.locator(".dieter-island");
  await tap(page, page.getByRole("button", { name: "More options" }).last());
  await expect.poll(() => island.evaluate((e) => e.style.clipPath)).toContain("polygon");
  const item = (await page.getByRole("button", { name: "Copy Markdown" }).boundingBox())!;
  expect(await hit(page, item.x + item.width / 2, item.y + item.height / 2)).not.toMatch(/dieter-island|^p|^h1|^td|^th/);
  await page.keyboard.press("Escape");
  await expect.poll(() => island.evaluate((e) => e.style.clipPath)).toBe("");
});

test("a modal screen hides the island underneath", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit; new");
  await expect.poll(() => page.locator(".dieter-island").evaluate((e) => e.style.visibility)).toBe("hidden");
  expect(await aria(page)).toContain("What should we do?");
});

test("the island scrolls on its own and folds the header", async ({ page }) => {
  await open(page, "tab mail; mail Nightly build");
  const island = page.locator(".dieter-island");
  const box = (await island.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, 900);
  await expect.poll(() => island.evaluate((e) => e.scrollTop)).toBeGreaterThan(300);
  await expect.poll(async () => (await aria(page)).includes('button "Open conversation"')).toBe(false);
});

test("?islands=off keeps Compose's renderer", async ({ page }) => {
  await open(page, "tab mail; mail Dependency audit", "islands=off");
  await expect(page.locator(".dieter-island")).toHaveCount(0);
  expect(await aria(page)).toContain("Two dependencies have newer releases");
});

test("Markdown files are islands, plain text stays Compose's editor", async ({ page }) => {
  await open(page, "file README.md");
  await expect(page.locator(".dieter-island h1")).toHaveText("dieter-addon-kotlin");
  await open(page, "file notes.txt");
  await expect(page.locator(".dieter-island")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "File contents" })).toHaveCount(1);
});
