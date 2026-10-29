import { expect, type Page, type Locator } from "@playwright/test";

/** Opens the demo page (?demo), replays a debug navigation script and waits until the UI settles. */
export async function open(page: Page, script = "", query = ""): Promise<void> {
  // Demo data in the page itself; without ?demo the page connects to the web host.
  const params = new URLSearchParams(query);
  params.set("demo", "");
  if (script) params.set("script", script);
  const qs = params.toString();
  await page.goto(qs ? `./?${qs}` : "./");
  await page.waitForSelector("#loading", { state: "detached", timeout: 60_000 });
  await settle(page, script ? 1800 : 600);
}

/** Waits until the accessibility snapshot stops changing (the script and animations are done). */
export async function settle(page: Page, minimum = 300): Promise<string> {
  await page.waitForTimeout(minimum);
  let last = await aria(page);
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(300);
    const next = await aria(page);
    if (next === last) return next;
    last = next;
  }
  return last;
}

export const aria = (page: Page) => page.locator("body").ariaSnapshot();

/** The centre of an element of the accessibility DOM; Compose draws it on the canvas underneath. */
export async function centre(target: Locator): Promise<{ x: number; y: number }> {
  const box = await target.first().boundingBox();
  if (!box) throw new Error("not on screen");
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** Clicks what the accessibility DOM says is at [target], on the canvas (the DOM node is not hit-testable). */
export async function tap(page: Page, target: Locator): Promise<void> {
  const { x, y } = await centre(target);
  await page.mouse.click(x, y);
}

/** Drags across the last line of [target]'s box and copies; returns the clipboard text. */
export async function dragCopy(page: Page, target: Locator, line = -1, inset = 22): Promise<string> {
  const box = await target.first().boundingBox();
  if (!box) throw new Error("not on screen");
  const y = line < 0 ? box.y + box.height - 14 : box.y + 12 + line * 24;
  await page.evaluate(() => navigator.clipboard.writeText(""));
  // Inside the text: a press in the padding around it belongs to the row, not to the selection.
  await page.mouse.move(box.x + inset, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.4, y, { steps: 8 });
  await page.mouse.move(box.x + box.width * 0.7, y, { steps: 8 });
  await page.mouse.up();
  await page.keyboard.press("ControlOrMeta+c");
  await page.waitForTimeout(300);
  return page.evaluate(() => navigator.clipboard.readText());
}

export async function clipboard(page: Page): Promise<string> {
  return page.evaluate(() => navigator.clipboard.readText());
}

/** The DOM element under a point, outside Compose's accessibility layer. */
export async function hit(page: Page, x: number, y: number): Promise<string> {
  return page.evaluate(([x, y]) => {
    let el: Element | null = document.elementFromPoint(x, y);
    while (el && el.shadowRoot) {
      const inner = el.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === el) break;
      el = inner;
    }
    return el ? el.tagName.toLowerCase() + (el.className && typeof el.className === "string" ? "." + el.className.split(" ").join(".") : "") : "none";
  }, [x, y]);
}

export { expect };
