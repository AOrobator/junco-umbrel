import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import fs from "fs";
import path from "path";

const html = fs.readFileSync(path.resolve(__dirname, "../../index.html"), "utf8");

function writeDom() {
  const parser = new DOMParser();
  const parsed = parser.parseFromString(html, "text/html");
  while (document.head.firstChild) document.head.firstChild.remove();
  while (document.body.firstChild) document.body.firstChild.remove();
  for (const node of [...parsed.head.childNodes]) document.head.appendChild(document.adoptNode(node));
  for (const node of [...parsed.body.childNodes]) document.body.appendChild(document.adoptNode(node));
  window.__JUNCO_TEST__ = true;
  try { localStorage.clear(); } catch (_) { /* jsdom compat */ }
  navigator.clipboard = { writeText: vi.fn().mockResolvedValue(undefined) };
  global.fetch = vi.fn();
  global.Headers = window.Headers;
  global.FormData = window.FormData;
}

async function loadApp() {
  vi.resetModules();
  writeDom();
  await import("../../app.js");
  return window.__junco;
}

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  };
}

describe("app helpers", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("normalizes views and formats values", async () => {
    const app = await loadApp();

    expect(app.normalizeView("home")).toBe("home");
    expect(app.normalizeView("overview")).toBe("home");
    expect(app.normalizeView("unknown")).toBe("home");

    expect(app.formatBtcFromSats(100_000_000)).toBe("1.00000000 BTC");
    expect(app.formatUsdFromSats(0, 0)).toBe("—");
    expect(app.formatUsdFromSats(100_000_000, 35000)).toMatch(/^\D?\d/);
    expect(app.formatSats(12345)).toContain("12,345");
    expect(app.formatDate(0)).toBe("Pending");
    expect(app.formatTime(0)).toBe("");

    app.showToast("Hello", 10);
    expect(document.getElementById("toast").classList.contains("is-visible")).toBe(true);
    vi.advanceTimersByTime(20);
    expect(document.getElementById("toast").classList.contains("is-visible")).toBe(false);
  });
});

describe("electrum form helpers", () => {
  it("resolves presets and connection state", async () => {
    const app = await loadApp();

    expect(app.normalizeHost("  EXAMPLE.COM ")).toBe("example.com");
    expect(app.isOnionHost("abcd.onion")).toBe(true);
    expect(app.resolveElectrumConnection({ useProxy: true })).toBe("tor");
    expect(app.resolveElectrumConnection({ host: "abcd.onion" })).toBe("tor");
    expect(app.resolveElectrumConnection({ host: "example.com" })).toBe("direct");

    app.updateElectrumProxyVisibility("tor");
    expect(document.getElementById("electrum-proxy-row").classList.contains("is-hidden")).toBe(false);
    expect(document.querySelector('#electrum-form input[name="proxy"]').value).toBe("tor:9050");

    expect(app.getPresetForConfig({})).toBe("mempool-space");
    expect(
      app.getPresetForConfig({
        host: "electrum.mempool.space",
        port: 50002,
        ssl: true,
      })
    ).toBe("mempool-space");
    expect(app.getPresetForConfig({ host: "custom", port: 1, ssl: false })).toBe("custom");

    app.setElectrumFormValues({ host: "example.com", port: 50001, ssl: false, proxyServer: "" });
    expect(document.querySelector('#electrum-form input[name="host"]').value).toBe("example.com");
    expect(document.querySelector('#electrum-form select[name="ssl"]').value).toBe("false");

    app.state.electrum.customDraft = {
      host: "draft",
      port: "123",
      ssl: "true",
      certificatePath: "cert",
      useProxy: true,
      proxyServer: "tor:9050",
    };
    app.applyElectrumPreset("custom");
    expect(document.querySelector('#electrum-form input[name="host"]').value).toBe("draft");

    app.applyElectrumPreset("unknown");
    app.applyElectrumPreset("mempool-space");
    expect(document.querySelector('#electrum-form input[name="host"]').value).toBe("electrum.mempool.space");

    app.state.electrum.config = {
      host: "custom-host",
      port: 5555,
      ssl: false,
      certificatePath: "",
      useProxy: true,
      proxyServer: "tor:9050",
    };
    app.syncElectrumForm();
    expect(app.state.electrum.preset).toBe("custom");
    expect(app.state.electrum.customDraft.host).toBe("custom-host");
  });
});

