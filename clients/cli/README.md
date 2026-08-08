# UnoArena Client CLI (authentication subset)

The canonical command surface the faculty uses to drive the cluster (Client-Checkpoint.md). This
repo ships the **authentication slice** (§5.A); room, play, spectate, bot and tournament commands
land with the phases that make them real (P3–P7).

## Commands

| Command | Maps to (identity) | Notes |
|---|---|---|
| `register --user U --pass P` | `POST /auth/register` | creates an account, stores the token; `409` if the name is taken |
| `login --user U --pass P` | `POST /auth/login` | new session — **invalidates the previous one** (single-active-session) |
| `whoami` | `GET /auth/whoami` | the authenticated user behind the stored token |
| `logout` | `POST /auth/logout` | kills the session server-side, then clears the session file |
| `seed --count N [--prefix P]` | `POST /auth/register`, falling back to `/auth/login` | ensures N test accounts and prints their credentials + tokens; safe to re-run |

Global `--json` emits one machine-readable line per action (Client §6), always the same shape:
`{ts, action, room, player, latency_ms, result, error_code, seq, correlationId}`. Fields that do
not apply to an authentication action (`room`, `seq`) are `null` rather than absent.

**Not implemented yet, on purpose:** token refresh (identity issues a single short-lived JWT; a
client whose token expires logs in again) and the `session_superseded` stream notice (the CLI holds
no live stream until the SSE tier arrives in P4 — until then a superseded session surfaces as a
`401` on the next command, which is what the drills assert).

## Configuration

- `UNOARENA_API_URL` — backend target (e.g. the staging NodePort of `identity`). **Never hardcoded.**
- `UNOARENA_SESSION` — optional session-file path (default `~/.unoarena/session.json`). One file per
  session means one process equals one player identity, as the checkpoint requires.

## Run

```bash
# Native
npm install && npm run build
UNOARENA_API_URL=http://localhost:30080 node dist/cli.js register --user alice --pass pw --json
UNOARENA_API_URL=http://localhost:30080 node dist/cli.js whoami --json

# Docker
docker build -t unoarena-cli .
docker run --rm -e UNOARENA_API_URL=https://identity.staging.example unoarena-cli whoami --json

# N test accounts for a load run
UNOARENA_API_URL=http://localhost:30080 node dist/cli.js seed --count 50 --prefix bot --json
```

## Smoke test

`integration-staging:identity` drives the whole slice through this CLI — register → whoami →
second login → the first token must now be `401` → logout → `401` again — plus a seed run and a
re-seed. `scripts/assert-smoke.js` checks the outcomes *and* that every line carries the full §6
field set; it exits non-zero on any mismatch.
