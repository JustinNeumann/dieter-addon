// Renders mockup.html to PNG. Run where Playwright's Chromium exists, e.g. on the workstation:
//   node render.mjs <dir containing docs/dreamfall/mockup.html and dieter/bridge/app.css> <out dir>
import path from "node:path";
import { createRequire } from "node:module";
const require = createRequire(process.env.PW_ROOT || "/home/justin/dieter-addon/dieter/tests/package.json");
const { chromium } = require("playwright");
const [root, out] = process.argv.slice(2);
console.log("launching");
const browser = await chromium.launch({ timeout: 30000 });
for (const theme of ["light", "dark"]) {
  const page = await browser.newPage({ viewport: { width: 1340, height: 1900 }, deviceScaleFactor: 2 });
  await page.goto("file://" + path.resolve(root, "docs/dreamfall/mockup.html"), { waitUntil: "load", timeout: 30000 });
  if (theme === "dark") await page.evaluate(() => document.documentElement.setAttribute("data-theme", "dark"));
  await Promise.race([page.evaluate(() => document.fonts.ready), page.waitForTimeout(8000)]);
  await page.waitForTimeout(500);
  await page.locator("#mock-board").screenshot({ path: path.join(out, `board-${theme}.png`) });
  await page.locator(".phones").screenshot({ path: path.join(out, `phone-${theme}.png`) });
  console.log("rendered", theme);
  await page.close();
}
await browser.close();
