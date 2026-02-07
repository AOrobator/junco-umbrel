const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./web/tests",
  testIgnore: ["**/unit/**"],
  timeout: 90_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  retries: 0,
  use: {
    baseURL: process.env.JUNCO_BASE_URL || "http://localhost:3009",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  reporter: [
    ["list"],
    ["html", { outputFolder: "playwright-report", open: "never" }],
  ],
});
