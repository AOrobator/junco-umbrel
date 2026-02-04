# Junco - Sparrow for Umbrel

Unofficial Umbrel app that packages Sparrow Server with a mobile-first web UI.

Status: scaffold / pre-alpha.

## Goals
- Delightful, mobile-first UX for core wallet flows.
- Secure-by-default operation on Umbrel Home and Raspberry Pi.
- Publish to the official Umbrel App Store.

## Scope (initial)
- Create/open wallet
- Connect to a local node (Umbrel Bitcoin + Electrs)
- Receive / send
- PSBT import/export
- Labels and notes

## Non-goals (initial)
- Pixel-perfect clone of Sparrow Desktop
- All advanced power-user features on day one

## Architecture (planned)
- Sparrow Server runs headless inside a container.
- A lightweight web UI talks to a local API adapter (if needed).
- Umbrel app packaging lives in `umbrel/`.

## Repo layout
- `umbrel/` App packaging for Umbrel (docker-compose, manifest, exports).
- `web/` Web UI (mobile-first).
- `docs/` Product and technical notes.
- `docker/` Local dev Dockerfiles.

## Local run
Prereqs: Docker Desktop

```bash
docker compose up --build -d
```

Open `http://localhost:3009` to see the placeholder web UI.

Note: Sparrow Server runs in headless (terminal) mode today. The web API bridge is not implemented yet.

## Notes
This project is not affiliated with Sparrow Wallet or Umbrel.

## Test Accounts
See `docs/qa/TEST_CREDENTIALS.md` for local auth + QA wallet guidance.

## License
Apache-2.0
