# 4 — Commands & Domain Events Catalog

---

## 4.1 Room Gameplay Context

### Commands

| Command | Issuer | Target Aggregate | Preconditions | Idempotency | Key Fields |
|---------|--------|-------------------|---------------|-------------|------------|
| `CreateRoom` | Player | Room | Player authenticated; not in another active room | Idempotent by `idempotencyKey` | `roomType`, `maxPlayers`, `idempotencyKey` |
| `JoinRoom` | Player | Room | Room in `Waiting`; player count < max; player not already joined | Idempotent (re-joining same room = no-op) | `roomId`, `playerId` |
| `StartGame` | System / Host | Room | Room in `Waiting` or between games; ≥ 2 players; match not complete | Idempotent by game number | `roomId` |
| `PlayCard` | Player | Room | Player's turn; card in hand; card is legal play; correct `sequenceNumber` | Rejected on duplicate seq (409) | `roomId`, `playerId`, `card`, `chosenColor?`, `sequenceNumber`, `callingUno` |
| `DrawCard` | Player | Room | Player's turn; correct `sequenceNumber` | Rejected on duplicate seq (409) | `roomId`, `playerId`, `sequenceNumber` |
| `PassTurn` | Player | Room | Player's turn; player has drawn this turn and cannot/chooses not to play; correct `sequenceNumber` | Rejected on duplicate seq (409) | `roomId`, `playerId`, `sequenceNumber` |
| `CallUno` | Player | Room | Player has exactly 2 cards and is about to play one, OR just played to 1 card | Can be sent with `PlayCard` or separately within window | `roomId`, `playerId` |
| `ChallengeUno` | Any opponent | Room | Challenge window is open for target player | Idempotent (first valid challenge wins; subsequent ones no-op) | `roomId`, `challengerId`, `targetPlayerId` |
| `ChooseColor` | Player | Room | Player just played a Wild; color not yet chosen | Idempotent (once chosen, re-sends are no-ops) | `roomId`, `playerId`, `color` |
| `ReconnectPlayer` | Player | Room | Player is in `Disconnected` status; within 60s window; valid session | Idempotent (reconnecting when already connected = no-op) | `roomId`, `playerId`, `sessionToken` |
| `ForfeitPlayer` | System (timer) or Player | Room | Player is in the room; game is in progress | Idempotent by player status | `roomId`, `playerId`, `reason` |

### Domain Events

| Event | Emitted By | Triggered After | Key Payload | Downstream Consumers |
|-------|-----------|-----------------|-------------|---------------------|
| `RoomCreated` | Room | `CreateRoom` accepted | `roomId`, `roomType`, `creatorId` | Analytics |
| `PlayerJoined` | Room | `JoinRoom` accepted | `roomId`, `playerId`, `playerCount` | Spectator View |
| `GameStarted` | Room | `StartGame` | `roomId`, `gameNumber`, `playerOrder`, `initialDiscardCard` (public), initial card counts | Spectator View, Analytics |
| `CardPlayed` | Room | `PlayCard` accepted | `roomId`, `playerId`, `card`, `newDiscardTop`, `playerCardCount`, `chosenColor?`, `nextPlayerId` | Spectator View, Analytics |
| `CardDrawn` | Room | `DrawCard` accepted | `roomId`, `playerId`, `newCardCount` (card identity NOT included in public payload) | Spectator View |
| `TurnPassed` | Room | `PassTurn` accepted | `roomId`, `playerId`, `nextPlayerId` | Spectator View |
| `ColorChosen` | Room | `ChooseColor` accepted | `roomId`, `playerId`, `color` | Spectator View |
| `UnoCallMade` | Room | `CallUno` or `PlayCard` with `callingUno=true` | `roomId`, `playerId` | Spectator View |
| `ChallengeWindowOpened` | Room | Card played leaving player with 1 card | `roomId`, `targetPlayerId`, `expiresAt` | Spectator View |
| `ChallengeWindowClosed` | Room | 5s timeout or next turn starts | `roomId`, `targetPlayerId`, `reason` | Spectator View |
| `UnoChallengeIssued` | Room | `ChallengeUno` accepted | `roomId`, `challengerId`, `targetPlayerId` | Spectator View |
| `UnoChallengeResolved` | Room | Challenge evaluated | `roomId`, `challengerId`, `targetPlayerId`, `challengeSucceeded`, `penaltyPlayerId`, `penaltyCardCount` | Spectator View |
| `TurnSkipped` | Room | Disconnected player's turn arrives | `roomId`, `skippedPlayerId`, `nextPlayerId`, `reason: disconnection` | Spectator View |
| `DeckRecycled` | Room | Draw pile exhausted | `roomId`, `newDeckSize` (no card order exposed) | Spectator View |
| `PlayerDisconnected` | Room | Connection lost detected | `roomId`, `playerId`, `reconnectionDeadline` | Spectator View |
| `PlayerReconnected` | Room | `ReconnectPlayer` accepted | `roomId`, `playerId` | Spectator View |
| `PlayerForfeited` | Room | Forfeit triggered | `roomId`, `playerId`, `reason`, `isTournament` | Spectator View, Tournament Orch. |
| `GameCompleted` | Room | Last card played or last active player standing | `roomId`, `gameNumber`, `finishingOrder`, `cardPointTotals`, `completedAt`, `isAbandoned` | Ranking (if casual + not abandoned), Tournament Orch., Analytics |
| `MatchCompleted` | Room | Best-of-3 resolved or all games played | `roomId`, `matchResults: Map<PlayerId, MatchScore>`, `advancingPlayers` (if tournament) | Tournament Orch., Analytics |
| `RoomCompleted` | Room | Match ends | `roomId`, `finalResults` | Tournament Orch., Analytics |
| `StaleCommandRejected` | Room | Command with wrong seq number | `roomId`, `playerId`, `expectedSeq`, `receivedSeq` | (Client via HTTP 409; not propagated to other contexts) |
| `GameLogEntryAppended` | Room | Every state change | `roomId`, `entry` (signed) | Audit (internal) |