describe("api and auth flows", () => {
  it("handles api fetch responses and auth overlay", async () => {
    const app = await loadApp();

    app.state.auth.csrfToken = "csrf";
    fetch.mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    await app.apiFetch("/api/test", { method: "POST", body: "{}" });
    const headers = fetch.mock.calls[0][1].headers;
    expect(headers.get("X-CSRF-Token")).toBe("csrf");

    fetch.mockResolvedValueOnce(jsonResponse(401, { error: "Unauthorized" }));
    await expect(app.apiFetch("/api/auth", { method: "GET" })).rejects.toThrow("Unauthorized");
    expect(app.state.auth.authenticated).toBe(false);

    fetch.mockResolvedValueOnce(jsonResponse(400, { error: "Bad" }));
    await expect(app.apiFetch("/api/bad", { method: "GET" })).rejects.toThrow("Bad");

    fetch.mockResolvedValueOnce(jsonResponse(200, { configured: true, authenticated: false, csrfToken: "t" }));
    await app.loadAuthStatus();
    expect(app.state.auth.configured).toBe(true);
    expect(document.getElementById("auth-login-panel").classList.contains("is-hidden")).toBe(false);

    fetch
      .mockResolvedValueOnce(jsonResponse(200, { configured: true, authenticated: true, csrfToken: "t2" }))
      .mockResolvedValueOnce(jsonResponse(200, []))
      .mockResolvedValueOnce(jsonResponse(200, { host: "", port: null, ssl: true }))
      .mockResolvedValueOnce(jsonResponse(200, { connected: false }));
    await app.login("pw");
    expect(app.state.auth.authenticated).toBe(true);
  });

  it("toggles ftue state and callouts", async () => {
    const app = await loadApp();

    app.state.auth.authenticated = true;
    app.state.wallets = [];
    app.state.ftueDismissed = false;
    app.renderFtue();
    expect(document.getElementById("ftue-overlay").classList.contains("is-hidden")).toBe(false);

    app.state.ftueDismissed = true;
    app.renderFtueCallout();
    expect(document.getElementById("ftue-callout").classList.contains("is-hidden")).toBe(false);

    app.setFtueDismissed(false);
    expect(app.state.ftueDismissed).toBe(false);

    app.setFtueDismissed(true);
    expect(app.state.ftueDismissed).toBe(true);
  });
});

