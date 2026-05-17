# 5 — Domain Event Flow Narratives

> Each narrative walks through an end-to-end business flow, identifying synchronous decision points (within an aggregate boundary) and asynchronous propagation (across context boundaries).

---

## 5.1 Casual Room: Creation to Completion

### Scenario: Three players create and play through a casual room

```
Timeline ──────────────────────────────────────────────────────────────►

PHASE 1: Room Setup
═══════════════════

Player A ──► 🔵 CreateRoom { type: Casual, maxPlayers: 4 }
                │
                ▼ (synchronous — Room aggregate)
           🟣 Room accepts → seq = 1
                │
                ▼
           🟠 RoomCreated { roomId: R1, type: Casual, status: Waiting }
                │
                ├──► Spectator View: creates empty room projection
                └──► Analytics: records room creation

Player B ──► 🔵 JoinRoom { roomId: R1 }
                │
                ▼ (synchronous — Room aggregate)
           🟣 Room accepts → seq = 2
                │
                ▼
           🟠 PlayerJoined { roomId: R1, playerId: B, playerCount: 2 }

Player C ──► 🔵 JoinRoom { roomId: R1 }  →  🟠 PlayerJoined { playerCount: 3 }

Player A ──► 🔵 StartGame { roomId: R1 }
                │
                ▼ (synchronous — Room aggregate)
           🟣 Room validates: status = Waiting, players ≥ 2 ✓
           🟣 Deck service: generate seed, shuffle 108 cards
           🟣 Deal 7 cards to each player
           🟣 Place top card on discard pile
           🟣 Set turn order [A, B, C], direction: Clockwise
           🟣 Room status → InProgress, seq = 4
                │
                ▼
           🟠 GameStarted { roomId: R1, gameNumber: 1,
                             playerOrder: [A,B,C],
                             initialDiscardTop: Red 5,
                             cardCounts: {A:7, B:7, C:7} }
                │
                ├──► Spectator View: initializes game view (no hand data)
                └──► Analytics: records game start


PHASE 2: Gameplay Loop (example turns)
═══════════════════════════════════════

Player A ──► 🔵 PlayCard { card: Red 7, seq: 5 }
                │
                ▼ (synchronous — Room aggregate)
           🟣 Validate: A's turn? ✓  Card in hand? ✓  Legal play? ✓  seq=5? ✓
           🟣 Remove Red 7 from A's hand → 6 cards
           🟣 Push Red 7 onto discard pile
           🟣 Advance turn to B
           🟣 Append to game log (signed)
           🟣 seq → 6
                │
                ▼
           🟠 CardPlayed { playerId: A, card: Red 7, cardCount: 6, nextPlayer: B }
           🟠 GameLogEntryAppended { ... }
                │
                └──► Spectator View: updates discard pile, card counts, current player

  ... (many turns) ...

Player A ──► 🔵 PlayCard { card: Red Draw Two, seq: 35 }
                │
                ▼ (synchronous)
           🟣 Validate play ✓
           🟣 Draw Two effect: B must draw 2 cards, B's turn is skipped
           🟣 seq → 36
                │
                ▼
           🟠 CardPlayed { playerId: A, card: Red Draw Two, cardCount: 5, nextPlayer: C }
           🟠 ForcedDraw { targetPlayerId: B, cardCount: 2, newHandSize: 9, reason: draw_two }
           🟠 TurnSkipped { skippedPlayerId: B, nextPlayer: C, reason: draw_two }

  ... (several turns later) ...

Player B ──► 🔵 PlayCard { card: Blue Skip, seq: 42, callingUno: true }
                │
                ▼ (synchronous)
           🟣 B has 2 cards → playing one leaves 1 → Uno call required
           🟣 callingUno = true → record Uno call
           🟣 Skip effect: C's turn is skipped, next player = A
           🟣 Open challenge window (5s) for B
           🟣 seq → 43
                │
                ▼
           🟠 CardPlayed { playerId: B, card: Blue Skip, cardCount: 1, nextPlayer: A }
           🟠 TurnSkipped { skippedPlayerId: C, nextPlayer: A, reason: skip_card }
           🟠 UnoCallMade { playerId: B }
           🟠 ChallengeWindowOpened { targetPlayerId: B, expiresAt: T+5s }

  ... (5 seconds pass, no challenge) ...

           🟠 ChallengeWindowClosed { targetPlayerId: B, reason: timeout }


PHASE 3: Game End
═════════════════

Player B ──► 🔵 PlayCard { card: Blue 3, seq: 50 }
                │
                ▼ (synchronous)
           🟣 B has 1 card → playing it leaves 0 → B WINS
           🟣 Calculate card-point totals for remaining players
           🟣 Determine finishing order: [B=1st, A=2nd, C=3rd]
           🟣 Game status → Completed
           🟣 seq → 51
                │
                ▼
           🟠 CardPlayed { playerId: B, card: Blue 3, cardCount: 0 }
           🟠 GameCompleted { gameNumber: 1, finishingOrder: [B,A,C],
                              cardPointTotals: {A: 35, C: 62},
                              isAbandoned: false,
                              completedAt: T1 }
                │
                ├──► (async) Ranking context:
                │       🟡 Policy: room is casual + game not abandoned → UpdateElo
                │       🟣 PlayerRating[B]: EloCalculation([B,A,C], ratings) → +15
                │       🟠 EloUpdated { playerId: B, delta: +15 }
                │       🟣 PlayerRating[A]: → +2
                │       🟠 EloUpdated { playerId: A, delta: +2 }
                │       🟣 PlayerRating[C]: → -17
                │       🟠 EloUpdated { playerId: C, delta: -17 }
                │
                └──► Analytics: records game result


PHASE 4: Room Completion (casual = single game)
════════════════════════════════════════════════

  Since this is a casual room, the single game's completion ends the room:

           🟠 RoomCompleted { roomId: R1, status: Completed }
                │
                └──► Analytics: records room completion

  Note: casual rooms do NOT produce a MatchCompleted event — the match
  concept applies only to tournament rooms (best-of-three).
```