---

## 4.2 Tournament Orchestration Context

### Commands

| Command | Issuer | Target Aggregate | Preconditions | Idempotency |
|---------|--------|-------------------|---------------|-------------|
| `CreateTournament` | Admin/System | Tournament | Valid config; admin authorized | Idempotent by `idempotencyKey` |
| `RegisterPlayer` | Player | Tournament | Tournament in `Registration`; player count < max; player not already registered | Idempotent (re-register = no-op) |
| `UnregisterPlayer` | Player | Tournament | Tournament in `Registration`; player is registered | Idempotent |
| `StartTournament` | Admin/System | Tournament | Tournament in `Registration`; ≥ 2 players registered | Idempotent by tournament status |
| `RecordRoomResult` | Policy (reacts to `MatchCompleted`) | Tournament | Room belongs to current round; result not already recorded | Idempotent by `roomId` — duplicate results are ignored |
| `AdvanceRound` | Policy (auto-triggered) | Tournament | All rooms in current round completed | Idempotent by round number |
| `CompleteTournament` | Policy (auto-triggered) | Tournament | Final room completed | Idempotent by status |

### Domain Events

| Event | Triggered After | Key Payload | Downstream Consumers |
|-------|-----------------|-------------|---------------------|
| `TournamentCreated` | `CreateTournament` | `tournamentId`, `config` | Analytics |
| `PlayerRegistered` | `RegisterPlayer` | `tournamentId`, `playerId`, `registeredCount` | Analytics |
| `PlayerUnregistered` | `UnregisterPlayer` | `tournamentId`, `playerId` | Analytics |
| `TournamentStarted` | `StartTournament` | `tournamentId`, `totalPlayers`, `roundCount` (estimated) | Analytics |
| `RoundStarted` | Round generation complete | `tournamentId`, `roundNumber`, `roomCount`, `roomIds` | Analytics, Brackets |
| `RoomCreationRequested` | During round start | `tournamentId`, `roundNumber`, `roomId`, `assignedPlayers` | Room Gameplay (creates the room) |
| `RoomResultRecorded` | `RecordRoomResult` | `tournamentId`, `roundNumber`, `roomId`, `advancingPlayers` | Analytics, Brackets |
| `RoundCompleted` | All rooms in round finished | `tournamentId`, `roundNumber`, `advancingPlayersTotal` | Analytics, Brackets |
| `FinalRoomCreated` | ≤ 10 players remain | `tournamentId`, `roomId`, `finalists` | Analytics, Brackets |
| `TournamentCompleted` | Final room finishes | `tournamentId`, `champion`, `finalPlacements` | Ranking (placement rating), Analytics |

---

## 4.3 Ranking & Elo Context

### Commands

