// The browser's own file dialog, tabs and downloads stand in for the apps' pickers and viewers.
import { test, expect } from "@playwright/test";
import { open, aria, tap } from "./helpers";

test("Attach in New task takes files from the browser's file dialog", async ({ page }) => {
  await open(page, "new");
  const attach = page.getByRole("button", { name: "Attach", exact: true });
  await expect(attach).toBeVisible();
  const chooser = page.waitForEvent("filechooser");
  await tap(page, attach);
  await (await chooser).setFiles([
    { name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("# Notes\n\nfrom the browser") },
    { name: "pixel.png", mimeType: "image/png", buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64") },
  ]);
  await expect.poll(() => aria(page)).toContain("notes.md");
  const form = await aria(page);
  expect(form).toContain("pixel.png");
  expect(form).toContain("2 of 4");
});

test("a file over the limit is refused with the apps' message", async ({ page }) => {
  await open(page, "new");
  const chooser = page.waitForEvent("filechooser");
  await tap(page, page.getByRole("button", { name: "Attach", exact: true }));
  await (await chooser).setFiles([{ name: "big.bin", mimeType: "application/octet-stream", buffer: Buffer.alloc(5 * 1024 * 1024 + 1) }]);
  await expect.poll(() => aria(page)).toContain("Each attachment must be at most 5 MB.");
  expect(await aria(page)).not.toContain("big.bin");
});

test("Share on a mail message downloads its Markdown where the browser has no share sheet", async ({ page }) => {
  await open(page, "tab mail; mail Nightly build");
  let share = page.getByRole("button", { name: "Share", exact: true });
  if (!(await share.count())) {
    await tap(page, page.getByRole("button", { name: /^(More|More options|Options)$/ }).last());
    await expect.poll(() => aria(page)).toContain("Share");
    share = page.getByRole("button", { name: "Share", exact: true });
  }
  const download = page.waitForEvent("download");
  await tap(page, share);
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/Nightly build.*\.md$/);
});
