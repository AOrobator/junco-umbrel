const allowedViews = ["home", "send", "receive", "activity", "settings"];
const viewAliases = {
  overview: "home",
  wallets: "settings",
  transactions: "activity",
  electrum: "settings",
};

const ftueStorageKey = "junco.ftueDismissed";
const savedFtueDismissed = (() => {
  try {
    return localStorage.getItem(ftueStorageKey) === "1";
  } catch (_) {
    return false;
  }
})();

const state = {
  view: "home",
  auth: {
    configured: false,
    authenticated: false,
    csrfToken: null,
  },
  wallets: [],
  activeWallet: null,
  walletSummary: null,
  balanceSats: 0,
  balanceHistory: [],
  priceUsd: null,
  priceUpdatedAt: null,
  priceSource: null,
  transactions: [],
  pendingReceive: null,
  pendingSend: null,
  lastMnemonic: null,
  pendingSeedWallet: null,
  ftueStep: 1,
  ftueDismissed: savedFtueDismissed,
  txFilter: "all",
  electrum: {
    config: null,
    status: null,
    lastChecked: null,
    preset: null,
    customDraft: null,
  },
};

const elements = {
  app: document.querySelector(".app"),
  views: document.querySelectorAll(".view"),
  navButtons: document.querySelectorAll("[data-view-target]"),
  walletList: document.getElementById("wallet-list"),
  createWalletForm: document.getElementById("create-wallet-form"),
  importWalletForm: document.getElementById("import-wallet-form"),
  openWalletForm: document.getElementById("open-wallet-form"),
  openWalletSelect: document.getElementById("open-wallet-select"),
  receiveForm: document.getElementById("receive-form"),
  sendForm: document.getElementById("send-form"),
  electrumForm: document.getElementById("electrum-form"),
  transactionsList: document.getElementById("transactions-list"),
  toast: document.getElementById("toast"),
  createDisclosure: document.getElementById("create-wallet-disclosure"),
  importDisclosure: document.getElementById("import-wallet-disclosure"),
  openDisclosure: document.getElementById("open-wallet-disclosure"),
  homeActivityList: document.getElementById("home-activity-list"),
  sparklineLine: document.getElementById("balance-sparkline"),
  sparklineArea: document.getElementById("balance-sparkline-area"),
  sparklineGlow: document.getElementById("balance-sparkline-glow"),
  sparklineDot: document.getElementById("balance-sparkline-dot"),
  homeTrend: document.getElementById("home-balance-trend"),
  balanceHigh: document.getElementById("balance-high"),
  balanceLow: document.getElementById("balance-low"),
  balanceRange: document.getElementById("balance-range"),
  homeWalletName: document.getElementById("home-wallet-name"),
  homeWalletMeta: document.getElementById("home-wallet-meta"),
  homeCta: document.getElementById("home-cta"),
  homeCtaOpen: document.getElementById("home-cta-open"),
  homeBalance: document.getElementById("home-balance"),
  homeBalanceFiat: document.getElementById("home-balance-fiat"),
  networkPill: document.getElementById("network-pill"),
  headerElectrum: document.getElementById("header-electrum"),
  transactionCount: document.getElementById("transaction-count"),
  transactionBalance: document.getElementById("transaction-balance"),
  sendContext: document.getElementById("send-context"),
  receiveContext: document.getElementById("receive-context"),
  sendReview: document.getElementById("send-review"),
  confirmSend: document.getElementById("confirm-send"),
  receiveRequest: document.getElementById("receive-request"),
  receiveAddress: document.getElementById("receive-address"),
  receiveTotal: document.getElementById("receive-total"),
  receiveStatus: document.getElementById("receive-status"),
  copyAddress: document.getElementById("copy-address"),
  newAddress: document.getElementById("new-address"),
  markReceived: document.getElementById("mark-received"),
  electrumPreset: document.getElementById("electrum-preset"),
  electrumHost: document.querySelector('#electrum-form input[name="host"]'),
  electrumPort: document.querySelector('#electrum-form input[name="port"]'),
  electrumSsl: document.querySelector('#electrum-form select[name="ssl"]'),
  electrumCertificate: document.querySelector('#electrum-form input[name="certificate"]'),
  electrumConnection: document.getElementById("electrum-connection"),
  electrumProxy: document.querySelector('#electrum-form input[name="proxy"]'),
  electrumProxyRow: document.getElementById("electrum-proxy-row"),
  electrumStatusDetail: document.getElementById("electrum-status-detail"),
  electrumDetail: document.getElementById("electrum-detail"),
  electrumCheck: document.getElementById("electrum-check"),
  electrumTip: document.getElementById("electrum-tip"),
  authOverlay: document.getElementById("auth-overlay"),
  authSetupPanel: document.getElementById("auth-setup-panel"),
  authLoginPanel: document.getElementById("auth-login-panel"),
  authSetupForm: document.getElementById("auth-setup-form"),
  authLoginForm: document.getElementById("auth-login-form"),
  authLogout: document.getElementById("auth-logout"),
  mnemonicPanel: document.getElementById("mnemonic-panel"),
  createdMnemonic: document.getElementById("created-mnemonic"),
  copyMnemonic: document.getElementById("copy-mnemonic"),
  clearMnemonic: document.getElementById("clear-mnemonic"),
  entropyRow: document.getElementById("entropy-row"),
  mnemonicRow: document.getElementById("mnemonic-row"),
  generateCheckbox: document.querySelector('#create-wallet-form input[name="generate"]'),
  ftueOverlay: document.getElementById("ftue-overlay"),
  ftueSteps: document.querySelectorAll("[data-ftue-step]"),
  ftueNext: document.getElementById("ftue-next"),
  ftueBack: null,
  ftueSkip: document.getElementById("ftue-skip"),
  ftueConfigure: document.getElementById("ftue-configure-electrum"),
  ftueCreate: document.getElementById("ftue-create-wallet"),
  ftueImport: document.getElementById("ftue-import-wallet"),
  ftueCallout: document.getElementById("ftue-callout"),
  ftueFinish: document.getElementById("ftue-finish"),
};

function normalizeView(view) {
  if (allowedViews.includes(view)) return view;
  if (viewAliases[view]) return viewAliases[view];
  return "home";
}

