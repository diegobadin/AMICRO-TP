# 4 — Commands & Domain Events Catalog

---

## 4.1 Room Gameplay Context

### Commands

| Command | Issuer | Target Aggregate | Preconditions | Idempotency | Key Fields |
|---------|--------|-------------------|---------------|-------------|------------|
| `CreateRoom` | Player | Room | Player authenticated; not in another active room | Idempotent by `idempotencyKey` | `roomType`, `maxPlayers`, `idempotencyKey` |
| `JoinRoom` | Player | Room | Room in `Waiting`; player count < max; player not already joined | Idempotent (re-joining same room = no-op) | `roomId`, `playerId` |
| `StartGame` | System / Host | Room | Room in `Waiting` or between games (tournament only); ≥ 2 active players; `gamesPlayed < maxGames` | Idempotent by game number | `roomId` |
| `PlayCard` | Player | Room | Player's turn; card in hand; card is legal play; correct `sequenceNumber`; if Wild/WildDrawFour then `chosenColor` is **required** | Rejected on duplicate seq (409) | `roomId`, `playerId`, `card`, `chosenColor?` (mandatory for Wilds), `sequenceNumber`, `callingUno` |
| `DrawCard` | Player | Room | Player's turn; correct `sequenceNumber` | Rejected on duplicate seq (409) | `roomId`, `playerId`, `sequenceNumber` |
| `PassTurn` | Player | Room | Player's turn; player has drawn this turn and cannot/chooses not to play; correct `sequenceNumber` | Rejected on duplicate seq (409) | `roomId`, `playerId`, `sequenceNumber` |
| `CallUno` | Player | Room | Player has exactly 2 cards and is about to play one, OR just played to 1 card | Can be sent with `PlayCard` or separately within window | `roomId`, `playerId` |
| `ChallengeUno` | Any opponent | Room | Challenge window is open for target player | Idempotent (first valid challenge wins; subsequent ones no-op) | `roomId`, `challengerId`, `targetPlayerId` |
| `ReconnectPlayer` | Player | Room | Player is in `Disconnected` status; within 60s window; valid session | Idempotent (reconnecting when already connected = no-op) | `roomId`, `playerId`, `sessionToken` |
| `ForfeitPlayer` | System (timer) or Player | Room | Player is in the room; game is in progress | Idempotent by player status | `roomId`, `playerId`, `reason` |

### Domain Events

| Event | Emitted By | Triggered After | Key Payload | Downstream Consumers |
|-------|-----------|-----------------|-------------|---------------------|
| `RoomCreated` | Room | `CreateRoom` accepted | `roomId`, `roomType`, `creatorId` | Analytics |
| `PlayerJoined` | Room | `JoinRoom` accepted | `roomId`, `playerId`, `playerCount` | Spectator View |
| `GameStarted` | Room | `StartGame` | `roomId`, `gameNumber`, `playerOrder`, `initialDiscardCard` (public), initial card counts | Spectator View, Analytics |
| `CardPlayed` | Room | `PlayCard` accepted | `roomId`, `playerId`, `card`, `newDiscardTop`, `playerCardCount`, `chosenColor?` (present for Wilds), `nextPlayerId` | Spectator View, Analytics |
| `CardDrawn` | Room | `DrawCard` accepted | `roomId`, `playerId`, `newCardCount` (card identity NOT included in public payload) | Spectator View |
| `TurnPassed` | Room | `PassTurn` accepted | `roomId`, `playerId`, `nextPlayerId` | Spectator View |
| `ForcedDraw` | Room | Draw Two or Wild Draw Four effect applied | `roomId`, `targetPlayerId`, `cardCount` (2 or 4), `newHandSize`, `reason: draw_two \| wild_draw_four` | Spectator View |
| `DirectionReversed` | Room | Reverse card played | `roomId`, `newDirection` | Spectator View |
| `TurnTimedOut` | Room | Connected player's turn timer expired | `roomId`, `playerId`, `autoAction: draw_and_pass \| pass` | Spectator View |
| `UnoCallMade` | Room | `CallUno` or `PlayCard` with `callingUno=true` | `roomId`, `playerId` | Spectator View |
| `ChallengeWindowOpened` | Room | Card played leaving player with 1 card | `roomId`, `targetPlayerId`, `expiresAt` | Spectator View |
| `ChallengeWindowClosed` | Room | 5s timeout or next turn starts | `roomId`, `targetPlayerId`, `reason` | Spectator View |
| `UnoChallengeIssued` | Room | `ChallengeUno` accepted | `roomId`, `challengerId`, `targetPlayerId` | Spectator View |
| `UnoChallengeResolved` | Room | Challenge evaluated | `roomId`, `challengerId`, `targetPlayerId`, `challengeSucceeded`, `penaltyPlayerId`, `penaltyCardCount` | Spectator View |
| `TurnSkipped` | Room | Player's turn is skipped | `roomId`, `skippedPlayerId`, `nextPlayerId`, `reason: disconnection \| skip_card \| draw_two \| wild_draw_four \| reverse_2p \| first_card_effect` | Spectator View |
| `DeckRecycled` | Room | Draw pile exhausted | `roomId`, `newDeckSize` (no card order exposed) | Spectator View |
| `PlayerDisconnected` | Room | Connection lost detected | `roomId`, `playerId`, `reconnectionDeadline` | Spectator View |
| `PlayerReconnected` | Room | `ReconnectPlayer` accepted | `roomId`, `playerId` | Spectator View |
| `PlayerForfeited` | Room | Forfeit triggered | `roomId`, `playerId`, `reason`, `isTournament` | Spectator View, Tournament Orch. |
| `GameCompleted` | Room | Last card played or last active player standing | `roomId`, `gameNumber`, `finishingOrder`, `cardPointTotals`, `completedAt`, `isAbandoned` | Ranking (if casual + not abandoned), Tournament Orch., Analytics |
| `MatchCompleted` | Room | Tournament room: best-of-3 resolved or all 3 games played | `roomId`, `matchResults: Map<PlayerId, MatchScore>`, `advancingPlayers` | Tournament Orch., Analytics |
| `RoomCompleted` | Room | Casual: single game ends. Tournament: match ends. | `roomId`, `finalResults`, `roomType` | Tournament Orch., Analytics |
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

