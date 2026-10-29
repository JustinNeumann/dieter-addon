import { defineConfig } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

// The packaged bundle under test: DIETER_WEB_DIR, else the newest build/package/dieter-web-*/ of this
// checkout (tools/package.sh <version> makes it).
function bundle(): string {
  if (process.env.DIETER_WEB_DIR) return path.resolve(process.env.DIETER_WEB_DIR);
  const dir = path.resolve(__dirname, "../build/package");
  const found = fs.existsSync(dir)
    ? fs.readdirSync(dir).filter((n) => n.startsWith("dieter-web-") && fs.statSync(path.join(dir, n)).isDirectory())
        .map((n) => path.join(dir, n)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)
    : [];
  if (!found.length) throw new Error("No bundle: run tools/package.sh <version> or set DIETER_WEB_DIR");
  return found[0];
}

const port = Number(process.env.PORT || 18780);

export default defineConfig({
  testDir: ".",
  testMatch: /.*\.spec\.ts/,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: process.env.CI ? 2 : 3,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://127.0.0.1:${port}/api/hassio_ingress/t0ken/next/`,
    browserName: "chromium",
    viewport: { width: 1200, height: 800 },
    permissions: ["clipboard-read", "clipboard-write"],
    trace: "retain-on-failure",
  },
  webServer: {
    command: `node serve.mjs "${bundle()}" ${port}`,
    url: `http://127.0.0.1:${port}/api/hassio_ingress/t0ken/next/`,
    reuseExistingServer: !process.env.CI,
    stdout: "ignore",
  },
});
