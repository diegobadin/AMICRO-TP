# UnoArena Client CLI (DevOps smoke subset)

The canonical command surface the faculty uses to drive the cluster (Client-Checkpoint.md). This
repo ships the **subset the DevOps staging smoke test needs**; the full game/bot surface lands in
the Client Checkpoint.

## Commands

| Command | Maps to (identity) | Notes |
|---|---|---|
| `register --user U --pass P` | `POST /register` | creates an account, stores the token |
| `login --user U --pass P` | `POST /login` | refreshes the stored token |
| `whoami` | `GET /whoami` | returns the authenticated user from the stored token |
| `logout` | — | clears the session file |

Global `--json` emits one machine-readable line (Client §6): `{ts, action, result, error_code, correlationId, user, userId}`.

## Configuration

- `UNOARENA_API_URL` — backend target (e.g. the staging ingress of `identity`). **Never hardcoded.**
- `UNOARENA_SESSION` — optional session-file path (default `~/.unoarena/session.json`).

## Run

```bash
# Native
npm install && npm run build
UNOARENA_API_URL=http://localhost:8085 node dist/cli.js register --user alice --pass pw --json
UNOARENA_API_URL=http://localhost:8085 node dist/cli.js whoami --json

# Docker
docker build -t unoarena-cli .
docker run --rm -e UNOARENA_API_URL=https://identity.staging.example unoarena-cli whoami --json
```

The staging smoke test (`ci/templates/smoke-cli.yml`) runs `register` then `whoami` and asserts via
`scripts/assert-smoke.js` that the registered user is returned.
