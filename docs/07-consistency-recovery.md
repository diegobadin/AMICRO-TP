# 7 — Consistency & Recovery Strategy (Domain Level)

> This section addresses consistency, retries, deduplication, and saga/compensation decisions **at the domain level** — not infrastructure-level patterns (message brokers, databases, etc.).

---

## 7.1 Consistency Model by Context

| Context | Consistency Model | Rationale |
|---------|------------------|-----------|
| **Room Gameplay** | **Strong consistency** (linearizable within a room) | Game correctness demands that every command is serialized. Two players must never see conflicting game states. The sequence number mechanism ensures total ordering. |
| **Tournament Orchestration** | **Strong consistency** (within a tournament aggregate) | Round advancement is a critical decision that depends on all room results being recorded. The tournament aggregate must not advance a round until all rooms report in. |
| **Ranking & Elo** | **Eventual consistency** | Elo updates are derived from game results. A brief delay between game completion and Elo adjustment is acceptable and does not affect gameplay. |
| **Spectator View** | **Eventual consistency** | A few seconds of lag for spectators is tolerable. Game correctness is unaffected by spectator projection delay. |
| **Analytics & Brackets** | **Eventual consistency** | Analytics and bracket views are read models. They can lag significantly without impacting the platform's core function. |
| **Identity & Session** | **Strong consistency** (within a player identity aggregate) | Session invalidation must be immediate — an old session must not be usable after a new login. However, propagation of invalidation to other contexts (Room Gameplay checking token validity) can have a short eventual-consistency window. |

---

## 7.2 Intra-Aggregate Consistency (Room Gameplay)

### 7.2.1 Sequence Number Serialization

The Room aggregate enforces **strict total ordering** of all state-mutating commands:

```
Client sends: PlayCard { seq: N }
                │
                ▼
Room Aggregate checks: room.seq == N ?
  ├── Yes → Accept command, execute business logic,
  │         increment room.seq to N+1,
  │         emit events, return success
  │
  └── No  → Reject with HTTP 409 Conflict
            Return { currentSeq: room.seq, latestState }
            Client reconciles and retries
```

**Why this prevents invariant violations:**
- Only one command per sequence number is ever accepted.
- All clients observe the same linear history of events via SSE.
- No optimistic-locking retries needed — the rejection is deterministic and immediate.
- Even under high concurrency, at most one command succeeds per sequence step.

### 7.2.2 Challenge Window as a Temporal Invariant

The challenge window is a time-bounded state within the Room aggregate:

```
Invariant: At most one challenge window is open at any time.

Open condition:  A player plays to exactly 1 card remaining.
Close conditions (first to occur):
  1. 5-second timer expires
  2. A valid challenge is processed
  3. The next player begins their turn (plays a card or draws)

Enforcement:
  - ChallengeUno commands are rejected if no window is open.
  - The timer is evaluated at command-processing time (server clock),
    not at command-send time (client clock).
  - This eliminates client-clock manipulation attacks.
```

---

## 7.3 Cross-Context Event Delivery & Deduplication

### 7.3.1 General Pattern

All cross-context communication uses **asynchronous domain events** with at-least-once delivery semantics. Every consumer is responsible for **idempotent processing**.

```
Producer (Room Gameplay)
  │
  │  emit GameCompleted { eventId: E1, gameId: G1, ... }
  │
  ▼
Event Bus (durable, at-least-once delivery)
  │
  ├──► Ranking Consumer
  │      Check: has E1 been processed? (or: has G1 been rated?)
  │        Yes → skip
  │        No  → process, record E1/G1 as processed
  │
  ├──► Tournament Consumer
  │      Check: has roomId result been recorded?
  │        Yes → skip
  │        No  → process
  │
  └──► Analytics Consumer
         (Analytics is append-only; deduplication by eventId)
```

### 7.3.2 Deduplication Strategies

| Consumer | Deduplication Key | Storage |
|----------|-------------------|---------|
| Ranking (Elo) | `gameId` | Processed-games log per `PlayerRating` aggregate |
| Ranking (Tournament Placement) | `tournamentId` | Processed-tournaments log per `PlayerRating` aggregate |
| Tournament (room result) | `roomId` within a round | `TournamentRoomRef.result != null` guard |
| Analytics | `eventId` | Append-only with unique constraint on `eventId` |
| Spectator View | `sequenceNumber` per room | Last-processed seq; out-of-order events buffered or dropped |

---

## 7.4 Saga: Tournament Round Advancement

The most complex cross-context workflow is **tournament round advancement**, which involves coordination between Tournament Orchestration and Room Gameplay across potentially 100,000+ rooms.

