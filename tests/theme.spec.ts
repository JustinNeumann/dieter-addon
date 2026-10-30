// The palettes of Settings → Appearance. Dreamfall Pearl: night-sky navy in the dark, a lavender-tinted pearl white
// in the light, its swatch a pearlescent sheen (cyan, periwinkle, violet, pink, peach).
import { test, expect } from "@playwright/test";
import { column, open } from "./helpers";

/** The page's colour at (x, y). */
const pixel = async (page, x: number, y: number) => (await column(page, x, y, 1))[0];

const near = (got: number[], want: number) =>
  got.every((v, i) => Math.abs(v - ((want >> (16 - 8 * i)) & 0xff)) <= 3) || `got rgb(${got.join(", ")}), want #${want.toString(16).padStart(6, "0")}`;

const BACKGROUND = { x: 900, y: 120 }; // the empty detail pane: the screen's own background

test("Dreamfall Pearl: navy background in the dark, pearl white in the light", async ({ page }) => {
  await open(page, "appearance dark; palette dreamfall");
  expect(near(await pixel(page, BACKGROUND.x, BACKGROUND.y), 0x0b1330)).toBe(true);
  await open(page, "appearance light; palette dreamfall");
  // the light base with a trace of the violet seed (MobileTheme: lerp(light, seed, .015))
  expect(near(await pixel(page, BACKGROUND.x, BACKGROUND.y), 0xf6f4fb)).toBe(true);
});

test("the Dreamfall Pearl swatch shows the sheen and selects the palette", async ({ page }) => {
  await open(page, "appearance dark; tool settings");
  // the swatch (circle and caption, clickable as one) fills its row of the picker alone
  const box = await page.getByText("Dreamfall", { exact: true }).boundingBox();
  expect(box, "the Dreamfall swatch").toBeTruthy();
  // its 48 dp circle: 4 dp below the swatch's top; cool (cyan) at the top left, warm (peach) at the bottom right
  const cx = box!.x + box!.width / 2, cy = box!.y + 4 + 24;
  const [r1, , b1] = await pixel(page, Math.round(cx - 14), Math.round(cy - 14));
  const [r2, , b2] = await pixel(page, Math.round(cx + 14), Math.round(cy + 14));
  expect(b1 > r1 && r2 > b2, `cool to warm: rgb ${r1}/${b1} → ${r2}/${b2}`).toBe(true);
  await page.mouse.click(Math.round(cx), Math.round(cy));
  await page.waitForTimeout(800);
  // Settings fills the right pane now: its top bar, right of the title, is the screen's background
  expect(near(await pixel(page, 1150, 30), 0x0b1330)).toBe(true);
});