describe("wallet rendering and flows", () => {
  it("renders wallet lists and home states", async () => {
    const app = await loadApp();

    app.state.wallets = [];
    app.state.walletSummary = null;
    app.render();
    expect(document.getElementById("home-wallet-name").textContent).toBe("No wallet yet");

    app.state.wallets = ["Alpha"];
    app.state.walletSummary = {
      name: "Alpha",
      policyType: "SINGLE",
      scriptType: "P2WPKH",
      watchOnly: true,
      network: "MAINNET",
    };
    app.state.balanceSats = 123456;
    app.state.priceUsd = null;
    app.state.transactions = [
      { valueSats: 1000, confirmations: 2, timestamp: 1710000000, label: "In" },
    ];
    app.render();
    expect(document.getElementById("home-wallet-name").textContent).toBe("Alpha");
    expect(document.getElementById("home-wallet-meta").textContent).toContain("Watch-only");
  });

  it("handles sparkline and transactions filtering", async () => {
    const app = await loadApp();

    app.renderSparkline([]);
    expect(document.getElementById("home-balance-trend").textContent).toBe("No history yet");

    app.renderSparkline([
      { timestamp: 1, balanceSats: 100 },
      { timestamp: 2, balanceSats: 50 },
    ]);
    expect(document.getElementById("home-balance-trend").textContent).toMatch(/Down/);

    app.state.transactions = [
      { valueSats: 1000, confirmations: 0, timestamp: 1710000000 },
      { valueSats: -500, confirmations: 2, timestamp: 1710001000 },
    ];
    app.state.balanceSats = 500;
    app.state.priceUsd = 10000;
    app.state.txFilter = "in";
    app.renderTransactions();
    expect(document.getElementById("transactions-list").textContent).toContain("Incoming");

    app.state.txFilter = "out";
    app.renderTransactions();
    expect(document.getElementById("transactions-list").textContent).toContain("Outgoing");

    app.state.txFilter = "all";
    app.renderHomeActivity([]);
    expect(document.getElementById("home-activity-list").textContent).toContain("No activity");
  });

  it("updates receive and send review states", async () => {
    const app = await loadApp();

    app.state.pendingSend = null;
    app.renderSendReview();
    expect(document.getElementById("send-review").textContent).toContain("Fill the form");

    app.state.pendingSend = { address: "bc1qtest", amountSats: 1000, feeRate: 2 };
    app.renderSendReview();
    expect(document.getElementById("send-review").textContent).toContain("bc1qtest");

    app.state.pendingReceive = null;
    app.renderReceive();
    expect(document.getElementById("receive-address").textContent).toBe("—");

    app.state.pendingReceive = { address: "bc1qrx", amountBtc: 0.1 };
    app.renderReceive();
    expect(document.getElementById("receive-address").textContent).toBe("bc1qrx");
  });
});

describe("async flows", () => {
  it("refreshes wallet list and electrum status", async () => {
    const app = await loadApp();

    fetch
      .mockResolvedValueOnce(jsonResponse(200, ["WalletOne"]))
      .mockResolvedValueOnce(jsonResponse(200, { name: "WalletOne", policyType: "SINGLE", scriptType: "P2WPKH" }))
      .mockResolvedValueOnce(jsonResponse(200, { balanceSats: 10, transactions: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { balanceSats: 10, history: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { usd: 10000 }))
      .mockResolvedValueOnce(
        jsonResponse(200, { host: "electrum.mempool.space", port: 50002, ssl: true })
      )
      .mockResolvedValueOnce(jsonResponse(200, { connected: true, tipHeight: 1, serverVersion: ["v"] }));

    await app.refreshWalletList();
    expect(app.state.activeWallet).toBe("WalletOne");
    await app.refreshElectrum();
    expect(app.state.electrum.status.connected).toBe(true);
  });

  it("handles refresh errors and sends", async () => {
    const app = await loadApp();
    app.state.activeWallet = "Alpha";

    fetch.mockResolvedValueOnce(jsonResponse(500, { error: "Nope" })).mockResolvedValueOnce(jsonResponse(200, {}));
    await app.refreshWalletData("Alpha");
    expect(app.state.balanceSats).toBe(0);

    fetch.mockResolvedValueOnce(jsonResponse(200, { address: "bc1qaddr" }));
    await app.requestReceive("label");
    expect(app.state.pendingReceive.address).toBe("bc1qaddr");

    app.state.pendingSend = { address: "bc1qaddr", amountSats: 1000, feeRate: 1, note: "" };
    fetch
      .mockResolvedValueOnce(jsonResponse(200, { txid: "abc123def456" }))
      .mockResolvedValueOnce(jsonResponse(200, { transactions: [], balanceSats: 0 }))
      .mockResolvedValueOnce(jsonResponse(200, { balanceSats: 0, history: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { usd: 0 }));
    await app.sendPayment();
    expect(app.state.pendingSend).toBe(null);
  });
});
