// Step 1 of Phase 2 (docs/PLAN.md): an automated proxy for the accessibility, keyboard and selection
// checklist, per screen. It records instead of asserting; `npm run measure` writes
// measure-results/measure.{json,md}. Screen readers themselves (Orca, VoiceOver, TalkBack) still need a
// person: this reads what Chromium's accessibility tree exposes, which is what they would get.
import { test, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { open, settle, aria, tap, dragCopy } from "./helpers";

type Screen = {
  name: string;
  script: string;
  content: string; // text that must be exposed to assistive tech
  copy?: string; // text to drag-select and copy
  menus?: string[]; // buttons that open a menu or sheet, first one present is used
  modal?: boolean; // the screen itself is a sheet that Escape should close
};

const SCREENS: Screen[] = [
  { name: "Inbox", script: "", content: "Compose web UI behind Ingress", menus: ["Filter"] },
  { name: "Board", script: "board Add-on", content: "Compose web UI behind Ingress", menus: ["Board options"] },
  { name: "Conversation", script: "card Compose web UI behind Ingress", content: "Sora comes from Compose resources", copy: "Sora comes from Compose resources", menus: ["Conversation actions"] },
  { name: "Mail message", script: "tab mail; mail Dependency audit", content: "Two dependencies have newer releases", copy: "Two dependencies have newer releases", menus: ["More options"] },
  { name: "Files", script: "file README.md", content: "compiled for the browser", copy: "compiled for the browser", menus: ["Document actions"] },
  { name: "New task", script: "new", content: "What should we do?", modal: true },
];

const MAX_TABS = 8;
const OUT = path.resolve(process.env.MEASURE_OUT || path.join(__dirname, "measure-results"));
const LABEL = process.env.MEASURE_LABEL || "current";

function record(screen: string, values: Record<string, unknown>) {
  fs.mkdirSync(OUT, { recursive: true });
  const file = path.join(OUT, "measure.json");
  const all = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
  all[LABEL] ??= {};
  all[LABEL][screen] = { ...(all[LABEL][screen] || {}), ...values };
  fs.writeFileSync(file, JSON.stringify(all, null, 2));
  fs.writeFileSync(path.join(OUT, "measure.md"), table(all));
}

function table(all: Record<string, Record<string, any>>): string {
  const lines: string[] = [];
  for (const [label, screens] of Object.entries(all)) {
    lines.push(`### ${label}`, "", "| Screen | Exposed (nodes / buttons / unnamed) | Content in tree | Odd names | Tab → Enter activates (Tab presses) | Space | DOM focus follows | Escape closes | Closed menu left in tree | Drag-select + copy |", "|---|---|---|---|---|---|---|---|---|---|");
    for (const s of SCREENS) {
      const r = screens[s.name];
      if (!r) continue;
      lines.push(`| ${s.name} | ${r.nodes} / ${r.buttons} / ${r.unnamed} | ${r.content ? "yes" : "no"} | ${(r.odd || []).map((n: string) => "`" + n.slice(0, 30) + "`").join(", ") || "none"} | ${r.tab?.length ? r.tab.join(", ") : "none"} | ${r.space ?? "n/a"} | ${r.domFocus ? "yes" : "no"} | ${r.escape ?? "n/a"} | ${r.staleTree === undefined || r.staleTree === null ? "n/a" : r.staleTree ? "yes" : "no"} | ${r.copy === undefined ? "n/a" : r.copy ? "`" + String(r.copy).slice(0, 40).replace(/\n/g, " ") + "`" : "nothing copied"} |`);
    }
    lines.push("");
  }
  return lines.join("\n");
}

const buttonNames = (snapshot: string) =>
  [...snapshot.matchAll(/^\s*- '?button(?: "((?:[^"\\]|\\.)*)")?/gm)].map((m) => m[1] ?? "");

/** Labels that read badly: an avatar letter in front, a lone icon description, icon states only. */
const odd = (name: string) =>
  /^[A-Z]{1,2} [A-Z][a-z]/.test(name) || /^(Chat|Done|Open|Image|Icon)$/.test(name) ||
  /^(Done|Open)(, (Done|Open))+$/.test(name) || /^Last activity/.test(name);

// Ages ("3m", "1min") tick while the suite runs; they are not what a key press changed.
const stable = (snapshot: string) => snapshot.replace(/\b\d+\s?(s|m|min|h|d)\b/g, "#");

async function changesAfter(page: Page, key: string): Promise<boolean> {
  const before = stable(await aria(page));
  await page.keyboard.press(key);
  const after = stable(await settle(page, 500));
  return before !== after;
}

/** Pixels below a menu button's box, where its menu opens. */
async function belowButton(page: Page, b: { x: number; y: number; width: number; height: number }): Promise<Buffer> {
  const x = Math.max(0, b.x + b.width - 320);
  return page.screenshot({ clip: { x, y: b.y + b.height + 4, width: b.x + b.width - x, height: 300 } });
}

test.describe("@measure Phase 2 checklist", () => {
  test.describe.configure({ mode: "serial" });
  for (const s of SCREENS) {
    test(`@measure ${s.name}`, async ({ page }) => {
      test.setTimeout(300_000);
      await open(page, s.script);
      const snapshot = await aria(page);
      const names = buttonNames(snapshot);
      const domFocus = await (async () => {
        for (let i = 0; i < 3; i++) await page.keyboard.press("Tab");
        return page.evaluate(() => {
          let a: Element | null = document.activeElement;
          while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
          return !!a && a.tagName !== "CANVAS" && a.tagName !== "BODY";
        });
      })();
      record(s.name, {
        nodes: snapshot.split("\n").length,
        buttons: names.length,
        unnamed: names.filter((n) => !n.trim()).length,
        content: snapshot.includes(s.content),
        odd: [...new Set(names.filter(odd))],
        domFocus,
      });

      // Keyboard: Tab k times, then Enter; did anything open or change?
      const tab: number[] = [];
      for (let k = 1; k <= MAX_TABS; k++) {
        await open(page, s.script);
        for (let i = 0; i < k; i++) await page.keyboard.press("Tab");
        if (await changesAfter(page, "Enter")) tab.push(k);
      }
      let space: string = "n/a";
      if (tab.length) {
        await open(page, s.script);
        for (let i = 0; i < tab[0]; i++) await page.keyboard.press("Tab");
        space = (await changesAfter(page, "Space")) ? "yes" : "no";
      }
      record(s.name, { tab, space });

      // Escape closes the screen's menu (or the screen, when it is a modal one). Menus are judged by
      // their pixels: Compose for Web keeps a closed popup in the accessibility tree (recorded too).
      await open(page, s.script);
      let escape = "n/a";
      let staleTree: boolean | undefined;
      if (s.modal) {
        const gone = async () => !(await aria(page)).includes(s.content);
        await page.keyboard.press("Tab");
        await page.keyboard.press("Escape");
        await settle(page, 600);
        const fromField = await gone();
        if (!fromField) {
          await page.mouse.click(900, 760);
          await page.keyboard.press("Escape");
          await settle(page, 600);
        }
        escape = fromField ? "yes" : (await gone()) ? "yes, once the canvas has focus (not from a text field)" : "no";
      } else {
        const snap = await aria(page);
        const menu = (s.menus || []).find((m) => snap.includes(`button "${m}"`));
        if (menu) {
          // While a popup is open the tree holds only the popup: measure the button once.
          const button = (await page.getByRole("button", { name: menu, exact: true }).last().boundingBox())!;
          const before = await belowButton(page, button);
          await page.mouse.click(button.x + button.width / 2, button.y + button.height / 2);
          await settle(page, 500);
          const items = await aria(page);
          const opened = await belowButton(page, button);
          if (before.equals(opened)) escape = `\`${menu}\` did not open`;
          else {
            await page.keyboard.press("Escape");
            await page.waitForTimeout(700);
            // Closed when the menu's pixels are gone (an HTML island underneath may repaint slightly).
            escape = (await belowButton(page, button)).equals(opened) ? `no (\`${menu}\`)` : `yes (\`${menu}\`)`;
            const added = items.split("\n").filter((l) => !snap.includes(l) && /button/.test(l));
            const after = await aria(page);
            staleTree = added.length > 0 && added.every((l) => after.includes(l));
          }
        }
      }
      record(s.name, { escape, staleTree });

      if (s.copy) {
        await open(page, s.script);
        // Mail bodies and Markdown files are HTML islands: drag across their DOM text from its first letter.
        const island = page.locator(".dieter-island");
        const native = (await island.count()) > 0;
        const target = (native ? island : page).getByText(s.copy, { exact: false }).last();
        // A row's box starts with its header; its text ends at the bottom.
        const copied = await dragCopy(page, target, s.name === "Conversation" ? -1 : 0, native ? 1 : 22);
        record(s.name, { copy: copied });
      } else record(s.name, { copy: undefined });
    });
  }
});
