# 2 — Bounded Contexts & Context Map

---

## 2.1 Identified Bounded Contexts

### 2.1.1 Room Gameplay Context (Core Domain)

**Responsibility:** Owns the authoritative state of every room — deck, hands, discard pile, turn order, Uno call mechanics, challenge windows, disconnection timers, and the game log. Accepts player commands, enforces rules, serializes mutations via sequence numbers, and emits domain events.

**Key domain objects:** Room (aggregate root), Game, Hand, Deck, DiscardPile, TurnOrder, UnoCallState, ChallengeWindow, GameLog.

**What it does NOT own:** Player identity/authentication, tournament structure, Elo calculations, or spectator delivery.

---

### 2.1.2 Tournament Orchestration Context (Core Domain)

**Responsibility:** Manages the full tournament lifecycle — registration, round generation, room creation requests, player-to-room assignment (seeding), advancement logic, tiebreaking, and final-room detection. Reacts to `MatchCompleted` events from rooms to drive round progression.

**Key domain objects:** Tournament (aggregate root), Round, TournamentRoom (reference, not the gameplay room itself), Bracket, AdvancementResult.

**What it does NOT own:** In-game mechanics, card state, or Elo. It knows only match outcomes, not how they were produced.

---

### 2.1.3 Ranking & Elo Context (Supporting Domain)

**Responsibility:** Maintains every player's Elo rating (casual) and Tournament Placement Rating. Consumes `GameCompleted` events from casual rooms to compute Elo deltas. Consumes `TournamentCompleted` events for placement rating updates. Enforces the rule that abandoned games and tournament games do not affect Elo.

**Key domain objects:** PlayerRating (aggregate root), EloCalculation (domain service), RatingHistory.

**What it does NOT own:** Game mechanics, tournament structure, or player identity.

---

### 2.1.4 Identity & Session Context (Generic/Supporting)

**Responsibility:** Authentication, authorization, single-active-session enforcement, token management, and rate limiting. On new login, invalidates any existing session for that player and emits `SessionInvalidated`. Provides session tokens verified by other contexts on every command.

**Key domain objects:** PlayerIdentity (aggregate root), Session, RateLimitPolicy, AuditEntry.

**What it does NOT own:** Game state, tournament state, or ranking data.

---

### 2.1.5 Spectator View Context (Downstream Read Model)

**Responsibility:** Maintains a **privacy-filtered**, read-optimized projection of room state for spectators. Subscribes to Room Gameplay events and strips all private information (hands) before making state available to spectator clients.

**Key domain objects:** SpectatorRoomView (read model), PublicPlayerState, DiscardPileView.

**Privacy boundary (critical):**

| Information | Visible to Spectators? |
|-------------|----------------------|
| Player names & seat positions | ✅ Yes |
| Card count per player | ✅ Yes |
| Discard pile (all cards, ordered) | ✅ Yes |
| Current turn & direction | ✅ Yes |
| Uno call status | ✅ Yes |
| Game/match score | ✅ Yes |
| Player hand contents | ❌ **Never** |
| Deck contents / order | ❌ **Never** |
| RNG seed or shuffle state | ❌ **Never** |

**Events that drive Spectator View updates:**

- `CardPlayed` → updates discard pile, card count, current turn, active color
- `CardDrawn` → increments card count (card identity withheld)
- `ForcedDraw` → increments target player's card count (from Draw Two / Wild Draw Four effects)
- `DirectionReversed` → updates direction indicator
- `TurnSkipped` → advances current turn indicator (reasons: disconnection, skip card, draw two, wild draw four, reverse in 2-player)
- `TurnTimedOut` → shows player timed out, advances turn
- `UnoCallMade` → updates Uno status
- `UnoChallengeIssued` / `UnoChallengeResolved` → updates challenge state and card counts
- `GameStarted` / `GameCompleted` → resets or finalizes view
- `PlayerDisconnected` / `PlayerReconnected` → updates player status indicator
- `PlayerForfeited` → removes player from active display

**What crosses the boundary:** Only public projections of events. The Spectator View context never receives raw `Hand` data. The Room Gameplay context is responsible for producing **already-filtered** public event payloads — the Spectator View does not perform the filtering itself, eliminating any risk of an implementation bug leaking private data.

---

### 2.1.6 Analytics & Brackets Context (Supporting Read Model)

**Responsibility:** Absorbs high-volume event streams (`GameCompleted`, `RoundAdvanced`, `MatchCompleted`) and maintains read-optimized projections for player statistics, bracket visualization, tournament leaderboards, and historical match data. Designed for eventual consistency; can lag behind real-time gameplay without impacting correctness.

**Key domain objects:** PlayerStats (read model), BracketView (read model), TournamentLeaderboard (read model).

---

