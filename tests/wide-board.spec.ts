// Boards take the whole width on desktops and tablets, like Dreamfall boards: the lanes side by side and
// sharing the width; a card opens over the board, and Back returns to it.
import { test, expect } from "@playwright/test";
import { open, aria, tap } from "./helpers";

test("a board on a wide screen shows every lane side by side; a card opens over it", async ({ page }) => {
  await open(page, "board Roadmap");
  const board = await aria(page);
  for (const lane of ["Todo 1", "Running 2", "Review 1", "Done 1"]) expect(board).toContain(`text: ${lane}`);
  expect(board).not.toContain('button "Running 2"');                         // no lane pager
  const last = await page.getByText("Add structured request logging with slog").last().boundingBox();
  expect(last!.x + last!.width).toBeLessThanOrEqual(1200);                   // the last lane is fully on screen
  const first = await page.getByText("Rotate webhook signing secrets without downtime").last().boundingBox();
  expect(first!.x).toBeLessThan(200);                                        // and the first starts at the left
  await tap(page, page.getByText("Return RFC 9457 problem details from every handler").last());
  await expect.poll(async () => (await aria(page)).includes("text: Running 2")).toBe(false);
  await expect.poll(async () => aria(page)).toContain("Return RFC 9457 problem details from every handler");
  await tap(page, page.getByRole("button", { name: "Back", exact: true }).first());
  await expect.poll(async () => aria(page)).toContain("text: Running 2");
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 390, height: 800 }, hasTouch: true, isMobile: true });
  test("the board still pages through its lanes", async ({ page }) => {
    await open(page, "board Roadmap");
    const board = await aria(page);
    expect(board).toContain('button "Running 2"');                             // the pager's lane buttons
    expect(board).not.toContain("text: Running 2");
  });
});