---

## 5.2 Tournament Round Advancement

### Scenario: 1,000 players, Round 1 → Round 2

```
Timeline ──────────────────────────────────────────────────────────────►

PHASE 1: Tournament Start & Round 1 Generation
═══════════════════════════════════════════════

Admin ──► 🔵 StartTournament { tournamentId: T1 }
                │
                ▼ (synchronous — Tournament aggregate)
           🟣 Validate: status = Registration, players ≥ 2 ✓
           🟣 1,000 players registered
           🟣 Distribute into rooms of 10 → 100 rooms
           🟣 Round 1 created, status → InProgress
           🟣 Tournament status → InProgress
                │
                ▼
           🟠 TournamentStarted { tournamentId: T1, totalPlayers: 1000 }
           🟠 RoundStarted { roundNumber: 1, roomCount: 100 }
                │
                ▼ (for each room)
           🟠 RoomCreationRequested { tournamentId: T1, roundNumber: 1,
                                      roomId: R001, assignedPlayers: [P1..P10] }
           ... (×100)
                │
                └──► (async) Room Gameplay context:
                        For each RoomCreationRequested:
                        🟣 Room.create(tournament-linked) → 🟠 RoomCreated
                        🟣 Auto-join assigned players → 🟠 PlayerJoined (×10)
                        🟣 Auto-start game → 🟠 GameStarted


PHASE 2: Rooms Complete (asynchronous, varying durations)
═════════════════════════════════════════════════════════

  Room R001 completes:
  🟠 MatchCompleted { roomId: R001, advancingPlayers: [P1, P3, P7] }
  🟠 RoomCompleted { roomId: R001 }
        │
        └──► (async) Tournament Orchestration:
                🟡 Policy: react to RoomCompleted for tournament rooms
                🔵 RecordRoomResult { roomId: R001, advancingPlayers: [P1, P3, P7] }
                      │
                      ▼ (synchronous — Tournament aggregate)
                 🟣 Record result for R001
                 🟣 Check: all 100 rooms complete? → No (only 1/100)
                      │
                      ▼
                 🟠 RoomResultRecorded { roundNumber: 1, roomId: R001,
                                         advancers: [P1, P3, P7] }

  ... (rooms complete over time) ...

  Room R100 completes (last room):
  🟠 RoomCompleted { roomId: R100 }
        │
        └──► Tournament Orchestration:
                🔵 RecordRoomResult { roomId: R100, advancingPlayers: [P298, P301, P305] }
                      │
                      ▼ (synchronous)
                 🟣 Record result → all 100 rooms now complete ✓
                 🟣 Total advancing: 300 players (3 per room × 100 rooms)
                 🟣 Round 1 status → Completed
                      │
                      ▼
                 🟠 RoomResultRecorded { ... }
                 🟠 RoundCompleted { roundNumber: 1, advancingPlayersTotal: 300 }


PHASE 3: Round 2 Generation
════════════════════════════

                 🟡 Policy: RoundCompleted → check if ≤ 10 remain
                    300 > 10 → generate next round
                 🔵 AdvanceRound
                      │
                      ▼ (synchronous — Tournament aggregate)
                 🟣 Distribute 300 players into rooms of 10 → 30 rooms
                 🟣 Round 2 created, status → InProgress
                      │
                      ▼
                 🟠 RoundStarted { roundNumber: 2, roomCount: 30 }
                 🟠 RoomCreationRequested { ... } (×30)

  ... (cycle repeats) ...


PHASE 4: Final Room
════════════════════

  After Round N, ≤ 10 players remain (say, 8 players):

                 🟡 Policy: ≤ 10 players → create final room
                 🟠 FinalRoomCreated { tournamentId: T1, roomId: R_FINAL, finalists: [...] }
                      │
                      └──► Room Gameplay creates final room, plays match

  Final room completes:
  🟠 MatchCompleted { roomId: R_FINAL, ... }
  🟠 RoomCompleted { roomId: R_FINAL }
        │
        └──► Tournament Orchestration:
                🔵 CompleteTournament
                      │
                      ▼
                 🟣 Set champion = match winner
                 🟣 Compute final placements from final room results
                 🟣 Tournament status → Completed
                      │
                      ▼
                 🟠 TournamentCompleted { champion: P42, finalPlacements: [...] }
                      │
                      ├──► Ranking: 🔵 UpdateTournamentPlacement (for each finalist)
                      └──► Analytics: final bracket + stats
```

