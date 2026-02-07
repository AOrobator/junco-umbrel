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

## TODO
- [ ] UI: Fix FTUE dialog copy and remove back button
- [ ] UI: Fix settings page mobile layout (horizontal scroll, overlapping components)
- [ ] Push images to GHCR for production deployment (currently local registry only)
- [ ] Add health checks to docker-compose
- [ ] Test wallet creation / import flow on Umbrel