function setView(view) {
  state.view = normalizeView(view);
  elements.views.forEach((section) => {
    section.classList.toggle("is-active", section.dataset.view === state.view);
  });
  elements.navButtons.forEach((button) => {
    if (!button.dataset.viewTarget) return;
    button.classList.toggle("is-active", button.dataset.viewTarget === state.view);
  });
}

function showToast(message, timeout = 2400) {
  elements.toast.textContent = message;
  elements.toast.classList.add("is-visible");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    elements.toast.classList.remove("is-visible");
  }, timeout);
}

function formatBtcFromSats(sats, decimals = 8) {
  const value = sats / 100_000_000;
  return `${value.toFixed(decimals)} BTC`;
}

const usdFormatter = new Intl.NumberFormat(undefined, {
  style: "currency",
  currency: "USD",
  maximumFractionDigits: 2,
});

function formatUsdFromSats(sats, rateUsd) {
  if (!Number.isFinite(rateUsd) || rateUsd <= 0) return "—";
  const value = (sats / 100_000_000) * rateUsd;
  return usdFormatter.format(value);
}

function formatSats(sats) {
  return `${sats.toLocaleString()} sats`;
}

function formatDate(timestampSec) {
  if (!timestampSec) return "Pending";
  const date = new Date(timestampSec * 1000);
  return date.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function formatTime(timestampSec) {
  if (!timestampSec) return "";
  const date = new Date(timestampSec * 1000);
  return date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  });
}

const electrumPresets = [
  {
    id: "mempool-space",
    label: "mempool.space (public)",
    host: "electrum.mempool.space",
    port: 50002,
    ssl: true,
  },
  {
    id: "mempool-guide",
    label: "mempool.guide (public)",
    host: "electrum.mempool.guide",
    port: 50002,
    ssl: true,
  },
];

const defaultElectrumPresetId = electrumPresets[0]?.id || "custom";
const electrumPresetMap = electrumPresets.reduce((acc, preset) => {
  acc[preset.id] = preset;
  return acc;
}, {});

const torProxyDefault = "tor:9050";

function normalizeHost(host) {
  return (host || "").trim().toLowerCase();
}

function isOnionHost(host) {
  return normalizeHost(host).endsWith(".onion");
}

function resolveElectrumConnection(values) {
  if (values?.useProxy === true || values?.useProxy === "true") return "tor";
  if (isOnionHost(values?.host)) return "tor";
  return "direct";
}

function updateElectrumProxyVisibility(connection) {
  const useTor = connection === "tor";
  if (elements.electrumProxyRow) {
    elements.electrumProxyRow.classList.toggle("is-hidden", !useTor);
  }
  if (useTor && elements.electrumProxy && !elements.electrumProxy.value.trim()) {
    elements.electrumProxy.value = torProxyDefault;
  }
}

function getPresetForConfig(config) {
  if (!config?.host || !config.host.trim()) return defaultElectrumPresetId;
  const host = normalizeHost(config.host);
  const port = config.port ?? null;
  const ssl = Boolean(config.ssl);
  const match = electrumPresets.find(
    (preset) =>
      normalizeHost(preset.host) === host &&
      (preset.port ?? null) === port &&
      Boolean(preset.ssl) === ssl
  );
  return match ? match.id : "custom";
}

function getElectrumFormValues() {
  const connection =
    elements.electrumConnection?.value ||
    resolveElectrumConnection({ host: elements.electrumHost?.value, useProxy: false });
  const useProxy = connection === "tor";
  return {
    host: elements.electrumHost?.value?.trim() || "",
    port: elements.electrumPort?.value?.trim() || "",
    ssl: elements.electrumSsl?.value || "true",
    certificatePath: elements.electrumCertificate?.value?.trim() || "",
    useProxy,
    proxyServer: elements.electrumProxy?.value?.trim() || "",
  };
}

function setElectrumFormValues(values) {
  if (elements.electrumHost) {
    elements.electrumHost.value = values.host ?? "";
  }
  if (elements.electrumPort) {
    elements.electrumPort.value = values.port ?? "";
  }
  if (elements.electrumSsl) {
    elements.electrumSsl.value = values.ssl === false || values.ssl === "false" ? "false" : "true";
  }
  if (elements.electrumCertificate) {
    elements.electrumCertificate.value = values.certificatePath ?? "";
  }
  const connection = resolveElectrumConnection(values);
  if (elements.electrumConnection) {
    elements.electrumConnection.value = connection;
  }
  if (elements.electrumProxy) {
    elements.electrumProxy.value = values.proxyServer ?? "";
  }
  updateElectrumProxyVisibility(connection);
}

function applyElectrumPreset(presetId, overrides = {}) {
  if (presetId === "custom") {
    const draft = state.electrum.customDraft || {};
    setElectrumFormValues({
      host: draft.host ?? "",
      port: draft.port ?? "",
      ssl: draft.ssl ?? "true",
      certificatePath: draft.certificatePath ?? "",
      useProxy: draft.useProxy ?? false,
      proxyServer: draft.proxyServer ?? "",
    });
    return;
  }
  const preset = electrumPresetMap[presetId];
  if (!preset) return;
  setElectrumFormValues({
    host: preset.host,
    port: preset.port?.toString() ?? "",
    ssl: preset.ssl ? "true" : "false",
    certificatePath: "",
    useProxy: overrides.useProxy ?? false,
    proxyServer: overrides.proxyServer ?? "",
  });
}

function syncElectrumForm() {
  if (!elements.electrumForm || !elements.electrumPreset) return;
  const config = state.electrum.config;
  if (!config) return;
  const presetId = getPresetForConfig(config);
  state.electrum.preset = presetId;
  elements.electrumPreset.value = presetId;
  if (presetId === "custom") {
    state.electrum.customDraft = {
      host: config.host || "",
      port: config.port?.toString() ?? "",
      ssl: config.ssl ? "true" : "false",
      certificatePath: config.certificatePath || "",
      useProxy: Boolean(config.useProxy),
      proxyServer: config.proxyServer || "",
    };
  }
  applyElectrumPreset(presetId, {
    useProxy: Boolean(config.useProxy),
    proxyServer: config.proxyServer || "",
  });
}