### Scale Analysis: 1,000,000 Players

At maximum capacity, the domain model behaves as follows:

| Round | Players | Rooms (10/room) | RoomCreationRequested Events | Concurrent Matches |
|-------|---------|------------------|------------------------------|--------------------|
| 1 | 1,000,000 | 100,000 | 100,000 (burst) | up to 100,000 |
| 2 | 300,000 | 30,000 | 30,000 | up to 30,000 |
| 3 | 90,000 | 9,000 | 9,000 | up to 9,000 |
| 4 | 27,000 | 2,700 | 2,700 | up to 2,700 |
| 5 | 8,100 | 810 | 810 | up to 810 |
| 6 | 2,430 | 243 | 243 | up to 243 |
| 7 | 729 | 73 | 73 | up to 73 |
| 8 | 219 | 22 | 22 | up to 22 |
| 9 | 66 | 7 | 7 | up to 7 |
| 10 | 21 | 3 | 3 | up to 3 |
| 11 | 9 | 1 (final) | 1 | 1 |

**Key domain observations at this scale:**

- **Round 1 surge:** The Tournament aggregate emits 100,000 `RoomCreationRequested` events in a burst. Each is an independent command to Room Gameplay — no cross-room coordination needed. The Room Gameplay context must absorb this creation spike without blocking tournament progression.
- **Convergence:** The tournament converges from 1M to a single final room in approximately 11 rounds (log₃ formula, since top 3 advance per room of 10). Each round reduces the player count by ~⅔.
- **Round gate invariant:** The Tournament aggregate blocks round advancement until all rooms report `RoomCompleted`. In Round 1, this means waiting for the slowest of 100,000 rooms. Long-running rooms (due to close matches or disconnections) gate the entire round — this is a domain-level constraint that cannot be relaxed without compromising fairness.
- **Event volume:** At peak (Round 1), with ~100,000 concurrent rooms each playing up to 3 games of ~50–100 turns, the system produces on the order of 15–30 million game-state events per round. Downstream consumers (Spectator View, Analytics, Brackets) must absorb this eventual-consistency load without affecting gameplay latency.
- **Room aggregate isolation:** Each Room aggregate is fully independent — no shared state between rooms. This makes horizontal distribution natural at the domain level: room R1's game logic never needs to coordinate with R2's.

---

## 5.3 Elo/Ranking Updates After Game Completion

