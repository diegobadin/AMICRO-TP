# 3 — Aggregates, Entities & Value Objects

---

## 3.1 Overview of Consistency Boundaries

```
Room Gameplay Context
├── 🟣 Room Aggregate (primary, large)
│   ├── Game (entity, nested)
│   │   ├── Hand (entity per player, nested)
│   │   ├── Deck (entity, nested)
│   │   ├── DiscardPile (entity, nested)
│   │   └── TurnOrder (entity, nested)
│   └── GameLog (entity, nested)
│
Tournament Orchestration Context
├── 🟣 Tournament Aggregate
│   └── Round (entity, nested)
│
Ranking & Elo Context
├── 🟣 PlayerRating Aggregate
│
Identity & Session Context
├── 🟣 PlayerIdentity Aggregate
│   └── Session (entity, nested)
```

---

## 3.2 Room Gameplay Context

### 3.2.1 🟣 Room Aggregate (Aggregate Root)

The Room is the **central consistency boundary** of the entire platform. All game-state mutations are serialized through this aggregate via sequence numbers.

**Identity:** `RoomId` (UUID)

**State:**

| Field | Type | Description |
|-------|------|-------------|
| `roomId` | `RoomId` | Unique room identifier |
| `roomType` | `RoomType` | `Casual` or `Tournament { tournamentId, roundNumber }` |
| `status` | `RoomStatus` | `Waiting`, `InProgress`, `Completed` |
| `players` | `List<RoomPlayer>` | Seated players with connection status |
| `currentGame` | `Game?` | Active game entity (null in Waiting/between games) |
| `matchScores` | `Map<PlayerId, MatchScore>?` | Tournament rooms only: wins, losses, cumulative card-points per player across games. Null for casual rooms (single game). |
| `gamesPlayed` | `int` | Number of completed games in this room |
| `maxGames` | `int` | Maximum games: 1 for Casual, 3 for Tournament (derived from `roomType`) |
| `sequenceNumber` | `int` | Monotonically increasing; incremented on every accepted mutation |
| `gameLog` | `GameLog` | Append-only log of all state changes |
| `createdAt` | `Timestamp` | Room creation time |

**Key Invariants:**