async function apiFetch(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && state.auth.csrfToken) {
    headers.set("X-CSRF-Token", state.auth.csrfToken);
  }

  const response = await fetch(path, {
    ...options,
    method,
    headers,
    credentials: "same-origin",
  });

  let data = null;
  try {
    data = await response.json();
  } catch (_) {
    data = null;
  }

  if (response.status === 401) {
    state.auth.authenticated = false;
    state.auth.csrfToken = null;
    showAuthOverlay();
    throw new Error("Unauthorized");
  }

  if (!response.ok) {
    const message = data?.error || "Request failed";
    throw new Error(message);
  }

  return data;
}

function showAuthOverlay() {
  elements.authOverlay.classList.remove("is-hidden");
  elements.app.classList.add("is-locked");
  if (elements.authLogout) {
    elements.authLogout.classList.toggle("is-hidden", !state.auth.authenticated);
  }
  if (!state.auth.configured) {
    elements.authSetupPanel.classList.remove("is-hidden");
    elements.authLoginPanel.classList.add("is-hidden");
  } else if (!state.auth.authenticated) {
    elements.authLoginPanel.classList.remove("is-hidden");
    elements.authSetupPanel.classList.add("is-hidden");
  } else {
    elements.authOverlay.classList.add("is-hidden");
    elements.app.classList.remove("is-locked");
  }
}

function setFtueDismissed(value) {
  state.ftueDismissed = value;
  try {
    if (value) {
      localStorage.setItem(ftueStorageKey, "1");
    } else {
      localStorage.removeItem(ftueStorageKey);
    }
  } catch (_) {
    // ignore storage failures
  }
}

function shouldShowFtue() {
  return state.auth.authenticated && state.wallets.length === 0 && !state.ftueDismissed;
}

function shouldShowFtueCallout() {
  return state.auth.authenticated && state.wallets.length === 0 && state.ftueDismissed;
}

function renderFtue() {
  if (!elements.ftueOverlay) return;
  const show = shouldShowFtue();
  elements.ftueOverlay.classList.toggle("is-hidden", !show);
  elements.app.classList.toggle("is-locked", show || !elements.authOverlay.classList.contains("is-hidden"));
  if (!show) return;

  const total = elements.ftueSteps.length;
  state.ftueStep = Math.min(Math.max(state.ftueStep, 1), total);
  elements.ftueSteps.forEach((step) => {
    step.classList.toggle("is-active", step.dataset.ftueStep === String(state.ftueStep));
  });
  if (elements.ftueBack) {
    elements.ftueBack.disabled = state.ftueStep === 1;
  }
  if (elements.ftueNext) {
    elements.ftueNext.classList.toggle("is-hidden", state.ftueStep === total);
  }
}

function renderFtueCallout() {
  if (!elements.ftueCallout) return;
  elements.ftueCallout.classList.toggle("is-hidden", !shouldShowFtueCallout());
}

async function loadAuthStatus() {
  const status = await apiFetch("/api/auth/status");
  state.auth.configured = status.configured;
  state.auth.authenticated = status.authenticated;
  state.auth.csrfToken = status.csrfToken;
  showAuthOverlay();
}

async function login(password) {
  const status = await apiFetch("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ password }),
  });
  state.auth.configured = status.configured;
  state.auth.authenticated = status.authenticated;
  state.auth.csrfToken = status.csrfToken;
  showAuthOverlay();
  await bootstrap();
}

async function setupPassword(password, confirm) {
  await apiFetch("/api/auth/setup", {
    method: "POST",
    body: JSON.stringify({ password, confirm }),
  });
  state.auth.configured = true;
  state.auth.authenticated = false;
  state.auth.csrfToken = null;
  showAuthOverlay();
}

async function logout() {
  await apiFetch("/api/auth/logout", { method: "POST" });
  state.auth.authenticated = false;
  state.auth.csrfToken = null;
  state.lastMnemonic = null;
  state.pendingSeedWallet = null;
  showAuthOverlay();
}

async function bootstrap() {
  await refreshWalletList();
  await refreshElectrum();
}

async function refreshWalletList() {
  state.wallets = await apiFetch("/api/wallets");
  renderWalletList();
  if (state.activeWallet && !state.wallets.includes(state.activeWallet)) {
    state.activeWallet = null;
    state.walletSummary = null;
  }
  if (!state.activeWallet && state.wallets.length > 0) {
    try {
      await openWallet(state.wallets[0]);
    } catch (error) {
      showToast(error.message || "Unable to open wallet");
    }
    return;
  }
  if (state.wallets.length === 0) {
    state.activeWallet = null;
    state.walletSummary = null;
    state.transactions = [];
    state.balanceSats = 0;
    state.balanceHistory = [];
    render();
  }
}

