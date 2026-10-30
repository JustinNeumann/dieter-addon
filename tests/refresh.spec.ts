// Pull to refresh (the lists of Inbox, Chats, Mail, Channels): a touch pull refreshes; a mouse wheel or trackpad has
// no release, so scrolling past the top of a list with it must not pull (it left the indicator half drawn for good).
import { test, expect } from "@playwright/test";
import { column, open } from "./helpers";

test.use({ viewport: { width: 1200, height: 380 } }); // short, so the chat list scrolls

test("a mouse wheel past the top of a list does not pull the refresh indicator", async ({ page }) => {
  await open(page);
  await page.mouse.click(40, 240); // Chats in the rail
  await page.waitForTimeout(1500);
  await page.mouse.move(280, 330);
  await page.waitForTimeout(500);
  // down a little, then back up past the top: the wheel's animated scrolling overshoots, which pulled before
  for (let i = 0; i < 4; i++) { await page.mouse.wheel(0, 100); await page.waitForTimeout(80); }
  await page.waitForTimeout(800);
  for (let i = 0; i < 8; i++) { await page.mouse.wheel(0, -100); await page.waitForTimeout(80); }
  await page.waitForTimeout(1500);
  // the indicator hangs at the list's centre (x 280) below the top bar; above the first chat, the strip there is plain
  // (bar, list background, search field, whether the bar is expanded or not), so x 280 looks like x 345 beside it
  const centre = await column(page, 280, 0, 140), beside = await column(page, 345, 0, 140);
  const differ = centre.findIndex((px, y) => px.some((v, i) => Math.abs(v - beside[y][i]) > 2));
  expect(differ, `no indicator: the centre differs from beside it at y ${differ}`).toBe(-1);
});
