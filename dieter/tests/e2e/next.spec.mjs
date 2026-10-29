// The new Compose page (dieter-addon-kotlin's Kotlin/Wasm bundle) at next/, behind the Ingress stand-in.
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";
import { test, expect } from "@playwright/test";

test.describe.configure({ mode: "serial" });

const started = (page) => expect(page.locator("#loading")).toHaveCount(0, { timeout: 60_000 });

test("next/ loads through Ingress with relative assets, compressed wasm and cache headers", async ({ page }) => {
  const failed = [];
  const responses = [];
  page.on("requestfailed", (r) => failed.push(r.url()));
  page.on("response", (r) => {
    if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`);
    responses.push(r);
  });
  await page.goto("next");                                  // without the slash: redirected
  await expect(page).toHaveURL(/\/api\/hassio_ingress\/t0ken\/next\/$/);
  await started(page);
  await expect(page.locator("#dieter canvas").first()).toBeVisible();
  expect(failed).toEqual([]);

  const byName = (re) => responses.filter((r) => re.test(new URL(r.url()).pathname));
  const wasm = byName(/\.wasm(\.gz)?$/);
  expect(wasm.length).toBe(2);
  for (const r of wasm) {
    expect(r.url()).toMatch(/\.wasm\.gz$/);                 // the loader inflates them in the browser
    expect(r.headers()["content-type"]).toBe("application/octet-stream");
    expect(r.headers()["cache-control"]).toContain("immutable");
  }
  const [js] = byName(/\/dieter\.[0-9a-f]{16}\.js$/);
  expect(js.headers()["cache-control"]).toContain("immutable");
  const [index] = byName(/\/next\/$/);
  expect(index.headers()["cache-control"]).toBe("no-cache");
  expect(index.headers()["etag"]).toBeTruthy();
});

test("next/ on a phone: the bottom navigation renders", async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await page.goto("next/?demo");
  await started(page);
  await expect(page.locator("#dieter canvas").first()).toBeVisible();
  await context.close();
});

test("a WebSocket passes Ingress (echo of text and a 100 kB binary frame)", async ({ page }) => {
  await page.goto("next/");
  const result = await page.evaluate(async () => {
    const url = new URL("../api/ws/echo", location.href);
    url.protocol = url.protocol.replace("http", "ws");
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";
    await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = () => fail(new Error("WebSocket error")); });
    const text = await new Promise((ok) => { ws.onmessage = (e) => ok(e.data); ws.send("ping through ingress"); });
    const size = await new Promise((ok) => { ws.onmessage = (e) => ok(e.data.byteLength); ws.send(new Uint8Array(100_000)); });
    ws.close();
    return { text, size };
  });
  expect(result).toEqual({ text: "ping through ingress", size: 100_000 });
});

test("a browser without WebAssembly GC gets a message with a link to the classic page", async ({ page }) => {
  await page.addInitScript(() => { WebAssembly.validate = () => false; });
  await page.goto("next/");
  await expect(page.locator("#loading")).toContainText("WebAssembly GC");
  await page.locator("#loading a").click();
  await expect(page).toHaveURL(/\/api\/hassio_ingress\/t0ken\/$/);
  await expect(page.locator("#rail")).toBeVisible();
});

test("the classic page links to the new one (Tools → Preview → New page)", async ({ page }) => {
  await page.goto("");
  await page.locator('#rail [data-act="tab"][data-tab="tools"]').click();
  await page.locator('[data-act="open-next"]').click();
  await expect(page).toHaveURL(/\/api\/hassio_ingress\/t0ken\/next\/$/);
  await started(page);
});

// colour of one screen pixel as #rrggbb (Compose draws into a canvas; there is no DOM to ask)
async function pixel(page, x, y) {
  const png = await page.screenshot({ clip: { x, y, width: 1, height: 1 } });
  const idat = [];
  for (let off = 8; off < png.length; ) {
    const len = png.readUInt32BE(off);
    if (png.toString("ascii", off + 4, off + 8) === "IDAT") idat.push(png.subarray(off + 8, off + 8 + len));
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  return "#" + [raw[1], raw[2], raw[3]].map((n) => n.toString(16).padStart(2, "0")).join("");
}

// Home Assistant's frontend stand-in on the same origin: <home-assistant>.hass.themes and theme variables
// (setContent reuses the window, so the script declares nothing at the top level)
const FAKE_HA = `<!doctype html><html><head><style>html{--primary-color:#03a9f4}body{margin:0}
  iframe{border:0;width:100vw;height:100vh;display:block}</style></head><body><home-assistant></home-assistant>
  <script>(() => {
    const ha = document.querySelector("home-assistant");
    ha.hass = { themes: { darkMode: false, theme: "default" } };
    window.setHa = (dark, primary) => {
      ha.hass = { themes: { darkMode: dark, theme: "default" } };
      if (primary) document.documentElement.style.setProperty("--primary-color", primary);
    };
  })();</script><iframe src="./?demo"></iframe></body></html>`;

test("next/ follows Home Assistant's theme without a reload: dark mode, and the primary colour with Dynamic color", async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1200, height: 800 }, colorScheme: "dark" });
  const page = await context.newPage();
  await page.goto("next/BUILD.json");                     // same origin as next/, then replace the document
  await page.setContent(FAKE_HA);
  const frame = () => page.frames().find((f) => f !== page.mainFrame());
  await expect.poll(() => frame()?.locator("#loading").count(), { timeout: 60_000 }).toBe(0);
  const spot = [900, 700];                                  // the empty detail pane
  await expect.poll(() => pixel(page, ...spot)).toBe("#fbfbfd"); // HA light although the device is dark
  await page.evaluate(() => window.setHa(true));
  await expect.poll(() => pixel(page, ...spot), { timeout: 2_000 }).toBe("#121214");
  // Dynamic color on: Dieter's scheme is seeded from HA's primary colour and follows it
  await frame().evaluate(() => localStorage.setItem("dieter.dynamic-color", "true"));
  await page.setContent(FAKE_HA);
  await expect.poll(() => frame()?.locator("#loading").count(), { timeout: 60_000 }).toBe(0);
  await page.evaluate(() => window.setHa(true));
  const blue = await expect.poll(() => pixel(page, ...spot)).not.toBe("#121214").then(() => pixel(page, ...spot));
  await page.evaluate(() => window.setHa(true, "#e91e63"));
  await expect.poll(() => pixel(page, ...spot), { timeout: 2_000 }).not.toBe(blue);
  await frame().evaluate(() => localStorage.removeItem("dieter.dynamic-color"));
  await context.close();
});

// ---- the web host (the page's core on the JVM, user dieter-web, loopback, per-boot secret)

test("next/ reaches the web host over next/core through Ingress and gets its hello", async ({ page }) => {
  const socket = page.waitForEvent("websocket", (ws) => /\/next\/core$/.test(ws.url()));
  await page.goto("next/");
  const ws = await socket;
  const first = await ws.waitForEvent("framereceived", { timeout: 60_000 });
  const bytes = Buffer.from(first.payload);
  expect(bytes[0]).toBe(0x0a);                                // HostFrame.hello (field 1, length-delimited)
  expect(bytes.toString("latin1")).toMatch(/0\.6\.\d+/);       // the core's Dieter release
  await started(page);
});

test("the gateway's return after sign-in reaches the web host (next/signin)", async ({ request }) => {
  const r = await request.get("next/signin?code=abc&state=xyz");
  expect(r.status()).toBe(200);
  const body = await r.text();
  expect(body).toContain("Sign-in failed");
  expect(body).toContain("No sign-in is in progress");
  expect(r.headers()["cache-control"]).toBe("no-store");
});

test("a deep link <HA>/app/<slug>/next/signin opens the new page's callback", async ({ page }) => {
  await page.goto("next/BUILD.json");                          // same origin; the parent's path is HA's deep link
  await page.evaluate(() => history.replaceState(null, "", "/app/0123abcd_dieter/next/signin?code=abc&state=xyz"));
  await page.setContent('<iframe src="/api/hassio_ingress/t0ken/" style="width:100vw;height:90vh;border:0"></iframe>');
  const frame = () => page.frames().find((f) => f !== page.mainFrame());
  await expect.poll(() => frame()?.url(), { timeout: 20_000 }).toMatch(/\/t0ken\/next\/signin\?code=abc&state=xyz$/);
  await expect(frame().locator("h1")).toHaveText("Sign-in failed");
  // the result page takes the callback out of Home Assistant's address bar
  await expect.poll(() => page.evaluate(() => location.pathname)).toBe("/app/0123abcd_dieter/next/");
});

test("other deep links <HA>/app/<slug>/next/<route> become the page's ?route=", async ({ page }) => {
  await page.goto("next/BUILD.json");
  await page.evaluate(() => history.replaceState(null, "", "/app/0123abcd_dieter/next/card/c_123?x=1"));
  await page.setContent('<iframe src="/api/hassio_ingress/t0ken/"></iframe>');
  const frame = () => page.frames().find((f) => f !== page.mainFrame());
  await expect.poll(() => frame()?.url(), { timeout: 20_000 }).toMatch(/\/t0ken\/next\/\?route=card%2Fc_123&x=1$/);
  // the new page's own link back to the classic page does not bounce (referrer inside Ingress)
  await frame().evaluate(() => { location.href = "../"; });
  await expect.poll(() => frame()?.url(), { timeout: 20_000 }).toMatch(/\/t0ken\/$/);
  await page.waitForTimeout(1500);
  expect(frame().url()).toMatch(/\/t0ken\/$/);
});

test("agents cannot use the web host: private state, no answer without the secret", async () => {
  const sh = (cmd) => execFileSync("docker", ["exec", "-u", "dieter", "dieter-e2e", "sh", "-c", cmd], { stdio: "pipe" }).toString().trim();
  expect(sh("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8097/next/signin?code=a")).toBe("403");
  expect(sh("ls /data/web-host >/dev/null 2>&1 && echo readable || echo denied")).toBe("denied");
  expect(sh("stat -c '%U %a' /data/web-host")).toBe("dieter-web 700");
  // the bridge holds the secret only in memory: not in its environment, not on an open descriptor
  expect(sh("for p in /proc/[0-9]*; do tr '\\0' '\\n' < $p/environ 2>/dev/null; done | grep -c '^DIETER_WEB_HOST_SECRET=' || true")).toBe("0");
  expect(sh("ls -l /proc/[0-9]*/fd 2>/dev/null | grep -c 'secret' || true")).toBe("0");
  const bridge = sh("pgrep -o -f dieter_bridge.py");
  expect(sh(`cat /proc/${bridge}/environ >/dev/null 2>&1 && echo readable || echo denied`)).toBe("denied");
  expect(sh(`head -c1 /proc/${bridge}/mem >/dev/null 2>&1 && echo readable || echo denied`)).toBe("denied");
  const host = execFileSync("docker", ["exec", "dieter-e2e", "sh", "-c", "ps -o user:20=,rss=,args= -C java"]).toString();
  expect(host).toMatch(/^dieter-web\s+\d+\s+java .*dieter-web-host\.jar/m);
});