async function openWallet(name) {
  const summary = await apiFetch("/api/wallets/open", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
  state.activeWallet = name;
  state.walletSummary = summary;
  await refreshWalletData(name);
}

async function refreshWalletData(name) {
  try {
    const [transactions, balance] = await Promise.all([
      apiFetch(`/api/wallets/${encodeURIComponent(name)}/transactions`),
      apiFetch(`/api/wallets/${encodeURIComponent(name)}/balance`),
    ]);
    state.transactions = transactions.transactions || [];
    state.balanceSats = balance.balanceSats ?? transactions.balanceSats ?? 0;
    state.balanceHistory = balance.history || [];
  } catch (error) {
    state.transactions = [];
    state.balanceSats = 0;
    state.balanceHistory = [];
    showToast(error.message || "Unable to refresh wallet data");
  }
  await refreshPrice();
  render();
}

async function refreshPrice() {
  try {
    const price = await apiFetch("/api/price");
    const usd = price?.usd;
    if (Number.isFinite(usd) && usd > 0) {
      state.priceUsd = usd;
      state.priceUpdatedAt = price.updatedAt ?? null;
      state.priceSource = price.source ?? null;
    } else {
      state.priceUsd = null;
      state.priceUpdatedAt = null;
      state.priceSource = null;
    }
  } catch (_) {
    // Keep the last known quote if the refresh fails.
  }
}

async function requestReceive(label) {
  if (!state.activeWallet) return;
  const response = await apiFetch(
    `/api/wallets/${encodeURIComponent(state.activeWallet)}/receive`,
    {
      method: "POST",
      body: JSON.stringify({ label: label || null }),
    }
  );
  state.pendingReceive = {
    label: label || "",
    address: response.address,
  };
  renderReceive();
}

async function sendPayment() {
  if (!state.activeWallet || !state.pendingSend) return;
  const { address, amountSats, feeRate, note } = state.pendingSend;
  const response = await apiFetch(
    `/api/wallets/${encodeURIComponent(state.activeWallet)}/send`,
    {
      method: "POST",
      body: JSON.stringify({
        outputs: [{ address, amountSats, label: note || null }],
        feeRate,
        allowRbf: true,
      }),
    }
  );
  state.pendingSend = null;
  showToast(`Broadcasted ${response.txid.slice(0, 10)}...`);
  await refreshWalletData(state.activeWallet);
  render();
}

async function refreshElectrum() {
  let config = null;
  let status = null;
  try {
    config = await apiFetch("/api/electrum");
  } catch (error) {
    showToast(error.message || "Unable to load Electrum settings");
  }
  try {
    status = await apiFetch("/api/electrum/status");
  } catch (error) {
    status = { connected: false, error: error.message };
  }
  if (config) {
    state.electrum.config = config;
    syncElectrumForm();
  }
  state.electrum.status = status;
  state.electrum.lastChecked = Date.now();
  renderElectrum();
}

function render() {
  renderWalletList();
  renderHome();
  renderWalletContext();
  renderTransactions();
  renderSendReview();
  renderReceive();
  renderElectrum();
  renderMnemonicPanel();
  renderFtue();
  renderFtueCallout();
}

function renderWalletList() {
  elements.walletList.innerHTML = "";
  const fragment = document.createDocumentFragment();
  if (state.wallets.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "No wallets found yet.";
    fragment.appendChild(empty);
  } else {
    state.wallets.forEach((name) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "wallet-item";
      if (state.activeWallet === name) {
        button.classList.add("is-active");
      }
      const title = document.createElement("span");
      title.className = "wallet-title";
      title.textContent = name;
      const status = document.createElement("span");
      status.className = "wallet-meta";
      status.textContent = state.activeWallet === name ? "Active" : "Open";
      button.append(title, status);
      button.addEventListener("click", () => openWallet(name));
      fragment.appendChild(button);
    });
  }
  elements.walletList.appendChild(fragment);

  if (elements.openDisclosure) {
    elements.openDisclosure.classList.toggle("is-hidden", state.wallets.length === 0);
  }

  if (elements.openWalletSelect) {
    elements.openWalletSelect.innerHTML = "";
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "Select a wallet";
    elements.openWalletSelect.appendChild(placeholder);
    state.wallets.forEach((name) => {
      const option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      elements.openWalletSelect.appendChild(option);
    });
  }
}

function renderHome() {
  if (!state.walletSummary) {
    elements.homeWalletName.textContent = "No wallet yet";
    elements.homeWalletMeta.textContent =
      "Create a wallet or import a watch-only wallet to get started.";
    elements.homeBalance.textContent = "0.00000000 BTC";
    elements.homeBalanceFiat.textContent = "—";
    elements.networkPill.textContent = "—";
    renderHomeActions(false);
    if (elements.homeCta) {
      elements.homeCta.classList.remove("is-hidden");
    }
    renderSparkline([]);
    renderHomeActivity([]);
    applyWalletRestrictions();
    return;
  }

  elements.homeWalletName.textContent = state.walletSummary.name;
  const watchOnlyTag = state.walletSummary.watchOnly ? " · Watch-only" : "";
  elements.homeWalletMeta.textContent = `${state.walletSummary.policyType} · ${state.walletSummary.scriptType}${watchOnlyTag}`;
  elements.homeBalance.textContent = formatBtcFromSats(state.balanceSats, 8);
  const fiatBalance = formatUsdFromSats(state.balanceSats, state.priceUsd);
  elements.homeBalanceFiat.textContent =
    fiatBalance === "—" ? formatSats(state.balanceSats) : fiatBalance;
  elements.networkPill.textContent = state.walletSummary.network || "Mainnet";
  renderHomeActions(true);
  if (elements.homeCta) {
    elements.homeCta.classList.add("is-hidden");
  }
  renderSparkline(state.balanceHistory);
  renderHomeActivity(state.transactions.slice(0, 3));
  applyWalletRestrictions();
}

function renderWalletContext() {
  if (!elements.sendContext || !elements.receiveContext) return;
  if (!state.walletSummary) {
    elements.sendContext.textContent = "Choose a wallet to start.";
    elements.receiveContext.textContent = "Choose a wallet to start.";
    return;
  }
  const walletName = state.walletSummary.name || "this wallet";
  if (state.walletSummary.watchOnly) {
    elements.sendContext.textContent = "Watch-only wallet. Sending is disabled.";
  } else {
    elements.sendContext.textContent = `Sending from ${walletName}.`;
  }
  elements.receiveContext.textContent = `Receiving into ${walletName}.`;
}

function renderHomeActions(hasWallet) {
  const primary = document.getElementById("home-actions");
  const setup = document.getElementById("home-setup-actions");
  const openButton = document.getElementById("home-setup-open");
  const hasWallets = state.wallets.length > 0;
  if (openButton) {
    openButton.classList.toggle("is-hidden", !hasWallets);
  }
  if (elements.homeCtaOpen) {
    elements.homeCtaOpen.classList.toggle("is-hidden", !hasWallets);
  }
  if (hasWallet) {
    primary.classList.remove("is-hidden");
    setup.classList.add("is-hidden");
  } else {
    primary.classList.add("is-hidden");
    setup.classList.remove("is-hidden");
  }
}

