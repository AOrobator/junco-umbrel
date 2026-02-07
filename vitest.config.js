import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    include: ["web/tests/unit/**/*.test.js"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      all: true,
      include: ["web/app.js"],
      exclude: ["web/tests/**", "web/index.html", "web/styles.css"],
      lines: 90,
      branches: 90,
      statements: 90,
      functions: 90,
    },
  },
});
