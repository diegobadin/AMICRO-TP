# Requirements — P3: Uno Engine + Room-Gameplay Core

> Third phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). P1 made
> the cluster boring, P2 made identity real; P3 is the phase the whole program is de-risked
> around — **R2, the Uno rules engine under concurrency**, plus the event-sourced core every
> later phase consumes.

## Objective

A player registered through the CLI can create or join a room and **play a complete casual game of
Uno to the end**, against a cluster deployed from empty: every accepted move is durably appended to
the immutable game log *before* anyone sees it, stale moves are rejected with `412`, illegal ones
with `409`, and the rules — including the Uno! call, its challenge window and the deck recycle —
are enforced by a pure engine that property tests and log replay can prove.

## Locked decisions (session 2026-08-08)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | Authentication without a gateway | room-gameplay validates the JWT locally (same secret as identity) **and** consumes `identity.session-events` to disconnect superseded sessions — exactly what Architecture §2.3.3 assigns it. P4's gateway slots in front without changing the contract |
| E2 | Timer-dependent rules before P5 | **Deadlines live in the aggregate and are evaluated when the next command arrives** ("expire what is overdue, then process"). Correct with no scheduler, and P5's timer-worker only adds the push — no throwaway code |
| E3 | `play --casual` | The **CLI absorbs it** (list → join first with room → else create) and the backend **auto-starts at `minPlayers`** (configurable, default 2). Recorded as a delta against §2.3.1's host-initiated start |
| E4 | Live view before SSE | **Polling `GET …/games/{gid}` with `If-None-Match` → `304`**. The endpoint and its `ETag` are already in the architecture, so P4 replaces the polling loop with SSE and keeps the same endpoint as the reconnect resync |
| E5 | Kotlin stack | **Ktor + kotlinx.serialization + HikariCP over plain JDBC.** No ORM: the append-only SQL is the mechanism, it should be readable |
| E6 | Move serialization | **Optimistic concurrency**: unique index on `(room_id, sequence_number)`. Correct at any replica count with no routing layer; partition affinity stays a scale-chapter optimisation, not a correctness requirement |
| E7 | Event store shape | **Generic `room_events` + `outbox`, no snapshots.** A game is hundreds of events; replay is milliseconds, and a snapshot is a failure mode bought without evidence |
| E8 | CLI reach | Room commands **plus a playable `play`** (turn board, numbered hand, `play <n> [color]`, `draw`, `uno`, `challenge`, `pass`, `state`) fed by polling. The bot and SSE stay in P4 |

## In scope

- **`engine/`** — a pure Kotlin module with no framework dependency: the full casual rule set from
  `docs/design/03-aggregates.md` §3.2.1 (all 14 invariants), including the first-card rule, action
  cards, wild colour declaration, the Uno! call and its 5-second challenge window, and the deck
  recycle with a recorded seed. Property tests and log-replay tests are part of the deliverable,
  not an afterthought.
- **Event-sourced core** — `room_events` + `outbox` written in **one transaction**, the HTTP `2xx`
  sent only after commit (log-before-broadcast, Architecture §2.5), optimistic concurrency, and an
  `Idempotency-Key` mapping for `POST /rooms`.
- **REST per Architecture §2.3.1** — `/rooms`, `/rooms/{id}`, `/rooms/{id}/players/{pid}`,
  `/rooms/{id}/games`, `/rooms/{id}/games/{gid}` (player-scoped, `ETag`),
  `/rooms/{id}/games/{gid}/moves` (`If-Match`).
- **Session integration** — JWT validation and an `identity.session-events` consumer that marks the
  player disconnected in any active room, opening the 60-second window.
- **Platform seam, mirroring identity's P2 setup** — own CNPG role owning `room_gameplay`, sealed
  secrets, pinned digest, `/metrics` + `ServiceMonitor`, and a startup migration.
- **Client CLI** — `room create/join/list/leave`, `play --casual`, and the interactive loop, with
  the canonical card notation of Client-Checkpoint §5.F (`R5`, `BSKIP`, `Y+2`, `WILD+4`).
- **A second NodePort.** identity owns 30080 and kind only maps that one, so room-gameplay needs
  `kind-cluster.yaml` to publish a second port and the CLI to accept a second target
  (`UNOARENA_ROOMS_URL`). Both collapse back into one the moment P4's gateway becomes the single
  entry point — recorded as a delta, not pretended away.

## Out of scope (→ later phases)

- **SSE, the gateway, `bot --casual`, 409 reconciliation in the live feed** (P4). P3 polls.
- **Outbox relay and Kafka publication of room events** (P5). P3 fills the outbox; nothing drains
  it yet, which is exactly the seam P5 plugs into.
- **The timer-worker's push** (P5). P3's deadlines are correct but only fire when a command arrives.
- **Tournament rooms, best-of-three, `matchScores`** (P7). `roomType` stays in the model; only
  `Casual` is implemented, and the room plays exactly one game.
