const allowedViews = ["home", "send", "receive", "activity", "settings"];
const viewAliases = {
  overview: "home",
  wallets: "settings",
  transactions: "activity",
  electrum: "settings",
};

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
  txFilter: "all",
  electrum: {
    config: null,
    status: null,
    lastChecked: null,
  },
};

const elements = {
  app: document.querySelector(".app"),
  views: document.querySelectorAll(".view"),
  navButtons: document.querySelectorAll("[data-view-target]"),
  walletList: document.getElementById("wallet-list"),
  createWalletForm: document.getElementById("create-wallet-form"),
  openWalletForm: document.getElementById("open-wallet-form"),
  openWalletSelect: document.getElementById("open-wallet-select"),
  receiveForm: document.getElementById("receive-form"),
  sendForm: document.getElementById("send-form"),
  electrumForm: document.getElementById("electrum-form"),
  transactionsList: document.getElementById("transactions-list"),
  toast: document.getElementById("toast"),
  createDisclosure: document.getElementById("create-wallet-disclosure"),
  openDisclosure: document.getElementById("open-wallet-disclosure"),
  homeActivityList: document.getElementById("home-activity-list"),
  sparklineLine: document.getElementById("balance-sparkline"),
  sparklineArea: document.getElementById("balance-sparkline-area"),
  homeTrend: document.getElementById("home-balance-trend"),
  homeWalletName: document.getElementById("home-wallet-name"),
  homeWalletMeta: document.getElementById("home-wallet-meta"),
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
  if (!state.activeWallet && state.wallets.length > 0) {
    try {
      await openWallet(state.wallets[0]);
    } catch (error) {
      showToast(error.message || "Unable to open wallet");
    }
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
  try {
    const [config, status] = await Promise.all([
      apiFetch("/api/electrum"),
      apiFetch("/api/electrum/status"),
    ]);
    state.electrum.config = config;
    state.electrum.status = status;
    state.electrum.lastChecked = Date.now();
  } catch (error) {
    state.electrum.status = { connected: false, error: error.message };
    state.electrum.lastChecked = Date.now();
  }
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
    elements.homeWalletMeta.textContent = "Create or open a wallet to begin.";
    elements.homeBalance.textContent = "0.00000000 BTC";
    elements.homeBalanceFiat.textContent = "—";
    elements.networkPill.textContent = "—";
    renderHomeActions(false);
    renderSparkline([]);
    renderHomeActivity([]);
    return;
  }

  elements.homeWalletName.textContent = state.walletSummary.name;
  elements.homeWalletMeta.textContent = `${state.walletSummary.policyType} · ${state.walletSummary.scriptType}`;
  elements.homeBalance.textContent = formatBtcFromSats(state.balanceSats, 8);
  const fiatBalance = formatUsdFromSats(state.balanceSats, state.priceUsd);
  elements.homeBalanceFiat.textContent =
    fiatBalance === "—" ? formatSats(state.balanceSats) : fiatBalance;
  elements.networkPill.textContent = state.walletSummary.network || "Mainnet";
  renderHomeActions(true);
  renderSparkline(state.balanceHistory);
  renderHomeActivity(state.transactions.slice(0, 3));
}

function renderWalletContext() {
  if (!elements.sendContext || !elements.receiveContext) return;
  if (!state.walletSummary) {
    elements.sendContext.textContent = "Choose a wallet to start.";
    elements.receiveContext.textContent = "Choose a wallet to start.";
    return;
  }
  const walletName = state.walletSummary.name || "this wallet";
  elements.sendContext.textContent = `Sending from ${walletName}.`;
  elements.receiveContext.textContent = `Receiving into ${walletName}.`;
}

function renderHomeActions(hasWallet) {
  const primary = document.getElementById("home-actions");
  const setup = document.getElementById("home-setup-actions");
  if (hasWallet) {
    primary.classList.remove("is-hidden");
    setup.classList.add("is-hidden");
  } else {
    primary.classList.add("is-hidden");
    setup.classList.remove("is-hidden");
  }
}

function renderSparkline(history) {
  if (!history || history.length < 2) {
    elements.sparklineLine.setAttribute("points", "");
    elements.sparklineArea.setAttribute("d", "");
    elements.homeTrend.textContent = "No history yet";
    elements.homeTrend.classList.remove("negative");
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
  const height = 40;

  const polyPoints = points.map((p) => {
    const x = (p.x / (points.length - 1)) * width;
    const y = height - ((p.y - minY) / range) * height;
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  });

  elements.sparklineLine.setAttribute("points", polyPoints.join(" "));
  const areaPath = `M0,${height} L${polyPoints.join(" ")} L${width},${height} Z`;
  elements.sparklineArea.setAttribute("d", areaPath);

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

function openSettingsDisclosure(target) {
  if (!target) return;
  if (target === "create") {
    elements.createDisclosure.open = true;
  }
  if (target === "open") {
    elements.openDisclosure.open = true;
  }
}

function handleViewButtons() {
  elements.navButtons.forEach((button) => {
    button.addEventListener("click", () => {
      if (!button.dataset.viewTarget) return;
      setView(button.dataset.viewTarget);
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

  elements.electrumForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      const data = new FormData(elements.electrumForm);
      const host = data.get("host").trim();
      const port = parseInt(data.get("port"), 10) || null;
      const ssl = data.get("ssl") === "true";
      const certificatePath = data.get("certificate").trim();
      await apiFetch("/api/electrum", {
        method: "POST",
        body: JSON.stringify({
          host,
          port,
          ssl,
          certificatePath: certificatePath || null,
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

init();