function applyWalletRestrictions() {
  const watchOnly = Boolean(state.walletSummary?.watchOnly);
  const homeSendButton = document.querySelector("#home-actions [data-view-target='send']");
  if (homeSendButton) {
    homeSendButton.disabled = watchOnly;
    homeSendButton.classList.toggle("is-disabled", watchOnly);
  }
  if (elements.sendForm) {
    const controls = elements.sendForm.querySelectorAll("input, select, textarea, button");
    controls.forEach((control) => {
      control.disabled = watchOnly;
    });
    elements.sendForm.classList.toggle("is-disabled", watchOnly);
  }
  if (elements.confirmSend) {
    elements.confirmSend.disabled = watchOnly;
    elements.confirmSend.classList.toggle("is-disabled", watchOnly);
  }
}

function buildSmoothPath(points) {
  if (!points || points.length === 0) return "";
  if (points.length === 1) {
    return `M ${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`;
  }
  let d = `M ${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`;
  for (let i = 1; i < points.length - 1; i += 1) {
    const current = points[i];
    const next = points[i + 1];
    const xc = (current.x + next.x) / 2;
    const yc = (current.y + next.y) / 2;
    d += ` Q ${current.x.toFixed(2)},${current.y.toFixed(2)} ${xc.toFixed(2)},${yc.toFixed(2)}`;
  }
  const last = points[points.length - 1];
  d += ` T ${last.x.toFixed(2)},${last.y.toFixed(2)}`;
  return d;
}

function renderSparkline(history) {
  if (!history || history.length < 2) {
    elements.sparklineLine.setAttribute("d", "");
    elements.sparklineArea.setAttribute("d", "");
    if (elements.sparklineGlow) {
      elements.sparklineGlow.setAttribute("d", "");
    }
    if (elements.sparklineDot) {
      elements.sparklineDot.setAttribute("r", "0");
    }
    elements.homeTrend.textContent = "No history yet";
    elements.homeTrend.classList.remove("negative");
    if (elements.balanceHigh) elements.balanceHigh.textContent = "—";
    if (elements.balanceLow) elements.balanceLow.textContent = "—";
    if (elements.balanceRange) elements.balanceRange.textContent = "—";
    return;
  }
  const points = history.map((point, index) => ({
    x: index,
    y: point.balanceSats,
  }));
  const minY = Math.min(...points.map((p) => p.y));
  const maxY = Math.max(...points.map((p) => p.y));
  const range = maxY - minY || 1;
  const width = 100;
  const height = 60;
  const padding = 6;

  const scaledPoints = points.map((p) => {
    const x = (p.x / (points.length - 1)) * width;
    const y = height - padding - ((p.y - minY) / range) * (height - padding * 2);
    return { x, y };
  });

  const linePath = buildSmoothPath(scaledPoints);
  const baseY = height - padding;
  const areaPath = `${linePath} L ${width.toFixed(2)},${baseY.toFixed(2)} L 0,${baseY.toFixed(2)} Z`;
  elements.sparklineLine.setAttribute("d", linePath);
  elements.sparklineArea.setAttribute("d", areaPath);
  if (elements.sparklineGlow) {
    elements.sparklineGlow.setAttribute("d", linePath);
  }
  if (elements.sparklineDot) {
    const lastPoint = scaledPoints[scaledPoints.length - 1];
    elements.sparklineDot.setAttribute("cx", lastPoint.x.toFixed(2));
    elements.sparklineDot.setAttribute("cy", lastPoint.y.toFixed(2));
    elements.sparklineDot.setAttribute("r", "2.4");
  }

  if (elements.balanceHigh) elements.balanceHigh.textContent = formatBtcFromSats(maxY, 6);
  if (elements.balanceLow) elements.balanceLow.textContent = formatBtcFromSats(minY, 6);
  if (elements.balanceRange) {
    elements.balanceRange.textContent = `Range ${formatBtcFromSats(minY, 6)} -> ${formatBtcFromSats(maxY, 6)}`;
  }

  const first = history[0].balanceSats;
  const last = history[history.length - 1].balanceSats;
  const trend = last - first;
  const trendText = trend >= 0 ? `Up ${formatBtcFromSats(trend, 6)}` : `Down ${formatBtcFromSats(Math.abs(trend), 6)}`;
  elements.homeTrend.textContent = trendText;
  elements.homeTrend.classList.toggle("negative", trend < 0);
}

function renderHomeActivity(transactions) {
  elements.homeActivityList.innerHTML = "";
  if (!transactions || transactions.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "No activity yet.";
    elements.homeActivityList.appendChild(empty);
    return;
  }
  const fragment = document.createDocumentFragment();
  transactions.forEach((tx) => {
    const item = document.createElement("div");
    item.className = "activity-item";

    const row = document.createElement("div");
    row.className = "activity-row";

    const meta = document.createElement("div");
    const title = document.createElement("p");
    const directionLabel = tx.valueSats >= 0 ? "Incoming" : "Outgoing";
    title.textContent = tx.label || directionLabel;
    const sub = document.createElement("p");
    sub.className = "muted";
    const confirmations = Number.isFinite(tx.confirmations) ? tx.confirmations : 0;
    const status = confirmations > 0 ? `${confirmations} conf` : "Pending";
    const time = formatTime(tx.timestamp);
    const dateText = time ? `${formatDate(tx.timestamp)} · ${time}` : formatDate(tx.timestamp);
    sub.textContent = `${dateText} · ${status}`;
    meta.append(title, sub);

    const amount = document.createElement("p");
    amount.className = tx.valueSats >= 0 ? "activity-amount in" : "activity-amount out";
    amount.textContent = formatBtcFromSats(Math.abs(tx.valueSats), 6);

    row.append(meta, amount);
    item.appendChild(row);
    fragment.appendChild(item);
  });
  elements.homeActivityList.appendChild(fragment);
}