1. **Sequence serialization:** Every command must carry the expected `sequenceNumber`. If `command.seq ≠ room.seq`, reject with 409 Conflict.
2. **Turn enforcement:** Only the current player may play a card or draw. Exception: any player may issue an Uno challenge during an open challenge window.
3. **Play legality:** A card can only be played if it matches the top discard card's color or face, or is a Wild.
4. **Uno call requirement:** If a player plays their second-to-last card without calling Uno, they are vulnerable to a valid challenge within the 5-second window.
5. **Challenge window exclusivity:** Only one challenge window is open at a time. It closes on: 5-second timeout, next turn start, or challenge resolution.
6. **Game count boundary:** Casual rooms play exactly 1 game. Tournament rooms play up to 3 games (match). A tournament match ends when all 3 games are played or when the remaining games provably cannot change the advancement outcome (e.g., one player already has 3 wins).
7. **Player count:** 2 ≤ players ≤ 10 at room start. A room with fewer than 2 active players (after forfeits) ends the current game immediately; the last remaining player wins.
8. **Disconnection timer:** Each disconnected player has an independent 60-second timer. Turns are skipped while disconnected. Expiry triggers forfeit.
9. **Immutable log:** The GameLog is append-only. No entry may be modified or deleted.
10. **Single active game:** At most one Game is active within a Room at any time.
11. **Action card effects:** When an action card (Skip, Reverse, Draw Two, Wild Draw Four) is played, its effect is applied atomically within the same command processing. Skip → next player loses their turn. Reverse → direction toggles (in a 2-player game, acts as Skip — the player retains the turn). Draw Two → next player draws 2 cards and loses their turn. Wild Draw Four → next player draws 4 cards and loses their turn. These effects are not separate commands; they are enforced by the aggregate as part of `PlayCard` processing.
12. **Wild color declaration:** When a Wild or Wild Draw Four is played, the `chosenColor` field in the `PlayCard` command is **mandatory**. A `PlayCard` for a Wild without a declared color is rejected. The active color is set to the declared color immediately.
13. **Turn timer:** Connected players have a configurable time limit to act on their turn (default: 30 seconds). If the timer expires, the system automatically draws a card for the player (if they haven't drawn this turn) and passes their turn. Emits `TurnTimedOut`.
14. **First card rule:** At game start, the initial discard card's effect is applied before the first player acts: Skip → first player is skipped; Reverse → direction set to counter-clockwise (2-player: first player skipped); Draw Two → first player draws 2 and is skipped; Wild → first player must choose the active color; Wild Draw Four → card is buried in the deck and a new initial discard is drawn.

---

### 3.2.2 Game (Entity, within Room)

**Identity:** `GameNumber` (1, 2, or 3 within the match)

| Field | Type | Description |
|-------|------|-------------|
| `gameNumber` | `int` | 1–3 |
| `status` | `GameStatus` | `InProgress`, `Completed` |
| `deck` | `Deck` | Remaining draw pile |
| `discardPile` | `DiscardPile` | Played cards stack |
| `hands` | `Map<PlayerId, Hand>` | Each player's private hand |
| `turnOrder` | `TurnOrder` | Circular player sequence with current index and direction (see §3.2.8) |
| `challengeWindow` | `ChallengeWindow?` | Open challenge, if any |
| `activeColor` | `Color` | Current color in play (may differ from top discard if Wild was played) |
| `finishingOrder` | `List<PlayerId>` | Players in order of elimination/game end |
| `turnTimerDeadline` | `Timestamp?` | Deadline for the current player to act (null if not their turn or game paused) |
| `completedAt` | `Timestamp?` | When the game ended |

---

### 3.2.3 Hand (Entity, within Game)

**Identity:** `PlayerId` (contextual within a Game)

| Field | Type |
|-------|------|
| `cards` | `List<Card>` | 
| `hasCalledUno` | `bool` |

**Invariant:** `hasCalledUno` is reset to `false` whenever the hand size changes (draw or penalty).

---

### 3.2.4 Deck (Entity, within Game)

| Field | Type |
|-------|------|
| `cards` | `List<Card>` (ordered, top = index 0) |
| `rngSeed` | `Seed` (set at shuffle time, recorded in game log) |

**Invariant:** When the deck is exhausted, the discard pile (except the top card) is recycled, shuffled with a new server-generated seed, and becomes the new deck. This is an atomic operation recorded as a single `DeckRecycled` log entry.

---

### 3.2.5 DiscardPile (Entity, within Game)

| Field | Type |
|-------|------|
| `cards` | `List<Card>` (ordered, top = last element) |

---

### 3.2.6 GameLog (Entity, within Room)

| Field | Type |
|-------|------|
| `entries` | `List<GameLogEntry>` (append-only) |

Each `GameLogEntry`:

| Field | Type | Description |
|-------|------|-------------|
| `entryId` | `int` | Sequential within the room |
| `sequenceNumber` | `int` | Room sequence number at time of entry |
| `timestamp` | `Timestamp` | Server-authoritative time |
| `eventType` | `string` | Domain event type name |
| `payload` | `EventPayload` | Full event data |
| `signature` | `Signature` | HMAC of the entry for integrity verification |

---

### 3.2.7 RoomPlayer (Entity, within Room)

| Field | Type | Description |
|-------|------|-------------|
| `playerId` | `PlayerId` | |
| `displayName` | `string` | |
| `connectionStatus` | `ConnectionStatus` | `Connected`, `Disconnected { since, deadline }`, `Forfeited` |
| `joinedAt` | `Timestamp` | |

---

### 3.2.8 TurnOrder (Entity, within Game)

**Identity:** Contextual within a Game (one per game).

| Field | Type | Description |
|-------|------|-------------|
| `activePlayers` | `List<PlayerId>` | Ordered circular ring of active (non-forfeited) players in seating order |
| `currentIndex` | `int` | Index of the current player within `activePlayers` |
| `direction` | `Direction` | `Clockwise` (+1) or `CounterClockwise` (-1) |

**Invariants:**

- When a player forfeits, they are removed from `activePlayers` without changing the relative order of remaining players. The `currentIndex` is adjusted to remain valid.
- The next player is determined by `(currentIndex + direction) mod activePlayers.length`.
- When a **Reverse** card is played, `direction` toggles. In a **2-player game**, since reversing direction points back to the current player, the effect is equivalent to a Skip (the current player retains the turn).
- When a **Skip** card (or Draw Two / Wild Draw Four) is played, the next player in sequence is skipped and the turn advances to the player after them.

---

### 3.2.9 ChallengeWindow (Value Object)

| Field | Type |
|-------|------|
| `targetPlayerId` | `PlayerId` |
| `openedAt` | `Timestamp` |
| `expiresAt` | `Timestamp` (openedAt + 5s) |
| `targetCalledUno` | `bool` |

---

## 3.3 Tournament Orchestration Context

### 3.3.1 🟣 Tournament Aggregate (Aggregate Root)

**Identity:** `TournamentId` (UUID)

| Field | Type | Description |
|-------|------|-------------|
| `tournamentId` | `TournamentId` | |
| `status` | `TournamentStatus` | `Registration`, `InProgress`, `Completed` |
| `registeredPlayers` | `Set<PlayerId>` | Players who registered |
| `currentRound` | `int` | Current round number (1-based) |
| `rounds` | `List<Round>` | Completed and in-progress rounds |
| `config` | `TournamentConfig` | Max players, room size, advancement count |
| `champion` | `PlayerId?` | Winner, set upon completion |
| `finalPlacements` | `List<PlayerId>?` | Ordered final placements |

**Key Invariants:**

1. **Round completion gate:** A new round cannot start until **all** rooms in the current round have completed.
2. **Advancement count:** Exactly the top 3 players per room advance (unless fewer than 3 players were in a room, in which case all non-forfeited players advance).
3. **Final room threshold:** When 10 or fewer players remain, the next round creates a single final room instead of multiple rooms.
4. **No re-entry:** A player eliminated in a round cannot re-enter the tournament.
5. **Registration cap:** Cannot exceed the configured maximum player count.
6. **Tiebreaking determinism:** Ties are broken by cumulative card-point total (lower wins), then by earliest final-game completion time. This must be deterministic — no random tiebreakers.

---

### 3.3.2 Round (Entity, within Tournament)

| Field | Type | Description |
|-------|------|-------------|
| `roundNumber` | `int` | 1-based |
| `status` | `RoundStatus` | `Pending`, `InProgress`, `Completed` |
| `rooms` | `List<TournamentRoomRef>` | References to rooms (not full game state) |
| `advancingPlayers` | `List<PlayerId>?` | Set after all rooms complete |

---

### 3.3.3 TournamentRoomRef (Value Object)

| Field | Type |
|-------|------|
| `roomId` | `RoomId` |
| `assignedPlayers` | `List<PlayerId>` |
| `status` | `RoomStatus` |
| `result` | `RoomResult?` (top 3, card-point totals, completion time) |

---

## 3.4 Ranking & Elo Context

### 3.4.1 🟣 PlayerRating Aggregate (Aggregate Root)

**Identity:** `PlayerId`

| Field | Type | Description |
|-------|------|-------------|
| `playerId` | `PlayerId` | |
| `eloRating` | `int` | Current casual Elo (default 1200) |
| `tournamentPlacementRating` | `int` | Separate tournament rating |
| `ratingHistory` | `List<RatingChange>` | Auditable history of changes |
| `lastGameId` | `GameId?` | Idempotency guard for Elo updates |

**Key Invariants:**

1. **Casual-only Elo:** Elo is only updated for games in casual rooms. Tournament games never affect Elo.
2. **Per-game update:** Elo changes once per completed game, not per match.
3. **Abandoned game exclusion:** Games where all remaining players forfeit produce no Elo change.
4. **Idempotency:** Processing the same `GameCompleted` event twice must not double-apply the Elo delta. Guarded by `lastGameId` or an event-processing log.

---

## 3.5 Identity & Session Context

### 3.5.1 🟣 PlayerIdentity Aggregate (Aggregate Root)

**Identity:** `PlayerId` (UUID)

| Field | Type | Description |
|-------|------|-------------|
| `playerId` | `PlayerId` | |
| `credentials` | `Credentials` | Hashed password or OAuth reference |
| `activeSession` | `Session?` | At most one |
| `rateLimitState` | `RateLimitState` | Current throttle counters |

**Key Invariants:**

1. **Single active session:** At most one active session per player. New login invalidates the old session immediately.
2. **Session integrity:** Session tokens are signed and time-bounded. Expired tokens are rejected.
3. **Rate limit enforcement:** Commands exceeding rate limits are rejected before reaching downstream contexts.

---

### 3.5.2 Session (Entity, within PlayerIdentity)

| Field | Type |
|-------|------|
| `sessionId` | `SessionId` |
| `token` | `SignedToken` |
| `createdAt` | `Timestamp` |
| `expiresAt` | `Timestamp` |
| `ipAddress` | `IP` |
| `isActive` | `bool` |

---

## 3.6 Value Objects Catalog

| Value Object | Context | Description |
|-------------|---------|-------------|
| `Card` | Room Gameplay | Immutable (color + face). Equality by value. |
| `Color` | Room Gameplay | Enum: Red, Yellow, Green, Blue, Wild. |
| `Face` | Room Gameplay | Enum: Zero–Nine, Skip, Reverse, DrawTwo, Wild, WildDrawFour. |
| `RoomType` | Room Gameplay | `Casual` or `Tournament { tournamentId, roundNumber }`. |
| `RoomStatus` | Room Gameplay | Enum: Waiting, InProgress, Completed. |
| `GameStatus` | Room Gameplay | Enum: InProgress, Completed. |
| `Direction` | Room Gameplay | Enum: Clockwise, CounterClockwise. |
| `ConnectionStatus` | Room Gameplay | `Connected`, `Disconnected { since, deadline }`, `Forfeited`. |
| `MatchScore` | Room Gameplay | `{ wins: int, losses: int, cumulativeCardPoints: int }`. |
| `PlayConstraint` | Room Gameplay | Derived from top discard + active color. |
| `TournamentConfig` | Tournament | `{ maxPlayers, roomSize, advancementCount }`. |
| `RoomResult` | Tournament | `{ advancingPlayers, cardPointTotals, completionTimes }`. |
| `RatingChange` | Ranking | `{ gameId, oldRating, newRating, delta, timestamp }`. |
| `EloCalculation` | Ranking | Pure function: `(playerRatings, finishingOrder) → Map<PlayerId, EloDelta>`. |
| `TurnTimerConfig` | Room Gameplay | `{ durationSeconds: int }`. Default 30s. Configurable per tournament. |
| `Seed` | Room Gameplay | Opaque RNG seed value used for deck shuffling. |
| `Signature` | Room Gameplay | HMAC-based integrity signature for game log entries. |
| `SignedToken` | Identity | JWT or equivalent containing playerId, sessionId, expiry, signature. |

---

## 3.7 Aggregate Size & Performance Considerations

The **Room aggregate** is the largest and most contended. In the worst case (10 players, game 3), it holds approximately 108 cards across deck/hands/discard, plus the full game log. Key design decisions:

- **Sequence-number serialization** eliminates optimistic-locking retries within a room — only one command is accepted per sequence number, and all others get a deterministic 409 rejection.
- **Game log growth** is bounded by the number of turns in a game (~200–500 entries for a typical 10-player game). Three games per match caps the log at ~1,500 entries.
- **Tournament aggregate** is lighter: it holds player IDs and room references, not game state. The heaviest operation is round generation (distributing up to 1M players into rooms), which is a one-time burst per round.
