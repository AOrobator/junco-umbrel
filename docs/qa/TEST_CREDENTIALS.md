# Test Accounts & Credentials

> **Security note:** Never commit real secrets. Use local `.env` files or a team password manager.
> Only use test wallets and test credentials for local QA.

## Local Auth (Web UI)

The web UI is protected by a local password that you set on first run.

- **Env var used by tests:** `JUNCO_PASSWORD`
- **Base URL for tests:** `JUNCO_BASE_URL` (default: `http://localhost:3009`)

Example:

```bash
export JUNCO_PASSWORD="your-local-dev-password"
export JUNCO_BASE_URL="http://localhost:3009"
```

## Test Wallets

There are no shared test wallets committed to this repo.
For QA:

- **E2E flow:** Creates a wallet named `JuncoTest-<timestamp>`.
- **Manual QA:** Create a wallet in the UI (recommended) or import a seed from your team’s secure store.

If you use a shared seed for deterministic testing, keep it in your local `.env` or password manager and **do not** add it to git.

## Network Context

By default the adapter runs on mainnet. For non-production testing, use:

```bash
export JUNCO_NETWORK="testnet"
```

This keeps test flows isolated from real funds.
