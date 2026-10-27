import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  workers: 1, // one shared daemon
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE || "http://127.0.0.1:18100/api/hassio_ingress/t0ken/",
    viewport: { width: 1300, height: 900 },
    trace: "retain-on-failure",
  },
});
