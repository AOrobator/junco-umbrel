# Junco-Umbrel Project Scratchpad

## Mission
Mobile-first web UI for Sparrow Server running on Umbrel. Connect to your local Bitcoin and Electrs services, manage wallets, and transact privately from your phone.

## Current Setup (2026-02-07)

### Junco on Umbrel
- **App ID:** `junco-umbrel`
- **Port:** 3009 (via Umbrel app proxy)
- **Containers:** web (nginx), adapter (Ktor/Kotlin), server (Sparrow Server 2.3.1), app_proxy, tor_server
- **Images:** Built natively on Umbrel x86_64, pushed to local registry (`localhost:5555`)
- **Host:** `umbrel@umbrel.local`

### Architecture
```
Browser -> app_proxy (:3009) -> web (nginx :8080)
                                  ├── static files (/)
                                  └── /api/ proxy -> adapter (:8081) -> server (Sparrow :18080)
```

### Dependencies on Umbrel
- Bitcoin (bitcoin-knots)
- Electrs

## Known Issues / Workarounds

### Umbrel ignores docker-compose options
Umbrel's compose system does NOT apply: `tty`, `stdin_open`, `security_opt`.
- **TTY fix:** Server entrypoint uses `script -qc` to create a pseudo-TTY (Sparrow's lanterna terminal library needs `/dev/tty`)
- **AppArmor fix:** Server entrypoint uses `/tmp/sparrow-data` as working directory with symlinks to persistent `/data` volume. Docker's `docker-default` AppArmor profile blocks Unix domain socket `bind()` on bind-mounted volumes. The lock file (`sparrow.lock`) is created on the overlay filesystem instead.

### Nginx upstream resolution
Nginx resolves `proxy_pass` hostnames at config load time. If the adapter container isn't ready, nginx exits with `host not found in upstream "adapter"`. Fix: use Docker's embedded DNS resolver with a variable so resolution happens at request time:
```nginx
resolver 127.0.0.11 valid=10s;
set $adapter_upstream http://adapter:8081;
proxy_pass $adapter_upstream;
```

### Deployment
- Uses SSH + rsync to build natively on Umbrel (no cross-compile needed, Umbrel is x86_64)
- Local Docker registry on port 5555 (shared with alby-hub)
- App store path: `/home/umbrel/umbrel/app-stores/getumbrel-umbrel-apps-github-53f74447/junco-umbrel`
- Deploy script: `./scripts/deploy-umbrel.sh`

## Session Log

### Session 1 — Initial Umbrel Deployment (2026-02-07)
- Built all 3 Docker images on Umbrel (server, adapter, web)
- Discovered Umbrel compose was missing adapter service (web nginx proxies /api/ to adapter)
- Hit two blockers with Sparrow Server:
  1. AppArmor blocks Unix domain socket binding on volume mounts → fixed with tmpfs data dir workaround
  2. Umbrel ignores `tty: true` → fixed with `script -qc` pseudo-TTY wrapper
- Successfully deployed and verified all 5 containers running
- Created `deploy-umbrel.sh` deploy script following alby-hub pattern

### Session 2 — UI Fixes & Nginx Crash Fix (2026-02-07)
- Fixed FTUE dialog: removed Sparrow-specific copy, simplified title, removed back button
- Fixed settings page mobile layout: single-column grids at 720px breakpoint
- Fixed nginx crash on restart: adapter upstream resolved at startup → switched to dynamic DNS resolution via Docker's embedded resolver (127.0.0.11)
- Redeployed and verified all 5 containers stable

### Session 3 — Settings Mobile Polish (2026-02-07)
- Fixed settings page horizontal scroll: cards get `overflow: hidden` + `min-width: 0`, inputs get `width: 100%`
- Create wallet: stacked entropy/passphrase vertically (eliminated overlap)
- Create wallet: hide mnemonic input when "Generate a new seed phrase" is checked; hide entropy when unchecked
- Import wallet: stacked script/derivation fields vertically
- Hide "Open existing wallet" disclosure when no wallets exist
- Electrum card: fixed content floating off card with overflow + word-break on status values
- FTUE dialog: swapped button order (Skip left, Next right)
- Fixed unit tests: replaced DOM write with DOMParser for jsdom/Node 20 compatibility
- Fixed nvm lazy-load in `.zshrc` (replaced broken function wrappers with direct PATH setup)

### Session 4 — Test Connection UX & Toast Fix (2026-02-07)
- Fixed toast hidden behind mobile nav bar: added `z-index: 20` and repositioned to `bottom: 80px` on mobile (above the nav pill)
- Fixed "Test connection" button appearing to do nothing:
  - Button now shows "Testing..." with disabled state while the API call is in progress
  - After save, disclosure auto-closes so user sees the updated connection status
  - Toast message is contextual: "Connected to Electrum server" or "Settings saved — server not reachable"
- Added `electrumDisclosure` to elements object (was a stray local variable)
- Verified on Umbrel: Port/SSL fields stack vertically on mobile (720px CSS override works)
- Reset auth after password was lost from previous session

### Session 5 — Electrum Presets, Tor Proxy, FTUE Skip, Import Wallet (2026-02-07)
- Added Local Electrs (`electrs_electrs_1:50001`, no SSL) as default Electrum preset
- Public servers (mempool.space, mempool.guide) now route through Umbrel's shared Tor SOCKS proxy (`10.21.21.11:9050`) — port 50002 blocked on local network
- Fixed Tor proxy hostname: `tor` resolved to per-app hidden service containers, `tor_proxy` not resolvable by Java in container — used raw IP `10.21.21.11`
- FTUE "Finish setup" now skips to step 3 (wallet creation) when Electrum is already connected
- Removed settings subtitle ("Advanced controls live here...")
- Import wallet form now supports both seed phrase (full access) and xpub (watch-only) imports
- Renamed all "Import watch-only" labels to "Import wallet"

### Session 6 — Trezor-Inspired Homepage Refactor (2026-02-07)
- Removed Activity page and its nav buttons (desktop + mobile) — transactions now live on the homepage
- Merged Send/Receive buttons into the balance card; removed the separate Actions card
- Replaced sparkline chart with proper interactive chart: y-axis labels (4 ticks), horizontal grid lines, larger SVG viewBox (600x200), hover tooltip showing date/BTC/fiat, hover vertical line + dot indicator
- Full transaction list on homepage grouped by date, with direction badges (arrow up/down), time, confirmations pill, BTC + fiat amounts
- Added filter chips (All / Incoming / Outgoing) to transaction list on homepage
- Balance card is now full-width (removed 2-column grid for wallet summary)
- Chart card is full-width with y-axis labels on the left side
- Watch-only wallet correctly disables Send button in balance card
- Responsive: y-axis labels shrink on mobile (720px), chart height reduces

## TODO
- [ ] Push images to GHCR for production deployment (currently local registry only)
- [ ] Add health checks to docker-compose
- [ ] Test wallet creation / import flow on Umbrel
- [ ] Push code to GitHub (git credentials issue: `sat-engineer` doesn't have access to `AOrobator/junco-umbrel`)