| Command | Issuer | Target Aggregate | Preconditions | Idempotency |
|---------|--------|-------------------|---------------|-------------|
| `UpdateElo` | Policy (reacts to `GameCompleted`) | PlayerRating | Game was casual; game not abandoned; not already processed | Idempotent by `gameId` — deduplicated via event log |
| `UpdateTournamentPlacement` | Policy (reacts to `TournamentCompleted`) | PlayerRating | Tournament not already processed | Idempotent by `tournamentId` |

### Domain Events

| Event | Triggered After | Key Payload |
|-------|-----------------|-------------|
| `EloUpdated` | `UpdateElo` processed | `playerId`, `oldElo`, `newElo`, `delta`, `gameId` |
| `TournamentPlacementUpdated` | `UpdateTournamentPlacement` | `playerId`, `oldRating`, `newRating`, `tournamentId`, `placement` |

---

## 4.4 Identity & Session Context

### Commands

| Command | Issuer | Preconditions | Idempotency |
|---------|--------|---------------|-------------|
| `Authenticate` | Player | Valid credentials | Returns existing session if already active |
| `InvalidateSession` | System (on new login) or Admin | Session exists | Idempotent — invalidating an already-invalid session is a no-op |
| `RefreshToken` | Player | Current token valid and near expiry | Idempotent within refresh window |
| `CheckRateLimit` | System (on every command) | — | Stateless check; no mutation needed |

### Domain Events

| Event | Triggered After | Key Payload | Downstream Consumers |
|-------|-----------------|-------------|---------------------|
| `PlayerAuthenticated` | `Authenticate` | `playerId`, `sessionId` | Audit |
| `SessionInvalidated` | `InvalidateSession` | `playerId`, `oldSessionId`, `reason` | Room Gameplay (triggers disconnect detection), Audit |
| `RateLimitExceeded` | `CheckRateLimit` fails | `playerId`, `ip`, `action`, `limit` | Audit, Security monitoring |

---

## 4.5 Causality Map (Event Chains)

```
Player sends PlayCard
  → Room accepts → CardPlayed 🟠
      → if player has 1 card left → ChallengeWindowOpened 🟠
      → if player has 0 cards → GameCompleted 🟠
          → 🟡 Policy: Is this game 2 or 3, or does a player have 2 wins?
              → Yes → MatchCompleted 🟠
                  → 🟡 Policy: RoomCompleted 🟠
                      → if tournament room:
                          → 🟡 RecordRoomResult → RoomResultRecorded 🟠
                              → 🟡 All rooms done? → AdvanceRound → RoundCompleted 🟠
                                  → 🟡 ≤ 10 players? → FinalRoomCreated 🟠
                                  → 🟡 > 10 players? → RoundStarted 🟠 (next round)
                      → if casual room:
                          → 🟡 UpdateElo → EloUpdated 🟠 (for each non-abandoned game)
              → No → StartGame (next game in match)

Player disconnects
  → PlayerDisconnected 🟠
      → 🟡 60s timer starts
      → On each turn: TurnSkipped 🟠
      → Timer expires → ForfeitPlayer → PlayerForfeited 🟠
          → if last player standing → GameCompleted 🟠 (chain continues above)

ChallengeWindowOpened 🟠
  → 🟡 5s timer starts
  → Player sends ChallengeUno → UnoChallengeIssued 🟠 → UnoChallengeResolved 🟠
      → ChallengeWindowClosed 🟠
  → Timer expires → ChallengeWindowClosed 🟠
  → Next turn starts → ChallengeWindowClosed 🟠
```

---

## 4.6 Idempotency Strategy Summary

| Mechanism | Applied To | How It Works |
|-----------|-----------|--------------|
| **Sequence number** | All Room mutations | Command carries expected seq. Mismatch → 409 Conflict. Match → accept + increment. Guarantees exactly-once within a room. |
| **Idempotency key** | `CreateRoom`, `CreateTournament` | Client-generated UUID. If a room/tournament with that key already exists, return the existing entity. |
| **Event ID deduplication** | Cross-context event consumers (Ranking, Tournament, Analytics) | Each consumer tracks the last processed event ID per source. Re-delivered events with known IDs are skipped. |
| **Status guard** | `RegisterPlayer`, `StartTournament`, etc. | Commands check current aggregate status. If the operation has already been performed (e.g., tournament already started), return success without re-applying. |
| **Challenge window state** | `ChallengeUno` | Only the first valid challenge per window is accepted. Subsequent challenges during the same window are no-ops. |