### 7.4.1 Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  SAGA: Round Advancement                                        │
│                                                                 │
│  Trigger: RoundStarted event                                    │
│                                                                 │
│  Step 1: Tournament emits RoomCreationRequested (×N rooms)      │
│    │  Each is an independent command to Room Gameplay.           │
│    │  Tournament tracks: rooms_created / rooms_expected          │
│    │                                                             │
│  Step 2: Room Gameplay creates rooms, starts games              │
│    │  Emits: RoomCreated (or RoomCreationFailed) for each       │
│    │                                                             │
│  Step 3: Games play out (minutes to hours)                      │
│    │                                                             │
│  Step 4: Rooms complete, emit MatchCompleted / RoomCompleted    │
│    │  Tournament records each result via RecordRoomResult       │
│    │                                                             │
│  Step 5: All rooms reported → RoundCompleted                    │
│    │  Tournament checks: advancingPlayers ≤ 10?                 │
│    │    Yes → FinalRoomCreated                                  │
│    │    No  → new RoundStarted (back to Step 1)                │
│    │                                                             │
│  Compensation: see below                                        │
└─────────────────────────────────────────────────────────────────┘
```

### 7.4.2 Compensation & Recovery Scenarios

| Failure | Detection | Compensation |
|---------|-----------|-------------|
| `RoomCreationRequested` delivered but room not created | Tournament polls for `RoomCreated` acknowledgment; timeout after configurable period | Re-emit `RoomCreationRequested` (idempotent). If persistent failure, alert operators. |
| Room created but game never starts | Room-level inactivity timeout → `RoomExpired` | Tournament treats expired room as if all players forfeited. No players advance from that room. |
| Room completes but `MatchCompleted` event is lost | Tournament has a "stale room" detector: rooms that have been `InProgress` beyond a maximum game duration threshold | Tournament queries Room Gameplay for room status (compensating read). If room is `Completed`, manually triggers `RecordRoomResult`. |
| Tournament context crashes mid-round | On restart, Tournament reloads its event-sourced state | All `RoomResultRecorded` events are replayed. The round-completion check re-evaluates. Safe due to idempotency. |
| Partial round advancement (some rooms created for next round, then crash) | On restart, Tournament checks which rooms from the pending round actually exist | Re-emits `RoomCreationRequested` for missing rooms (idempotent). |

### 7.4.3 Why This Is Not a Distributed Transaction

- Each room is an independent aggregate. There is no need for atomic cross-room consistency.
- The tournament aggregate coordinates via **event collection**, not distributed locking.
- A room completing faster or slower than others does not affect correctness — the tournament simply waits for all rooms.
- The only critical invariant is: **a round does not advance until ALL rooms report**. This is enforced within the Tournament aggregate's own consistency boundary (a simple counter: `completedRooms == totalRooms`).

---

## 7.5 Invariant Protection Summary

| Invariant | Where Enforced | How Violations Are Prevented |
|-----------|---------------|------------------------------|
| Sequence serialization (exactly-once per room) | Room aggregate | Reject commands with mismatched seq number (409) |
| Turn enforcement | Room aggregate | Command validation: `currentPlayer == command.playerId` |
| Play legality | Room aggregate | Card-matching rules evaluated before acceptance |
| Uno call requirement | Room aggregate + challenge window | Window opens automatically; challenge resolves penalty |
| Game count boundary | Room aggregate | Casual rooms: exactly 1 game. Tournament rooms: up to 3 games. `gamesPlayed < maxGames` checked before `StartGame` |
| Player count bounds (2–10) | Room aggregate | Reject `JoinRoom` at capacity; end game if < 2 active |
| Single active session | PlayerIdentity aggregate | New login invalidates old session atomically |
| Elo: casual-only, non-abandoned, per-game | Ranking consumer policy | Filter on `roomType` and `isAbandoned` before processing |
| Elo: no double-counting | PlayerRating aggregate | `gameId` deduplication check |
| Round advancement gate | Tournament aggregate | `completedRooms == totalRooms` predicate |
| No re-entry to tournament | Tournament aggregate | Eliminated players tracked in a set; `RegisterPlayer` rejects them |
| Reconnection window (60s) | Room aggregate | Timer tracked per player; expiry triggers `ForfeitPlayer` |
| Immutable game log | Room aggregate | Append-only data structure; no update/delete operations exposed |
| Spectator privacy | Public Event Publisher + ACL | Hand data stripped at source; validated at boundary |
| Action card effects (atomic) | Room aggregate | Skip/Reverse/DrawTwo/WD4 effects applied within same `PlayCard` processing; no separate command needed |
| Wild color declaration | Room aggregate | `PlayCard` for Wild without `chosenColor` is rejected; no intermediate "color pending" state |
| Turn timer (30s default) | Room aggregate | Server-side timer per turn; expiry triggers auto-draw + pass via `TurnTimedOut` |
| First card rule | Room aggregate | Initial discard card effects applied at game start; Wild Draw Four is buried and redrawn |

---

## 7.6 Handling Invariant Violations (Detection & Recovery)

Even with prevention, the system should detect and recover from violations that might occur due to bugs:

| Potential Violation | Detection | Recovery |
|--------------------|-----------|----------|
| Game log entry modified | Signature verification on read (HMAC mismatch) | Flag game as corrupted; escalate to admin; exclude from Elo calculations |
| Elo applied to tournament game | Periodic reconciliation job: cross-reference Elo changes with room types | Reverse incorrect Elo deltas via compensating `EloUpdated` events |
| Player advances from a room they weren't in | Tournament aggregate validates player membership before recording advancement | Reject the advancement; log a security alert |
| Spectator view contains hand data | ACL validation on every event crossing the boundary | Reject the event; alert the development team; Spectator View skips the event (shows slightly stale state) |
| Duplicate room creation in tournament | Room Gameplay checks `idempotencyKey` (derived from `tournamentId + roundNumber + roomIndex`) | Returns existing room; no duplicate created |

---

## 7.7 Retry Policies (Domain Level)

| Operation | Retry Strategy | Max Retries | Backoff |
|-----------|---------------|-------------|---------|
| Player command (PlayCard, DrawCard, etc.) | **No server retry.** Client retries with reconciled state after 409. | N/A (client-driven) | Client exponential backoff |
| Cross-context event delivery | At-least-once with consumer deduplication | Unlimited (durable queue) | Exponential backoff with jitter |
| Room creation for tournament | Tournament retries on `RoomCreationFailed` | 3 attempts | Fixed delay (2s, 5s, 10s) then alert |
| Elo update | Consumer retries on transient failure | Unlimited (event replay from checkpoint) | Exponential backoff |
| Reconnection | Player-initiated; no server retry | N/A | Within 60s window; player retries at will |