function renderTransactions() {
  const filtered = state.transactions.filter((tx) => {
    if (state.txFilter === "in") return tx.valueSats > 0;
    if (state.txFilter === "out") return tx.valueSats < 0;
    return true;
  });

  elements.transactionCount.textContent = `${filtered.length} transactions`;
  const transactionFiat = formatUsdFromSats(state.balanceSats, state.priceUsd);
  elements.transactionBalance.textContent =
    transactionFiat === "—"
      ? formatBtcFromSats(state.balanceSats, 6)
      : `${formatBtcFromSats(state.balanceSats, 6)} · ${transactionFiat}`;

  elements.transactionsList.innerHTML = "";
  if (filtered.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "No transactions yet.";
    elements.transactionsList.appendChild(empty);
    return;
  }

  const fragment = document.createDocumentFragment();
  filtered.forEach((tx) => {
    const item = document.createElement("div");
    item.className = "transaction";

    const row = document.createElement("div");
    row.className = "transaction-row";

    const meta = document.createElement("div");
    const title = document.createElement("p");
    const directionLabel = tx.valueSats >= 0 ? "Incoming" : "Outgoing";
    title.textContent = tx.label || directionLabel;
    const sub = document.createElement("p");
    sub.className = "muted";
    const confirmations = Number.isFinite(tx.confirmations) ? tx.confirmations : 0;
    const status = confirmations > 0 ? `${confirmations} conf` : "Pending";
    const time = formatTime(tx.timestamp);
    const dateText = time ? `${formatDate(tx.timestamp)} · ${time}` : formatDate(tx.timestamp);
    sub.textContent = `${dateText} · ${status}`;
    meta.append(title, sub);

    const amount = document.createElement("p");
    amount.className = tx.valueSats >= 0 ? "transaction-amount in" : "transaction-amount out";
    amount.textContent = formatBtcFromSats(Math.abs(tx.valueSats), 6);

    row.append(meta, amount);
    item.appendChild(row);
    fragment.appendChild(item);
  });
  elements.transactionsList.appendChild(fragment);
}

function renderSendReview() {
  if (!state.pendingSend) {
    elements.sendReview.innerHTML = "";
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "Fill the form to preview your send.";
    elements.sendReview.appendChild(empty);
    return;
  }

  elements.sendReview.innerHTML = "";
  const wrap = document.createElement("div");
  wrap.className = "review-card";

  const address = document.createElement("p");
  address.className = "mono";
  address.textContent = state.pendingSend.address;

  const amount = document.createElement("p");
  amount.textContent = formatBtcFromSats(state.pendingSend.amountSats, 6);

  const fee = document.createElement("p");
  fee.className = "muted";
  fee.textContent = `Fee rate: ${state.pendingSend.feeRate} sat/vB`;

  wrap.append(address, amount, fee);
  elements.sendReview.appendChild(wrap);
}

function renderReceive() {
  if (!state.pendingReceive) {
    elements.receiveAddress.textContent = "—";
    elements.receiveTotal.textContent = "0.0000 BTC";
    elements.receiveStatus.textContent = "Waiting";
    return;
  }
  elements.receiveAddress.textContent = state.pendingReceive.address;
  if (state.pendingReceive.amountBtc) {
    elements.receiveTotal.textContent = `${state.pendingReceive.amountBtc.toFixed(6)} BTC`;
  } else {
    elements.receiveTotal.textContent = "0.0000 BTC";
  }
  elements.receiveStatus.textContent = "Waiting";
}

function renderElectrum() {
  const status = state.electrum.status;
  if (!status) {
    elements.electrumStatusDetail.textContent = "Disconnected";
    elements.headerElectrum.textContent = "Disconnected";
    elements.electrumDetail.textContent = "No server configured yet.";
  } else if (status.connected) {
    elements.electrumStatusDetail.textContent = "Connected";
    elements.headerElectrum.textContent = "Connected";
    const server = status.serverVersion?.[0] ? `Server ${status.serverVersion[0]}` : "Private Electrum";
    elements.electrumDetail.textContent = server;
  } else {
    elements.electrumStatusDetail.textContent = "Disconnected";
    elements.headerElectrum.textContent = "Disconnected";
    elements.electrumDetail.textContent = status.error || "No server configured yet.";
  }

  if (status?.tipHeight !== null && status?.tipHeight !== undefined) {
    elements.electrumTip.textContent = status.tipHeight.toString();
  } else {
    elements.electrumTip.textContent = "--";
  }

  if (state.electrum.lastChecked) {
    const date = new Date(state.electrum.lastChecked);
    elements.electrumCheck.textContent = date.toLocaleTimeString(undefined, {
      hour: "2-digit",
      minute: "2-digit",
    });
  } else {
    elements.electrumCheck.textContent = "--";
  }
}

function renderMnemonicPanel() {
  if (!elements.mnemonicPanel || !elements.createdMnemonic) return;
  if (!state.lastMnemonic) {
    elements.mnemonicPanel.classList.add("is-hidden");
    elements.createdMnemonic.textContent = "—";
    return;
  }
  elements.createdMnemonic.textContent = state.lastMnemonic;
  elements.mnemonicPanel.classList.remove("is-hidden");
}

function updateGenerateToggle() {
  const generate = elements.generateCheckbox?.checked ?? true;
  if (elements.entropyRow) {
    elements.entropyRow.classList.toggle("is-hidden", !generate);
  }
  if (elements.mnemonicRow) {
    elements.mnemonicRow.classList.toggle("is-hidden", generate);
  }
}

function openSettingsDisclosure(target) {
  if (!target) return;
  if (target === "create") {
    elements.createDisclosure.open = true;
  }
  if (target === "import") {
    if (elements.importDisclosure) {
      elements.importDisclosure.open = true;
    }
  }
  if (target === "open") {
    elements.openDisclosure.open = true;
  }
  if (target === "electrum") {
    const electrumDisclosure = document.getElementById("electrum-disclosure");
    if (electrumDisclosure) {
      electrumDisclosure.open = true;
    }
  }
}

function handleViewButtons() {
  elements.navButtons.forEach((button) => {
    button.addEventListener("click", () => {
      if (!button.dataset.viewTarget) return;
      const targetView = button.dataset.viewTarget;
      if (state.pendingSeedWallet && button.dataset.viewTarget !== "settings") {
        showToast("Confirm your seed phrase before leaving Settings.");
        return;
      }
      if (state.wallets.length === 0 && (targetView === "send" || targetView === "receive")) {
        setFtueDismissed(false);
        state.ftueStep = 3;
        renderFtue();
        showToast("Finish setup to continue.");
        return;
      }
      setView(targetView);
      const disclosureTarget = button.dataset.disclosureTarget;
      if (disclosureTarget) {
        openSettingsDisclosure(disclosureTarget);
      }
    });
  });
}

