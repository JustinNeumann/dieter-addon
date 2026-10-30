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