### Scenario: 4-player casual game completes, Elo updated

```
Timeline ──────────────────────────────────────────────────────────────►

  Room Gameplay emits:
  🟠 GameCompleted {
       roomId: R1,
       gameNumber: 1,
       roomType: Casual,
       finishingOrder: [D=1st, A=2nd, B=3rd, C=4th],
       cardPointTotals: {A: 15, B: 42, C: 68},
       isAbandoned: false,
       completedAt: T2
     }
        │
        ▼ (asynchronous — crosses context boundary)

  Ranking & Elo Context:
  🟡 Policy: "When GameCompleted AND roomType=Casual AND isAbandoned=false → UpdateElo"

  Step 1: Deduplication check
     Has gameId (R1-G1) been processed before? → No ✓

  Step 2: Load player ratings
     🟣 PlayerRating[D]: Elo = 1350
     🟣 PlayerRating[A]: Elo = 1200
     🟣 PlayerRating[B]: Elo = 1180
     🟣 PlayerRating[C]: Elo = 1400

  Step 3: Calculate Elo deltas
     EloCalculation service:
       D (1st, rated 1350) vs field → outperformed expectations slightly → +8
       A (2nd, rated 1200) vs field → outperformed expectations → +12
       B (3rd, rated 1180) vs field → roughly expected → -2
       C (4th, rated 1400) vs field → severely underperformed → -18

  Step 4: Apply updates (one aggregate command per player)
     🔵 UpdateElo { playerId: D, gameId: R1-G1, delta: +8 }
         → 🟣 PlayerRating[D]: 1350 → 1358
         → 🟠 EloUpdated { playerId: D, old: 1350, new: 1358, gameId: R1-G1 }

     🔵 UpdateElo { playerId: A, gameId: R1-G1, delta: +12 }
         → 🟣 PlayerRating[A]: 1200 → 1212
         → 🟠 EloUpdated { playerId: A, old: 1200, new: 1212, gameId: R1-G1 }

     🔵 UpdateElo { playerId: B, gameId: R1-G1, delta: -2 }
         → 🟣 PlayerRating[B]: 1180 → 1178
         → 🟠 EloUpdated { ... }

     🔵 UpdateElo { playerId: C, gameId: R1-G1, delta: -18 }
         → 🟣 PlayerRating[C]: 1400 → 1382
         → 🟠 EloUpdated { ... }

  Step 5: Record processing
     Mark gameId R1-G1 as processed → prevents re-processing on re-delivery


  ┌─────────────────────────────────────────────────────────┐
  │ FILTER CASES (Elo is NOT updated):                      │
  │                                                         │
  │ • roomType = Tournament → skip                          │
  │ • isAbandoned = true → skip                             │
  │ • gameId already processed → skip (idemp.)              │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ FINISHING ORDER DETERMINATION:                           │
  │                                                         │
  │ • 1st: player who emptied their hand                    │
  │ • 2nd–last: ranked by ascending card-point total        │
  │   (fewer points = higher rank)                          │
  │ • Tie in card-point total: player with fewer cards      │
  │   remaining ranks higher                                │
  └─────────────────────────────────────────────────────────┘
```

---

## 5.4 Disconnection → Reconnection / Forfeit Flow