- **Spectator projection** (P6), **Elo** (P6), **audit read path with mTLS/RBAC** (Architecture
  §2.9 — the restricted `GET …/moves` collection is not exposed in P3).
- **Snapshots and partition affinity** (E6/E7) — revisit only if the exam shape shows they matter.

## Acceptance criteria

- **AC-P3.1 — Rooms behave like resources.** `POST /rooms` (with `Idempotency-Key`, replays
  returning the original representation), `GET /rooms/{id}`, `POST`/`DELETE`
  `/rooms/{id}/players/{pid}` are idempotent, `409` when the room is full or already started,
  `204` when deleting an absent membership, and `GET /rooms` lists joinable rooms.
- **AC-P3.2 — The engine is provably right.** Property tests hold the invariants over thousands of
  randomly-generated legal games: the multiset of cards is conserved across every transition
  (deck + hands + discard always equals the starting composition), the active colour always admits
  a legal play, at most one challenge window is open, and turn order respects direction and skips.
  Log-replay tests reconstruct the exact final state from the event log alone, deck order included
  — the recorded seed makes it deterministic, and replaying twice is byte-identical.
- **AC-P3.3 — Log before broadcast.** Every accepted command writes its events **and** its outbox
  rows in a single transaction, and the response is sent only after commit. A forced failure mid-
  command leaves zero events, zero outbox rows and no sequence number consumed. Verified against a
  **real** Postgres (the CI smoke's ephemeral one, and the kind drill) — not against a fake, since
  a fake cannot prove transactionality.
- **AC-P3.4 — Concurrency is honest.** Stale `If-Match` → `412`, missing `If-Match` → `428`,
  illegal or out-of-turn move → `409`. Two writers racing the same sequence number: exactly one
  commits, the loser gets `412` and can reconcile from the returned state.
- **AC-P3.5 — Deadlines without a scheduler.** With the clock advanced past them, the next command
  first expires the Uno! challenge window (5 s), the turn timer (30 s, configurable → auto-draw +
  pass, `TurnTimedOut`) and the reconnection window (60 s → forfeit), each recorded as an event.
- **AC-P3.6 — A superseded session disconnects the player.** A second login for a player who is in
  an active room produces `PlayerDisconnected` in that room, via the Kafka topic P2 already
  publishes, and starts the 60-second window.
- **AC-P3.7 — A full casual game plays through the CLI.** Two CLI processes, against a cluster
  deployed from empty, play a complete 2-player game to a winner: join, turn board, numbered hand,
  play by index, wild colour, draw, Uno! call and a successful challenge, ending in
  `GameCompleted` in the log.
- **AC-P3.8 — It comes up from an empty cluster.** `install.sh` alone brings room-gameplay to
  `Synced/Healthy` with its own database role, decrypted secrets, migrated schema and a scraped
  `/metrics`, alongside everything P1 and P2 already deliver.
- **AC-P3.9 — Pipeline unchanged in shape and green.** `main` green after the merge; no new stage,
  no new job kind. `test:room-gameplay` runs the engine's property and replay suites.

## Behaviour contract (edge cases)

- **Wild without a colour** is rejected (`409`), never silently defaulted; the active colour is set
  the instant the wild is accepted (invariant 12).
- **First card rule** applies the initial discard's effect before the first player acts, including
  burying an initial Wild Draw Four and drawing again (invariant 14).
- **`hasCalledUno` resets whenever the hand size changes** (invariant, §3.2.3) — a player who calls
  Uno! and then draws is vulnerable again.
- **Challenge validity:** a challenge is only valid against a player sitting at one card who did
  not call Uno!, and only while the window is open; an invalid challenge is a `409`, not a penalty.
- **Deck exhaustion** recycles the discard pile except its top card, shuffles with a **new recorded
  seed**, and is one atomic `DeckRecycled` event — replay must reproduce it exactly.
- **Fewer than two active players** (after forfeits or leaves) ends the game immediately and the
  last player standing wins (invariant 7).
- **Reconnection** (`PATCH /rooms/{id}/players/{pid}`) returns the player-scoped state so the client
  can rehydrate, and cancels the 60-second window if it has not expired.
- **A player's hand never leaves the service**, not in any event payload, not in another player's
  game view — the spectator privacy boundary of P6 depends on that being true from the start.
- **Lazy expiry has a visible consequence:** a human thinking at the terminal is only penalised
  when *some* command arrives, so a turn timeout can land late. That is the honest shape of E2 and
  exactly the gap P5's timer-worker closes — it must be documented in the CLI README, not
  discovered by the faculty.
- **Replay is the definition of correct.** If the aggregate rebuilt from the log ever disagrees
  with the served state, the log wins and the bug is in the code, not the log.