function attachHandlers() {
  handleViewButtons();

  document.querySelectorAll(".chip").forEach((chip) => {
    chip.addEventListener("click", () => {
      state.txFilter = chip.dataset.filter;
      document.querySelectorAll(".chip").forEach((btn) => btn.classList.remove("is-active"));
      chip.classList.add("is-active");
      renderTransactions();
    });
  });

  elements.createWalletForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const data = new FormData(elements.createWalletForm);
      const name = data.get("name").trim();
      const scriptType = data.get("script");
      const generate = data.get("generate") === "on";
      const entropyBits = parseInt(data.get("entropy"), 10) || 128;
      const mnemonic = generate ? null : data.get("mnemonic").trim();
      const passphrase = data.get("passphrase").trim();

      const response = await apiFetch("/api/wallets/create", {
        method: "POST",
        body: JSON.stringify({
          name,
          policyType: "SINGLE",
          scriptType,
          generate,
          entropyBits,
          mnemonic,
          passphrase: passphrase || null,
        }),
      });

      if (response.mnemonic) {
        state.lastMnemonic = response.mnemonic;
        state.pendingSeedWallet = name;
        renderMnemonicPanel();
        elements.createDisclosure.open = true;
        showToast("Wallet created. Save your seed phrase to continue.");
      } else {
        state.lastMnemonic = null;
        state.pendingSeedWallet = null;
        renderMnemonicPanel();
        showToast("Wallet created.");
      }

      elements.createWalletForm.reset();
      await refreshWalletList();
      await openWallet(name);
      if (!state.pendingSeedWallet) {
        setView("home");
      } else {
        setView("settings");
      }
    } catch (error) {
      showToast(error.message || "Unable to create wallet");
    }
  });

  if (elements.generateCheckbox) {
    elements.generateCheckbox.addEventListener("change", updateGenerateToggle);
    updateGenerateToggle();
  }

  if (elements.importWalletForm) {
    elements.importWalletForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      try {
        const data = new FormData(elements.importWalletForm);
        const name = data.get("name").trim();
        const xpub = data.get("xpub").trim();
        const scriptType = data.get("script");
        const derivationPath = data.get("derivation").trim();

        await apiFetch("/api/wallets/create", {
          method: "POST",
          body: JSON.stringify({
            name,
            policyType: "SINGLE",
            scriptType,
            xpub,
            derivationPath: derivationPath || null,
          }),
        });

        state.lastMnemonic = null;
        state.pendingSeedWallet = null;
        renderMnemonicPanel();
        elements.importWalletForm.reset();
        await refreshWalletList();
        await openWallet(name);
        setView("home");
        showToast("Watch-only wallet imported.");
      } catch (error) {
        showToast(error.message || "Unable to import wallet");
      }
    });
  }

  elements.openWalletForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const data = new FormData(elements.openWalletForm);
      const name = data.get("wallet");
      if (!name) {
        showToast("Select a wallet");
        return;
      }
      await openWallet(name);
      setView("home");
      showToast("Wallet opened");
    } catch (error) {
      showToast(error.message || "Unable to open wallet");
    }
  });

  elements.receiveForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state.activeWallet) {
      showToast("Select a wallet first");
      return;
    }
    try {
      const data = new FormData(elements.receiveForm);
      const amountBtc = parseFloat(data.get("amount")) || 0;
      const label = data.get("label").trim();
      await requestReceive(label);
      state.pendingReceive.amountBtc = amountBtc || null;
      renderReceive();
      elements.receiveForm.reset();
      showToast("Address generated");
    } catch (error) {
      showToast(error.message || "Unable to generate address");
    }
  });

  elements.copyAddress.addEventListener("click", async () => {
    if (!state.pendingReceive?.address) {
      showToast("No address to copy");
      return;
    }
    try {
      await navigator.clipboard.writeText(state.pendingReceive.address);
      showToast("Address copied");
    } catch (_) {
      showToast("Unable to copy");
    }
  });

  elements.newAddress.addEventListener("click", async () => {
    if (!state.activeWallet) return;
    try {
      await requestReceive("");
      showToast("New address created");
    } catch (error) {
      showToast(error.message || "Unable to generate address");
    }
  });

  elements.markReceived.addEventListener("click", () => {
    showToast("Waiting for confirmations");
  });

  elements.sendForm.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!state.activeWallet) {
      showToast("Select a wallet first");
      return;
    }
    if (state.walletSummary?.watchOnly) {
      showToast("Watch-only wallet cannot send.");
      return;
    }
    const data = new FormData(elements.sendForm);
    const address = data.get("address").trim();
    const amountBtc = parseFloat(data.get("amount"));
    const feeRate = parseFloat(data.get("fee")) || 8;
    const note = data.get("note").trim();
    if (!address || !amountBtc) {
      showToast("Enter recipient and amount");
      return;
    }
    const amountSats = Math.round(amountBtc * 100_000_000);
    state.pendingSend = { address, amountSats, feeRate, note };
    renderSendReview();
  });

  elements.confirmSend.addEventListener("click", async () => {
    if (!state.pendingSend) {
      showToast("Compose a send first");
      return;
    }
    try {
      await sendPayment();
    } catch (error) {
      showToast(error.message || "Unable to send");
    }
  });

  if (elements.copyMnemonic) {
    elements.copyMnemonic.addEventListener("click", async () => {
      if (!state.lastMnemonic) return;
      try {
        await navigator.clipboard.writeText(state.lastMnemonic);
        showToast("Seed phrase copied");
      } catch (_) {
        showToast("Unable to copy seed phrase");
      }
    });
  }

  if (elements.clearMnemonic) {
    elements.clearMnemonic.addEventListener("click", () => {
      const hadPending = Boolean(state.pendingSeedWallet);
      state.lastMnemonic = null;
      state.pendingSeedWallet = null;
      renderMnemonicPanel();
      elements.createDisclosure.open = false;
      showToast("Seed phrase cleared");
      if (hadPending) {
        setView("home");
      }
    });
  }

  if (elements.electrumPreset) {
    elements.electrumPreset.addEventListener("change", () => {
      const nextPreset = elements.electrumPreset.value;
      if (state.electrum.preset === "custom") {
        state.electrum.customDraft = getElectrumFormValues();
      }
      state.electrum.preset = nextPreset;
      if (nextPreset === "custom") {
        if (!state.electrum.customDraft) {
          state.electrum.customDraft = {
            host: "",
            port: "",
            ssl: "true",
            certificatePath: "",
            useProxy: false,
            proxyServer: "",
          };
        }
      }
      applyElectrumPreset(nextPreset);
    });
  }

  if (elements.electrumForm) {
    elements.electrumForm.addEventListener("input", (event) => {
      if (event?.target?.name === "host") {
        const hostValue = event.target.value || "";
        if (isOnionHost(hostValue) && elements.electrumConnection?.value !== "tor") {
          elements.electrumConnection.value = "tor";
          updateElectrumProxyVisibility("tor");
        }
      }
      if (elements.electrumPreset?.value === "custom") {
        state.electrum.customDraft = getElectrumFormValues();
      }
    });
  }

  if (elements.electrumConnection) {
    elements.electrumConnection.addEventListener("change", () => {
      updateElectrumProxyVisibility(elements.electrumConnection.value);
      if (elements.electrumPreset?.value === "custom") {
        state.electrum.customDraft = getElectrumFormValues();
      }
    });
  }

  elements.electrumForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const data = new FormData(elements.electrumForm);
      const host = data.get("host").trim();
      const port = parseInt(data.get("port"), 10) || null;
      const ssl = data.get("ssl") === "true";
      const certificatePath = data.get("certificate").trim();
      const connection = data.get("connection") || "direct";
      const useProxy = connection === "tor";
      const proxyServer = (data.get("proxy") || "").trim();
      if (useProxy && !proxyServer) {
        showToast("Tor proxy is required");
        return;
      }
      await apiFetch("/api/electrum", {
        method: "POST",
        body: JSON.stringify({
          host,
          port,
          ssl,
          certificatePath: certificatePath || null,
          useProxy,
          proxyServer: proxyServer || null,
        }),
      });
      await refreshElectrum();
      showToast("Electrum settings saved");
    } catch (error) {
      showToast(error.message || "Unable to update Electrum");
    }
  });

  elements.authSetupForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(elements.authSetupForm);
    const password = data.get("password");
    const confirm = data.get("confirm");
    try {
      await setupPassword(password, confirm);
      elements.authSetupForm.reset();
      showToast("Password set. Please log in.");
    } catch (error) {
      showToast(error.message || "Unable to set password");
    }
  });

  elements.authLoginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(elements.authLoginForm);
    const password = data.get("password");
    try {
      await login(password);
      elements.authLoginForm.reset();
      showToast("Welcome back");
    } catch (error) {
      showToast(error.message || "Unable to log in");
    }
  });

  elements.authLogout.addEventListener("click", async () => {
    try {
      await logout();
    } catch (error) {
      showToast(error.message || "Unable to lock app");
    }
  });

  if (elements.ftueNext) {
    elements.ftueNext.addEventListener("click", () => {
      state.ftueStep += 1;
      renderFtue();
    });
  }
  if (elements.ftueBack) {
    elements.ftueBack.addEventListener("click", () => {
      state.ftueStep -= 1;
      renderFtue();
    });
  }
  if (elements.ftueSkip) {
    elements.ftueSkip.addEventListener("click", () => {
      setFtueDismissed(true);
      renderFtue();
      renderFtueCallout();
    });
  }
  if (elements.ftueConfigure) {
    elements.ftueConfigure.addEventListener("click", () => {
      setFtueDismissed(true);
      setView("settings");
      openSettingsDisclosure("electrum");
      renderFtue();
      renderFtueCallout();
    });
  }
  if (elements.ftueCreate) {
    elements.ftueCreate.addEventListener("click", () => {
      setFtueDismissed(true);
      setView("settings");
      openSettingsDisclosure("create");
      renderFtue();
      renderFtueCallout();
    });
  }
  if (elements.ftueImport) {
    elements.ftueImport.addEventListener("click", () => {
      setFtueDismissed(true);
      setView("settings");
      openSettingsDisclosure("import");
      renderFtue();
      renderFtueCallout();
    });
  }
  if (elements.ftueFinish) {
    elements.ftueFinish.addEventListener("click", () => {
      setFtueDismissed(false);
      state.ftueStep = 1;
      renderFtue();
      renderFtueCallout();
    });
  }
}

