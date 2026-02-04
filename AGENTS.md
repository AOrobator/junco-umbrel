# Project Agent Instructions

Always complete the following **after code changes** (not docs/markdown-only changes):
- Write or update tests that cover the change.
- Run the test suite: `npm run test:e2e`.
- Launch the app via Docker: `docker compose up --build -d`.
- Validate the feature with Playwright MCP (CLI automation). Features are not done until this is completed.
  - If Playwright MCP fails to launch with an "Opening in existing browser session" error, remove the MCP Chrome profile cache at `~/Library/Caches/ms-playwright/mcp-chrome` and retry.
  - If auth must be reset to run tests, delete `./.junco/adapter/auth.json` and rerun Docker + tests.

Data resets (clearing `./.junco` for FTUE testing) are **not** code/content changes:
- Do **not** auto-run tests after a data reset when FTUE is requested.
- Only restart Docker to apply the clean state.

Docs/markdown-only updates are **not** code changes:
- Do **not** auto-run tests for docs-only changes.