## 2.2 Context Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        UnoArena Context Map                              │
│                                                                          │
│  ┌─────────────────┐         ┌──────────────────────────┐                │
│  │   Identity &    │ U/D     │   Room Gameplay           │                │
│  │   Session       │────────▶│   (Core)                  │                │
│  │   (Generic)     │ OHS/CF  │                            │                │
│  └─────────────────┘         └──────┬───────┬────────────┘                │
│                                     │       │                             │
│                        GameCompleted│       │CardPlayed,                  │
│                        MatchCompleted       │GameStarted, etc.            │
│                                     │       │(public payloads only)       │
│                                     ▼       ▼                             │
│  ┌──────────────────────────┐  ┌─────────────────────┐                   │
│  │ Tournament Orchestration │  │   Spectator View     │                   │
│  │ (Core)                   │  │   (Read Model)       │                   │
│  └──────────┬───────────────┘  └─────────────────────┘                   │
│             │                                                             │
│             │ RoomCreationRequested                                       │
│             │ (upstream to Room Gameplay)                                 │
│             │                                                             │
│             │ TournamentCompleted                                         │
│             ▼                                                             │
│  ┌──────────────────────────┐  ┌─────────────────────┐                   │
│  │   Ranking & Elo          │  │   Analytics &        │                   │
│  │   (Supporting)           │  │   Brackets (Read)    │                   │
│  └──────────────────────────┘  └─────────────────────┘                   │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

Legend:
  ──▶  Upstream / Downstream (arrow = data flows downstream)
  U/D  Upstream / Downstream relationship
  OHS  Open Host Service (Identity exposes a standard auth API)
  CF   Conformist (Room Gameplay conforms to Identity's token format)
```

## 2.3 Context Relationships

| Upstream | Downstream | Relationship | Integration Pattern |
|----------|------------|-------------|-------------------|
| Identity & Session | Room Gameplay | OHS / Conformist | Room Gameplay validates session tokens issued by Identity. Conforms to Identity's token schema. |
| Identity & Session | Tournament Orchestration | OHS / Conformist | Same as above. |
| Room Gameplay | Tournament Orchestration | Customer–Supplier | Tournament Orchestration is the customer; Room Gameplay publishes `GameCompleted`, `MatchCompleted` events that Tournament Orchestration consumes to drive advancement. |
| Tournament Orchestration | Room Gameplay | Customer–Supplier (reverse for room creation) | Tournament Orchestration emits `RoomCreationRequested`; Room Gameplay creates tournament-linked rooms. |
| Room Gameplay | Spectator View | Published Language | Room Gameplay publishes pre-filtered public event payloads via a published language. Spectator View is a pure consumer; it cannot send commands back. |
| Room Gameplay | Ranking & Elo | Published Language | `GameCompleted` events (with finishing order and room type) flow downstream. Ranking consumes and computes independently. |
| Room Gameplay | Analytics & Brackets | Published Language | High-volume event stream consumed asynchronously. |
| Tournament Orchestration | Analytics & Brackets | Published Language | `RoundAdvanced`, `BracketUpdated`, `TournamentCompleted` events consumed for bracket views. |
| Tournament Orchestration | Ranking & Elo | Published Language | `TournamentCompleted` with final placements flows to Ranking for placement rating updates. |

## 2.4 Anti-Corruption Layers

- **Spectator View ACL:** Even though Room Gameplay already produces filtered payloads, the Spectator View maintains a minimal ACL that validates no `Hand` data or deck-order data is present in incoming events. This is a defense-in-depth measure — if a code change in Room Gameplay accidentally includes private data, the ACL rejects the event and raises an alert.

- **Tournament → Room Gameplay ACL:** Tournament Orchestration never directly mutates room state. It issues `RoomCreationRequested` commands with player lists and room configuration. Room Gameplay validates these independently (e.g., rejects rooms with fewer than 2 players or more than 10).

## 2.5 Spectator View — Deep Treatment

**Design principle:** The Spectator View is a **separate bounded context**, not merely a filtered API endpoint on Room Gameplay. This separation guarantees:

1. **Structural isolation:** No code path exists for spectator queries to reach private game state.
2. **Independent scaling:** Spectator read load (potentially 100x player count for popular tournament rooms) scales independently from game command processing.
3. **Failure isolation:** If the Spectator View projection fails or lags, game correctness is unaffected.

**Event transformation pipeline:**

```
Room Gameplay Aggregate
        │
        ▼
  Emits internal event (contains full state delta, including hand changes)
        │
        ▼
  Public Event Publisher (within Room Gameplay context boundary)
        │  ── strips hand contents, deck order, RNG state
        │  ── retains: card counts, discard top, turn info, scores
        ▼
  Published public event (crosses context boundary)
        │
        ▼
  Spectator View ACL (validates no private data leaked)
        │
        ▼
  Spectator View Projection (read model update)
        │
        ▼
  Spectator clients receive updates
```

**What spectators explicitly cannot do:**
- Query any player's hand contents.
- Reconstruct deck order from draw events (events contain only `CardDrawn { playerId, newCardCount }`, never card identity).
- Access the game log's full entries (the game log contains hand mutations; only the public subset is projected).
- Send any command that affects game state (spectators are pure read-only consumers).