## 4.5 Spectator View Context

### Commands

| Command | Issuer | Target | Preconditions | Idempotency |
|---------|--------|--------|---------------|-------------|
| `JoinAsSpectator` | User | SpectatorRoomView | Room exists; user authenticated; spectator cap not reached (if configured) | Idempotent (re-joining same room = no-op) |
| `LeaveAsSpectator` | User | SpectatorRoomView | User is currently spectating the room | Idempotent (leaving when not spectating = no-op) |

### Domain Events

| Event | Triggered After | Key Payload | Downstream Consumers |
|-------|-----------------|-------------|---------------------|
| `SpectatorJoined` | `JoinAsSpectator` accepted | `roomId`, `spectatorId`, `spectatorCount` | Analytics |
| `SpectatorLeft` | `LeaveAsSpectator` accepted | `roomId`, `spectatorId`, `spectatorCount` | Analytics |

> Spectator commands are handled by the Spectator View context directly. They do **not** affect game state in the Room Gameplay context. The Spectator View context maintains its own count of connected spectators per room.

---

## 4.6 Causality Map (Event Chains)

```
Player sends PlayCard
  → Room accepts → CardPlayed 🟠
      → if card is Reverse → DirectionReversed 🟠
          → if 2-player game → TurnSkipped { reason: reverse_2p } 🟠
      → if card is Skip → TurnSkipped { reason: skip_card } 🟠
      → if card is Draw Two → ForcedDraw { count: 2 } 🟠 → TurnSkipped { reason: draw_two } 🟠
      → if card is Wild Draw Four → ForcedDraw { count: 4 } 🟠 → TurnSkipped { reason: wild_draw_four } 🟠
      → if player has 1 card left → ChallengeWindowOpened 🟠
      → if player has 0 cards → GameCompleted 🟠
          → 🟡 Policy: Is this the last game (casual: always; tournament: game 3 or outcome decided)?
              → Yes → MatchCompleted 🟠 (tournament only)
                  → 🟡 Policy: RoomCompleted 🟠
                      → if tournament room:
                          → 🟡 RecordRoomResult → RoomResultRecorded 🟠
                              → 🟡 All rooms done? → AdvanceRound → RoundCompleted 🟠
                                  → 🟡 ≤ 10 players? → FinalRoomCreated 🟠
                                  → 🟡 > 10 players? → RoundStarted 🟠 (next round)
                      → if casual room:
                          → RoomCompleted 🟠
                          → 🟡 UpdateElo → EloUpdated 🟠 (if not abandoned)
              → No (tournament, more games left) → StartGame (next game in match)

Turn timer expires (connected player inactive for 30s)
  → TurnTimedOut 🟠
      → 🟡 Auto-draw if player hasn't drawn → CardDrawn 🟠
      → 🟡 Auto-pass → TurnPassed 🟠

Player disconnects
  → PlayerDisconnected 🟠
      → 🟡 60s timer starts
      → On each turn: TurnSkipped { reason: disconnection } 🟠
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

## 4.7 Idempotency Strategy Summary

| Mechanism | Applied To | How It Works |
|-----------|-----------|--------------|
| **Sequence number** | All Room mutations | Command carries expected seq. Mismatch → 409 Conflict. Match → accept + increment. Guarantees exactly-once within a room. |
| **Idempotency key** | `CreateRoom`, `CreateTournament` | Client-generated UUID. If a room/tournament with that key already exists, return the existing entity. |
| **Event ID deduplication** | Cross-context event consumers (Ranking, Tournament, Analytics) | Each consumer tracks the last processed event ID per source. Re-delivered events with known IDs are skipped. |
| **Status guard** | `RegisterPlayer`, `StartTournament`, etc. | Commands check current aggregate status. If the operation has already been performed (e.g., tournament already started), return success without re-applying. |
| **Challenge window state** | `ChallengeUno` | Only the first valid challenge per window is accepted. Subsequent challenges during the same window are no-ops. |