async function init() {
  attachHandlers();
  setView(state.view);
  try {
    await loadAuthStatus();
    if (state.auth.authenticated) {
      await bootstrap();
    }
  } catch (error) {
    showToast(error.message || "Unable to reach adapter");
  }
}

const __juncoExports = {
  state,
  elements,
  normalizeView,
  setView,
  showToast,
  formatBtcFromSats,
  formatUsdFromSats,
  formatSats,
  formatDate,
  formatTime,
  normalizeHost,
  isOnionHost,
  resolveElectrumConnection,
  updateElectrumProxyVisibility,
  getPresetForConfig,
  getElectrumFormValues,
  setElectrumFormValues,
  applyElectrumPreset,
  syncElectrumForm,
  apiFetch,
  showAuthOverlay,
  setFtueDismissed,
  shouldShowFtue,
  shouldShowFtueCallout,
  renderFtue,
  renderFtueCallout,
  loadAuthStatus,
  login,
  setupPassword,
  logout,
  bootstrap,
  refreshWalletList,
  openWallet,
  refreshWalletData,
  refreshPrice,
  requestReceive,
  sendPayment,
  refreshElectrum,
  render,
  renderWalletList,
  renderHome,
  renderWalletContext,
  renderHomeActions,
  applyWalletRestrictions,
  buildSmoothPath,
  renderSparkline,
  renderHomeActivity,
  renderTransactions,
  renderSendReview,
  renderReceive,
  renderElectrum,
  renderMnemonicPanel,
  updateGenerateToggle,
  openSettingsDisclosure,
  handleViewButtons,
  attachHandlers,
  init,
};

if (typeof window !== "undefined") {
  window.__junco = __juncoExports;
  window.__juncoState = state;
  window.__juncoElements = elements;
  if (!window.__JUNCO_TEST__) {
    init();
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = __juncoExports;
}
