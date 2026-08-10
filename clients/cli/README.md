# UnoArena Client CLI (authentication + casual gameplay)

The canonical command surface the faculty uses to drive the cluster (Client-Checkpoint.md). This
repo ships the **authentication slice** (§5.A) and the **casual room/play slice** (§5.B, §5.C);
spectate, bot and tournament commands land with the phases that make them real (P4–P7).

## Commands

### Authentication (§5.A) — against `identity`

| Command | Maps to | Notes |
|---|---|---|
| `register --user U --pass P` | `POST /auth/register` | creates an account, stores the token; `409` if the name is taken |
| `login --user U --pass P` | `POST /auth/login` | new session — **invalidates the previous one** (single-active-session) |
| `whoami` | `GET /auth/whoami` | the authenticated user behind the stored token |
| `logout` | `POST /auth/logout` | kills the session server-side, then clears the session file |
| `seed --count N [--prefix P]` | `POST /auth/register`, falling back to `/auth/login` | ensures N test accounts and prints their credentials + tokens; safe to re-run |

### Rooms and play (§5.B, §5.C) — against `room-gameplay`

| Command | Maps to | Notes |
|---|---|---|
| `room create [--max N]` | `POST /rooms` | sends an `Idempotency-Key`, so a retry returns the same room instead of leaving an abandoned one |
| `room list` | `GET /rooms` | joinable rooms only — waiting, not full, not empty |
| `room join <roomId>` | `POST /rooms/{id}/players/{me}` | joining is asserting a membership; `409` if the room is full or already playing |
| `room leave [<roomId>]` | `DELETE /rooms/{id}/players/{me}` | idempotent — leaving a room you are not in still succeeds |
| `play --casual` | list → join → else create, then poll | "put me into a game"; the backend starts it at `ROOM_MIN_PLAYERS` |
| `play --room <id>` | the same loop on a known room | for when you were given a room id |

Inside `play`, the turn board shows the discard top, the active colour, the direction, the draw
pile, every opponent's **card count** and `UNO!` flag, and your **numbered hand with the playable
cards marked** (`*`). The marking comes from the server's own legality check, so the board can
never disagree with what the engine will accept. Only the actions that are legal right now are
offered — `pass` appears once you have drawn, not before.

| Typed | Effect |
|---|---|
| `play <n>` | play the n-th card in hand |
| `play <n> <R\|G\|B\|Y>` | play a wild and declare the colour (mandatory — a wild without one is refused) |
| `play <n> [colour] uno` | play **and** call Uno! in the same command |
| `draw`, `pass` | draw one, then pass |
| `uno` | call Uno! on its own (only useful while you still hold one or two cards) |
| `challenge` | catch an opponent sitting on one card who did not call |
| `state`, `quit` | re-render the board; leave |

**Calling Uno! is the player's job, not the client's.** `hasCalledUno` resets whenever the hand
size changes, so calling *before* you play is wiped by the play itself and after it the turn has
already moved on — `play <n> uno` is the only way to be safe, which is why the board says so when
you are down to two cards. A CLI that called on your behalf would delete the mechanic.

Cards are printed in the canonical notation of §5.F (`R5`, `G0`, `BSKIP`, `YREV`, `Y+2`, `WILD`,
`WILD+4`). The CLI does not translate: that is the string the backend sent, and the same one in
the game log.

Global `--json` emits one machine-readable line per action (Client §6), always the same shape:
`{ts, action, room, player, latency_ms, result, error_code, seq, correlationId}`. Fields that do
not apply to an action are `null` rather than absent.

## What is deliberately not here yet

- **A real live event feed.** §5.C's feed is derived by diffing consecutive polls of the game
  state, because P3's live view is polling with `If-None-Match` (decision E4). Two things happening
  inside one poll interval collapse into a single line. **P4 replaces the polling loop with SSE**
  over the same endpoint and the feed becomes the actual event stream.
- **`spectate`, `bot`, `tournament`** — P4, P6, P7.
- **Lazy timers have a visible consequence.** P3 has no timer worker: deadlines live in the room
  aggregate and are evaluated when the *next* command arrives (decision E2). A turn that timed out
  while nobody was playing is settled the moment anyone acts — correct, but it can land late. P5's
  timer worker closes that gap; until then, a player who walks away is only penalised once someone
  else moves.
- **Token refresh** and the `session_superseded` stream notice. A superseded session surfaces as a
  `401`; inside `play` that ends the session with `error_code: "session_superseded"`.

## Configuration

- `UNOARENA_API_URL` — the `identity` target (e.g. `http://localhost:30080`). **Never hardcoded.**
- `UNOARENA_ROOMS_URL` — the `room-gameplay` target (e.g. `http://localhost:30081`).
  **Two URLs is a P3-only shape**: there is no gateway yet, so each service owns a NodePort. P4's
  gateway becomes the single entry point and this variable goes away.
- `UNOARENA_SESSION` — optional session-file path (default `~/.unoarena/session.json`). One file per
  session means one process equals one player identity, as the checkpoint requires.
- `UNOARENA_POLL_MS` — poll interval inside `play` (default `1000`).

## Run

```bash
# Native
npm install && npm run build
export UNOARENA_API_URL=http://localhost:30080
export UNOARENA_ROOMS_URL=http://localhost:30081

UNOARENA_SESSION=/tmp/a.json node dist/cli.js register --user alice --pass pw
UNOARENA_SESSION=/tmp/a.json node dist/cli.js play --casual     # terminal 1

UNOARENA_SESSION=/tmp/b.json node dist/cli.js register --user bob --pass pw
UNOARENA_SESSION=/tmp/b.json node dist/cli.js play --casual     # terminal 2 — the game auto-starts

# Docker
docker build -t unoarena-cli .
docker run --rm -e UNOARENA_API_URL=... -e UNOARENA_ROOMS_URL=... unoarena-cli room list --json
```

Two `play --casual` processes started at the same moment would each create a room and then wait for
each other forever, so while waiting they converge on the lowest room id — same rule on both sides,
no coordinator.

## Drills

- `integration-staging:identity` drives the authentication slice through this CLI — register →
  whoami → second login → the first token must now be `401` → logout → `401` again — plus a seed
  run and a re-seed. `scripts/assert-smoke.js` checks the outcomes *and* that every line carries
  the full §6 field set; it exits non-zero on any mismatch.
- `scripts/casual-drill.js <sessionA> <sessionB>` is AC-P3.7: it spawns two real `play --casual`
  processes and types into them, reading the board back out of their own output. It never talks to
  the API itself, so anything the board fails to render is something it cannot play. It exits
  non-zero unless a game reaches a winner, and prints which of wild / draw / uno / challenge the
  run happened to exercise.
