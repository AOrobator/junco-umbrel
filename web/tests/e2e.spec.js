const { test, expect } = require("@playwright/test");

test("walk full wallet flow", async ({ page }) => {
  const password = "correct-horse-battery-staple";
  const walletName = `JuncoTest-${Date.now()}`;

  await page.goto("/");

  const authOverlay = page.getByTestId("auth-overlay");
  if (await authOverlay.isVisible()) {
    const setupForm = page.getByTestId("auth-setup-form");
    if (await setupForm.isVisible()) {
      await setupForm.locator('input[name="password"]').fill(password);
      await setupForm.locator('input[name="confirm"]').fill(password);
      await setupForm.getByRole("button", { name: "Set password" }).click();
    }

    const loginForm = page.getByTestId("auth-login-form");
    await expect(loginForm).toBeVisible();
    await loginForm.locator('input[name="password"]').fill(password);
    await loginForm.getByRole("button", { name: "Unlock" }).click();

    await expect(authOverlay).toBeHidden();
  }

  await page.getByRole("button", { name: "Settings" }).first().click();
  await page.locator("#create-wallet-disclosure summary").click();
  const createForm = page.getByTestId("create-wallet-form");
  await createForm.locator('input[name="name"]').fill(walletName);
  await createForm.getByRole("button", { name: "Create wallet" }).click();

  const mnemonicPanel = page.getByTestId("mnemonic-panel");
  await expect(mnemonicPanel).toBeVisible();
  await page.getByTestId("clear-mnemonic").click();

  await expect(page.getByTestId("home-wallet-name")).toHaveText(walletName);

  await page.getByRole("button", { name: "Receive" }).first().click();
  const receiveForm = page.getByTestId("receive-form");
  await receiveForm.locator('input[name="amount"]').fill("0.001");
  await receiveForm.getByRole("button", { name: "Generate request" }).click();
  await expect(page.getByTestId("receive-address")).not.toHaveText("bc1q....");
  await expect(page.getByTestId("receive-address")).toHaveText(/(bc1|tb1|bcrt1)/);

  await page.getByRole("button", { name: "Send" }).first().click();
  const sendForm = page.getByTestId("send-form");
  await sendForm.locator('input[name="address"]').fill("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
  await sendForm.locator('input[name="amount"]').fill("0.0001");
  await sendForm.getByRole("button", { name: "Review send" }).click();
  await page.getByTestId("confirm-send").click();

  const toast = page.getByTestId("toast");
  await expect(toast).toBeVisible();
  await expect(toast).toContainText(/(Unable|Insufficient|Broadcasted|Invalid|Failed)/i);
});