```
Timeline ──────────────────────────────────────────────────────────────►

  Player B loses connection at T0:
  🟠 PlayerDisconnected { playerId: B, reconnectionDeadline: T0+60s }
        │
        ├──► Spectator View: shows B as "disconnected"
        └──► 🟡 Start 60s reconnection timer

  Turn reaches B at T0+10s:
  🟠 TurnSkipped { playerId: B, reason: disconnection, nextPlayer: C }
        │
        └──► Spectator View: shows "B's turn skipped"

  Turn reaches B again at T0+25s:
  🟠 TurnSkipped { playerId: B, reason: disconnection, nextPlayer: A }


  PATH A — Reconnection within window (T0+30s):
  ─────────────────────────────────────────────
  Player B ──► 🔵 ReconnectPlayer { roomId: R1, playerId: B }
                │
                ▼ (synchronous)
           🟣 Validate: B in Disconnected status? ✓
           🟣 Within 60s window? T0+30s < T0+60s ✓
           🟣 Session valid? ✓
           🟣 Restore connection status → Connected
           🟣 Hand remains intact (was never modified)
                │
                ▼
           🟠 PlayerReconnected { playerId: B }
           (B resumes normal play on their next turn)


  PATH B — Window expires (T0+60s):
  ──────────────────────────────────
  Timer expires at T0+60s:
  🔵 ForfeitPlayer { playerId: B, reason: reconnection_timeout }
        │
        ▼ (synchronous)
   🟣 B's status → Forfeited
   🟣 Remove B from turn order
   🟣 If B's turn was active → advance to next player
        │
        ▼
   🟠 PlayerForfeited { playerId: B, reason: reconnection_timeout }

   Branch by room type:
   ┌──────────────────────────────────────────────────────┐
   │ Casual room:                                         │
   │   B is out; game continues with remaining players.   │
   │   If only 1 player remains → that player wins.       │
   │   → 🟠 GameCompleted { ... }                         │
   ├──────────────────────────────────────────────────────┤
   │ Tournament room:                                     │
   │   Forfeit = match loss. B is eliminated.             │
   │   If only 1 player remains in match → winner by      │
   │   default. → 🟠 MatchCompleted, RoomCompleted         │
   │   Tournament records B as non-advancing.             │
   └──────────────────────────────────────────────────────┘
```

---

## 5.5 Uno Challenge Flow (Detail)

```
Timeline ──────────────────────────────────────────────────────────────►

  Player A plays their second-to-last card WITHOUT calling Uno:

  🔵 PlayCard { playerId: A, card: Green 4, seq: 30, callingUno: false }
        │
        ▼ (synchronous — Room aggregate)
   🟣 Validate play ✓
   🟣 A had 2 cards → now has 1 card
   🟣 callingUno = false → A did NOT call Uno
   🟣 Open challenge window (5s)
   🟣 Record: A.hasCalledUno = false
   🟣 Next turn = B, BUT challenge window is open
        │
        ▼
   🟠 CardPlayed { playerId: A, card: Green 4, cardCount: 1 }
   🟠 ChallengeWindowOpened { targetPlayerId: A, expiresAt: T+5s }


  CASE 1: Challenge succeeds (A forgot to call Uno)
  ─────────────────────────────────────────────────
  Player C ──► 🔵 ChallengeUno { challengerId: C, targetPlayerId: A }
                │
                ▼ (synchronous)
           🟣 Challenge window open for A? ✓
           🟣 Did A call Uno? → No
           🟣 Challenge SUCCEEDS → A draws 2 penalty cards
           🟣 Close challenge window
           🟣 A now has 3 cards (1 + 2 penalty)
                │
                ▼
           🟠 UnoChallengeIssued { challengerId: C, targetPlayerId: A }
           🟠 UnoChallengeResolved { challengeSucceeded: true,
                                     penaltyPlayerId: A, penaltyCardCount: 2 }
           🟠 ChallengeWindowClosed { reason: challenge_resolved }


  CASE 2: Challenge fails (A did call Uno)
  ─────────────────────────────────────────
  (Assume A sent callingUno: true with PlayCard)

  Player C ──► 🔵 ChallengeUno { challengerId: C, targetPlayerId: A }
                │
                ▼ (synchronous)
           🟣 Challenge window open for A? ✓
           🟣 Did A call Uno? → Yes
           🟣 Challenge FAILS → C draws 2 penalty cards
           🟣 Close challenge window
                │
                ▼
           🟠 UnoChallengeIssued { challengerId: C, targetPlayerId: A }
           🟠 UnoChallengeResolved { challengeSucceeded: false,
                                     penaltyPlayerId: C, penaltyCardCount: 2 }
           🟠 ChallengeWindowClosed { reason: challenge_resolved }


  CASE 3: No challenge, window expires
  ─────────────────────────────────────
  5 seconds pass with no challenge:
   🟠 ChallengeWindowClosed { reason: timeout }
   (A keeps 1 card, no penalty — got away with not calling Uno)


  CASE 4: Next player acts before window expires
  ───────────────────────────────────────────────
  Player B ──► 🔵 PlayCard { seq: 31 }   (before 5s elapses)
                │
                ▼ (synchronous)
           🟣 Accepting B's play closes A's challenge window immediately
           🟠 ChallengeWindowClosed { reason: next_turn_started }
           🟠 CardPlayed { playerId: B, ... }
```
