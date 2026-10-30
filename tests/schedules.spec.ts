// Tools → Schedules lists every project's schedules under the project's name; the Project menu narrows it to one
// project and back. The demo has schedules in two of its three projects.
import { test, expect } from "@playwright/test";
import { open, aria, settle, tap } from "./helpers";

const SCHEDULES = "tab tools; tool schedules";

test("Schedules lists every project's schedules, each under its project in the project list's order", async ({ page }) => {
  await open(page, SCHEDULES);
  await expect.poll(() => aria(page)).toContain("Dependency check");
  const list = await aria(page);
  expect(list).toContain("All projects");
  expect(list).toMatch(/dieter-addon[\s\S]*Dependency check[\s\S]*Home-Assistant-Sem2[\s\S]*Backup review[\s\S]*Morning ideas/);
  expect(list).not.toMatch(/text: dieter\n/); // a project without schedules gets no heading
});

test("the Project menu offers every project, and All projects is the one chosen", async ({ page }) => {
  await open(page, SCHEDULES);
  await tap(page, page.getByRole("button", { name: "Schedule options", exact: true }));
  await settle(page, 600);
  await tap(page, page.getByRole("button", { name: /^Project/ }).last());
  const menu = await settle(page, 600);
  expect(menu).toMatch(/All projects[\s\S]*dieter-addon[\s\S]*Home-Assistant-Sem2[\s\S]*dieter/);
});

test("a new schedule in the list of every project's asks for its project", async ({ page }) => {
  await open(page, SCHEDULES);
  await tap(page, page.getByRole("button", { name: "New schedule", exact: true }).first());
  const sheet = await settle(page, 1200);
  expect(sheet).toContain("New Schedule");
  expect(sheet).toMatch(/Project[\s\S]*Board[\s\S]*Checkout/);
});

// Run history used to open as a section after every schedule, below the screen, so the menu item seemed to do nothing.
// (The test opens it with the debug script: once a menu closes, the page's accessibility tree keeps the menu.)
test("a schedule's run history opens right under it, with the runs' ages, and its menu offers to hide it", async ({ page }) => {
  await open(page, SCHEDULES + "; runs Backup");
  await expect.poll(() => aria(page)).toContain("the machine was offline");
  expect(await aria(page)).toMatch(/Backup review[\s\S]*Run history[\s\S]*Completed[\s\S]*1d ago · Scheduled[\s\S]*Failed[\s\S]*2d ago · Manual · the machine was offline[\s\S]*Morning ideas/);
  await tap(page, page.getByRole("button", { name: "Schedule actions", exact: true }).nth(1));   // Backup review's
  const menu = await settle(page, 600);
  expect(menu).toContain("Hide run history");
});

test("a schedule's menu offers its run history", async ({ page }) => {
  await open(page, SCHEDULES);
  await expect.poll(() => aria(page)).toContain("Backup review");
  await tap(page, page.getByRole("button", { name: "Schedule actions", exact: true }).nth(1));
  const menu = await settle(page, 600);
  expect(menu).toMatch(/Run now[\s\S]*Run history[\s\S]*Edit…/);
  expect(menu).not.toContain("Hide run history");
});
