const { test, expect } = require("@playwright/test");

test("walk full wallet flow", async ({ page }) => {
  const password = process.env.JUNCO_PASSWORD || "correct-horse-battery-staple";
  const walletName = `JuncoTest-${Date.now()}`;
  const watchXpub =
    "xpub6BzAmegh8uGpcyLLq77YBY4TkUdJcTmrCci5znumAQEeiDseM5BtRwx9htFRUemwC5WMTQiXjkqHjdarZAib5CbhQ3JsKM2v3Dm8f25sVEw";

  await page.route("**/api/electrum/status", async (route) => {
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ error: "status unavailable" }),
    });
  });

  await page.goto("/");

  const authOverlay = page.getByTestId("auth-overlay");
  await page.waitForLoadState("domcontentloaded");
  const networkPill = page.locator("#network-pill");
  await expect(networkPill).toBeVisible();
  const pillStyles = await networkPill.evaluate((el) => {
    const style = window.getComputedStyle(el);
    return {
      display: style.display,
      alignItems: style.alignItems,
      lineHeight: style.lineHeight,
      fontSize: style.fontSize,
    };
  });
  expect(pillStyles.display).toContain("flex");
  expect(pillStyles.alignItems).toBe("center");
  expect(Math.abs(parseFloat(pillStyles.lineHeight) - parseFloat(pillStyles.fontSize))).toBeLessThan(
    0.5
  );
  try {
    await authOverlay.waitFor({ state: "visible", timeout: 2000 });
  } catch {
    // overlay might not appear if already authenticated
  }

  if (await authOverlay.isVisible()) {
    const setupForm = page.getByTestId("auth-setup-form");
    if (await setupForm.isVisible()) {
      await setupForm.locator('input[name="password"]').fill(password);
      await setupForm.locator('input[name="confirm"]').fill(password);
      await setupForm.getByRole("button", { name: "Set password" }).click();
    } else if (!process.env.JUNCO_PASSWORD) {
      test.skip(true, "Auth already configured. Set JUNCO_PASSWORD to run this test.");
    }

    const loginForm = page.getByTestId("auth-login-form");
    await expect(loginForm).toBeVisible({ timeout: 10000 });
    await loginForm.locator('input[name="password"]').fill(password);
    await loginForm.getByRole("button", { name: "Unlock" }).click();
  }

  await expect(authOverlay).toBeHidden({ timeout: 10000 });

  const ftueOverlay = page.getByTestId("ftue-overlay");
  if (await ftueOverlay.isVisible().catch(() => false)) {
    await ftueOverlay.getByRole("button", { name: "Skip for now" }).click();
  }

  const homeWalletName = page.getByTestId("home-wallet-name");
  const homeWalletText = (await homeWalletName.textContent()) || "";
  if (homeWalletText.includes("No wallet yet")) {
    const homeCta = page.getByTestId("home-cta");
    await expect(homeCta).toBeVisible();
    await expect(homeCta.getByRole("button", { name: "Create wallet" })).toBeVisible();
  }

  await page.getByRole("button", { name: "Settings" }).first().click();
  const createDisclosure = page.locator("#create-wallet-disclosure");
  const createSummary = createDisclosure.locator("summary");
  const summaryBox = await createSummary.boundingBox();
  if (summaryBox) {
    await page.mouse.click(summaryBox.x + summaryBox.width - 4, summaryBox.y + summaryBox.height / 2);
    await expect(createDisclosure).toHaveAttribute("open", "");
  } else {
    await createSummary.click();
  }
  await page.locator("#electrum-disclosure summary").click();
  const electrumForm = page.getByTestId("electrum-form");
  const presetSelect = electrumForm.locator('select[name="preset"]');
  const connectionSelect = electrumForm.locator('select[name="connection"]');
  const proxyRow = electrumForm.locator("#electrum-proxy-row");
  await expect(presetSelect).toHaveValue("mempool-space");
  await presetSelect.selectOption("mempool-guide");
  await expect(electrumForm.locator('input[name="host"]')).toHaveValue("electrum.mempool.guide");
  await expect(electrumForm.locator('input[name="port"]')).toHaveValue("50002");
  await expect(electrumForm.locator('select[name="ssl"]')).toHaveValue("true");
  await expect(connectionSelect).toHaveValue("direct");
  await expect(proxyRow).toBeHidden();

  await presetSelect.selectOption("custom");
  await electrumForm.locator('input[name="host"]').fill("example.onion");
  await expect(connectionSelect).toHaveValue("tor");
  await expect(proxyRow).toBeVisible();
  await expect(electrumForm.locator('input[name="proxy"]')).toHaveValue("tor:9050");

  await page.locator("#import-wallet-disclosure summary").click();
  const importForm = page.getByTestId("import-wallet-form");
  const scriptSelect = importForm.locator('select[name="script"]');
  const paddingRight = await scriptSelect.evaluate(
    (el) => window.getComputedStyle(el).paddingRight
  );
  expect(parseFloat(paddingRight)).toBeGreaterThanOrEqual(32);
  await importForm.locator('input[name="name"]').fill(walletName);
  await importForm.locator('input[name="xpub"]').fill(watchXpub);
  await importForm.getByRole("button", { name: "Import watch-only" }).click();

  const mnemonicPanel = page.getByTestId("mnemonic-panel");
  await expect(mnemonicPanel).toBeHidden();

  await expect(page.getByTestId("home-wallet-name")).toHaveText(walletName);
  await expect(page.locator("#home-wallet-meta")).toContainText("Watch-only");
  await expect(page.locator("#home-balance-fiat")).not.toHaveText("—");

  await page.getByRole("button", { name: "Receive" }).first().click();
  const receiveForm = page.getByTestId("receive-form");
  await receiveForm.locator('input[name="amount"]').fill("0.001");
  await receiveForm.getByRole("button", { name: "Generate request" }).click();
  await expect(page.getByTestId("receive-address")).not.toHaveText("bc1q....");
  await expect(page.getByTestId("receive-address")).toHaveText(/(bc1|tb1|bcrt1|1|3|m|n)/);

  await page.getByRole("button", { name: "Send" }).first().click();
  const sendForm = page.getByTestId("send-form");
  await expect(sendForm.locator('input[name="address"]')).toBeDisabled();
  await expect(page.getByTestId("confirm-send")).toBeDisabled();
});
