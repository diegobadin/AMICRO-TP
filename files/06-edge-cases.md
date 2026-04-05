# 6 — Edge Cases & Failure-Path Analysis

---

## 6.1 Concurrent Conflicting Actions

### 6.1.1 Two players simultaneously play a card

**Scenario:** Player A (current turn) and Player B (not their turn) both send `PlayCard` commands at the same instant.

**Expected behavior:**
- The Room aggregate serializes all commands via sequence numbers.
- Player A's command carries the correct `sequenceNumber` → **accepted**. Seq increments.
- Player B's command is rejected for two independent reasons:
  1. It is not B's turn (turn enforcement invariant).
  2. If B somehow guessed the next seq, it would still fail the turn check.
- B receives HTTP 409 Conflict with the current sequence number.
- **Event emitted:** `CardPlayed` (A's), `StaleCommandRejected` (B's, internal/client only).

**Why this is safe:** The Room aggregate is the single writer. No optimistic concurrency needed — strict sequence numbers make the order deterministic.

---

### 6.1.2 Two opponents challenge Uno simultaneously

**Scenario:** Player C and Player D both send `ChallengeUno` targeting Player A within the 5-second window.

**Expected behavior:**
- First challenge to be serialized by the Room aggregate is **accepted**, closing the challenge window.
- Second challenge arrives with the challenge window already closed → **rejected** as a no-op (idempotent — not an error, simply returns current state).
- Only one `UnoChallengeIssued` + `UnoChallengeResolved` pair is emitted.

---

### 6.1.3 Player plays a card while a challenge window is open for another player

**Scenario:** A challenge window is open for Player A. Player B (next turn) sends `PlayCard`.

**Expected behavior:**
- Accepting B's play **closes** A's challenge window immediately (challenge window invariant: closes when next player acts).
- `ChallengeWindowClosed { reason: next_turn_started }` is emitted before `CardPlayed`.
- Any `ChallengeUno` commands that arrive after this are rejected as no-ops.

---

### 6.1.4 Race between challenge and window timeout

**Scenario:** A `ChallengeUno` command arrives at T+4.99s, and the 5-second timer fires at T+5.0s.

**Expected behavior:**
- The Room aggregate serializes both: whichever is processed first wins.
- If the challenge is processed first → challenge resolves, window closes with `reason: challenge_resolved`.
- If the timer fires first → window closes with `reason: timeout`, and the subsequent challenge is rejected (window already closed).
- The 5-second timer is a **domain concept**, not a distributed systems timer. The aggregate evaluates the current server time against `expiresAt` when processing the challenge command. If `now > expiresAt`, the challenge is rejected regardless of arrival time.

---

## 6.2 Disconnections and Late Rejoin Attempts

### 6.2.1 Player disconnects on their turn

**Expected behavior:**
- `PlayerDisconnected` emitted with `reconnectionDeadline = now + 60s`.
- Since it is the player's turn: their turn is **immediately skipped** (as if passed). `TurnSkipped` emitted.
- Turn advances to the next connected player.
- If the player reconnects within 60s, they resume on their next turn with hand intact.
- If the 60s window expires: `ForfeitPlayer` is triggered.

---

### 6.2.2 Late rejoin (after 60-second window expires)

**Scenario:** Player B reconnects at T0+65s (5 seconds after the window expired).

**Expected behavior:**
- By T0+60s, `PlayerForfeited { playerId: B }` has already been emitted.
- `ReconnectPlayer` command is **rejected**: B's status is `Forfeited`, not `Disconnected`.
- B receives an error indicating the reconnection window has expired.
- B's hand is discarded (cards returned to the deck or removed from play, depending on game state).

---

### 6.2.3 All players disconnect simultaneously

**Scenario:** In a 3-player room, all three players disconnect within seconds of each other.

**Expected behavior:**
- Three independent `PlayerDisconnected` events, each with their own 60-second timer.
- Turns are skipped for all players (the game effectively pauses — each turn cycles through all players and skips them all).
- If all timers expire: all players forfeit sequentially. The last player to forfeit "wins" only if at least one other player has already forfeited (last standing). If all forfeit simultaneously → **abandoned game** (no winner, no Elo change).
- Tournament: all players receive a loss.

---

### 6.2.4 Session invalidation during active game

**Scenario:** Player B logs in from a new device while in an active game.

**Expected behavior:**
- Identity context emits `SessionInvalidated { oldSessionId }`.
- Room Gameplay context detects the old session is invalid → treats it as a **disconnection** (not an immediate forfeit).
- `PlayerDisconnected` emitted, 60-second timer starts.
- B's new session can issue `ReconnectPlayer` with the new session token to resume.
- If B doesn't reconnect within 60s, forfeit as normal.

---

## 6.3 Stale Commands and Replayed Commands

### 6.3.1 Stale command (wrong sequence number)

**Scenario:** Player A's client has seq=15 but the room is at seq=17 (A missed two updates).

**Expected behavior:**
- Command rejected with HTTP 409 Conflict.
- Response includes current `sequenceNumber` (17) and latest game state.
- Client reconciles state via SSE event stream and retries with correct seq.
- No domain event emitted (except internal `StaleCommandRejected` for monitoring).

---

### 6.3.2 Replayed command (exact duplicate)

**Scenario:** Due to network retry, the exact same `PlayCard` command (same seq, same card) is sent twice.

**Expected behavior:**
- First copy: accepted, seq increments to N+1.
- Second copy: rejected with 409 (seq is now N+1, command carries N).
- The game log contains only one entry. Exactly-once semantics are preserved.

---

### 6.3.3 Replayed cross-context event

**Scenario:** `GameCompleted` event is delivered twice to the Ranking context (at-least-once delivery).

**Expected behavior:**
- First delivery: Ranking processes the event, updates Elo, records `gameId` as processed.
- Second delivery: Ranking checks the deduplication log, finds `gameId` already processed → **skips silently**.
- No duplicate Elo changes applied.

---

## 6.4 Partial Failures Between Contexts

### 6.4.1 Room completes but Tournament context is temporarily unavailable

**Scenario:** `MatchCompleted` is emitted by a tournament room, but the Tournament Orchestration service is down.

**Expected behavior:**
- The event is persisted in the event store / message bus (durable delivery).
- When Tournament Orchestration recovers, it processes the event from its last checkpoint.
- Room state is already `Completed` and immutable — no inconsistency risk.
- Tournament advancement is **delayed** but not lost.
- Idempotency guard: `RecordRoomResult` checks if the room result was already recorded. Re-delivery is safe.

---

### 6.4.2 Elo update partially fails (some players updated, others not)

**Scenario:** After a 4-player game, Elo is updated for players A and B, but the process crashes before updating C and D.

**Expected behavior:**
- Each `UpdateElo` command targets a separate `PlayerRating` aggregate — they are independent.
- On recovery, the Ranking processor re-reads the `GameCompleted` event.
- For A and B: deduplication check → already processed → skip.
- For C and D: not yet processed → apply Elo delta normally.
- **Result:** Eventually consistent. Each player's Elo is updated exactly once.

---

### 6.4.3 Room creation requested by Tournament but Room Gameplay fails to create

**Scenario:** Tournament emits `RoomCreationRequested` but Room Gameplay rejects it (e.g., invalid player count due to a bug).

**Expected behavior:**
- Room Gameplay emits `RoomCreationFailed { reason }`.
- Tournament Orchestration's policy reacts: logs the failure, alerts operators, and may retry with corrected parameters.
- If retries fail: the tournament round is **stuck** (cannot advance). This is a critical operational alert, not an automatic recovery — human intervention required.
- The tournament aggregate remains in `InProgress` state and does not advance until all rooms are resolved.

---

### 6.4.4 Spectator View projection falls behind

**Scenario:** High event volume causes the Spectator View projection to lag by 30 seconds.

**Expected behavior:**
- **No impact on game correctness.** The Spectator View is a read model; it does not participate in game decisions.
- Spectators see a delayed view. The projection catches up as load decreases.
- If the lag exceeds an acceptable threshold (configurable), the system can drop intermediate events and rebuild the spectator projection from a snapshot of the current room state (public fields only).

---

## 6.5 Security and Abuse Scenarios

### 6.5.1 Session takeover attempt

**Scenario:** An attacker obtains a player's old session token and attempts to issue commands.

**Expected behavior:**
- Session tokens are time-bounded and signed. Expired tokens are rejected at the Identity context gateway.
- If the token is still valid but the player has since logged in (new session), the old token is invalidated → 401 Unauthorized.
- Even if the attacker somehow presents a valid token, the single-active-session invariant ensures the legitimate player's new login invalidated the old session.
- **Audit:** `SuspiciousSessionUsage` event logged for security monitoring.

---

### 6.5.2 Command spam / flooding

**Scenario:** A malicious client sends 1,000 `PlayCard` commands per second to a room.

**Expected behavior:**
- **Layer 1 (per-IP rate limit):** Rejects requests exceeding the IP-level threshold (e.g., 50 req/s). Returns HTTP 429.
- **Layer 2 (per-user rate limit):** Even if the attacker rotates IPs, the per-user limit (e.g., 10 game actions/s) catches the abuse.
- **Layer 3 (per-room rate limit):** The room itself rejects commands that exceed a sane action rate. Since only one command per sequence number is accepted, most spam commands fail with 409.
- **Adaptive throttling:** Repeated 429s trigger increasingly aggressive throttling and eventual temporary IP/user ban.
- **No game impact:** The Room aggregate only processes valid, correctly sequenced commands. Spam is rejected before reaching the aggregate.

---

### 6.5.3 Spectator attempting to read another player's hand

**Scenario:** A spectator (or a player posing as a spectator) crafts a request to access hand data.

**Expected behavior:**
- The Spectator View context **does not store hand data at all**. There is no API endpoint that returns hand information.
- Even if an attacker queries the Room Gameplay context directly, hand data is only returned to the authenticated player who owns that hand. Authorization middleware enforces: `request.playerId == hand.playerId`.
- The Public Event Publisher (within Room Gameplay) strips hand data before events cross the context boundary. The Spectator View ACL validates no hand data is present.
- **Defense in depth:** Three independent barriers prevent hand data leakage:
  1. Publisher strips data at source.
  2. ACL validates at boundary.
  3. Spectator View has no hand data in its storage.

---

### 6.5.4 Player using spectator channel to gain information

**Scenario:** Player A opens a spectator connection to their own room, hoping to see opponents' hands.

**Expected behavior:**
- The spectator channel shows the same `PublicGameState` for all viewers, including participants. No private data is available.
- Player A gains no advantage — they already see everything the spectator channel shows, plus their own hand (which they already know).
- This is explicitly allowed and harmless by design.

---

### 6.5.5 Coordinated collusion between players

**Scenario:** Two players in a room communicate externally, sharing hand information to gain an unfair advantage.

**Expected behavior:**
- The platform **cannot prevent external communication** between players.
- However, the game log captures all actions. Post-hoc analysis can detect statistical anomalies (e.g., suspiciously optimal play patterns, always avoiding specific cards).
- Flagged games can be reviewed by administrators using the immutable game log.
- For tournament play: flagged matches can result in disqualification (a human moderation decision, not an automatic domain event).

---

### 6.5.6 Tournament registration spam

**Scenario:** An attacker registers 100,000 bot accounts for a tournament.

**Expected behavior:**
- **Per-IP rate limit** on registration: limits account creation velocity from a single source.
- **Authentication requirements:** Registration may require email verification, CAPTCHA, or similar (Identity context responsibility).
- **Tournament registration limit:** The Tournament aggregate enforces `maxPlayers`. Excess registrations are rejected.
- **Adaptive throttling:** Unusual registration surge triggers alerts and may pause registration pending review.

---

### 6.5.7 Timing attack on Uno challenge

**Scenario:** A player writes a bot that automatically challenges every Uno call within milliseconds.

**Expected behavior:**
- This is technically **legal gameplay** — the rules allow any opponent to challenge within the 5-second window.
- Per-user rate limits on `ChallengeUno` prevent spamming (e.g., max 1 challenge per window, which is already enforced by the challenge window state).
- The challenge is evaluated correctly: if the target called Uno, the bot challenger draws 2 penalty cards. Automated challenges are self-penalizing when wrong.

---

## 6.6 Turn Timer & Inactivity

### 6.6.1 Connected player does not act within the turn timer

**Scenario:** Player A is connected but does not play a card, draw, or pass within 30 seconds.

**Expected behavior:**
- The turn timer expires. The Room aggregate emits `TurnTimedOut { playerId: A, autoAction: draw_and_pass }`.
- The system automatically draws a card for A (if A hasn't drawn this turn) and passes the turn. The drawn card is added to A's hand but never revealed to other players or spectators (only `CardDrawn { newCardCount }` is emitted publicly).
- Turn advances to the next player. The turn timer resets for the new current player.
- Repeated timeouts are not penalized beyond the auto-pass — a player who times out multiple times is not forfeited (they are still connected). However, per-user rate-limit monitoring may flag patterns of intentional stalling for admin review.
- **In tournament rooms**, repeated timeouts may use a stricter policy (e.g., 3 consecutive timeouts trigger forfeit). This is configurable per tournament — see Open Questions.

---

### 6.6.2 Turn timer interacts with challenge window

**Scenario:** Player B is the next player. A challenge window is open for Player A (5s). B's turn timer has not started yet because B's turn hasn't officially begun.

**Expected behavior:**
- The turn timer for the next player **does not start until the challenge window closes** (or until the next player actively begins their turn, which also closes the challenge window).
- If B plays a card during the challenge window (closing it early), B's turn timer was never relevant — B acted proactively.
- If the challenge window times out (5s), B's turn timer starts at that point.

---

## 6.7 Action Card Edge Cases

### 6.7.1 Reverse card in a 2-player game

**Scenario:** Player A plays a Reverse card in a 2-player game (A vs. B).

**Expected behavior:**
- In a 2-player game, Reverse acts as **Skip** — the direction reversal causes the turn to return to Player A instead of advancing to B.
- Events emitted: `CardPlayed`, `DirectionReversed`, `TurnSkipped { skippedPlayerId: B, reason: reverse_2p }`.
- Player A takes another turn immediately.
- This is consistent with official UNO rules for 2-player games.

---

### 6.7.2 Draw Two / Wild Draw Four when deck is nearly empty

**Scenario:** Player A plays a Draw Two, but the deck has only 1 card remaining. Player B must draw 2 cards.

**Expected behavior:**
- B draws the 1 remaining card from the deck.
- The deck is exhausted → `DeckRecycled` event fires: the discard pile (except the top card) is shuffled with a new seed and becomes the new deck.
- B draws the second card from the recycled deck.
- All three events (`ForcedDraw`, `DeckRecycled`, continuation of draw) are processed atomically within a single command processing cycle.
- B's turn is still skipped after the forced draw completes.

---

### 6.7.3 Initial discard card is an action card

**Scenario:** After dealing, the first card placed on the discard pile is a Draw Two.

**Expected behavior (per First Card Rule):**
- **Draw Two:** First player draws 2 cards and their turn is skipped. `ForcedDraw` + `TurnSkipped { reason: first_card_effect }` emitted at game start.
- **Skip:** First player's turn is skipped. `TurnSkipped { reason: first_card_effect }` emitted.
- **Reverse:** Direction is set to counter-clockwise. In 2-player, first player is skipped. `DirectionReversed` emitted.
- **Wild:** First player chooses the active color (their turn timer applies). No other effect.
- **Wild Draw Four:** This card is **buried** back into the deck and a new initial discard is drawn. This prevents the first player from suffering an unfair 4-card penalty before any gameplay. If the replacement card is also a Wild Draw Four, it is buried again (repeat until a non-WD4 card appears).

---

## 6.8 Additional Edge Cases

### 6.8.1 Room reaches 1 active player (all others forfeited)

**Expected behavior:**
- The last remaining player wins the current game by default.
- `GameCompleted` emitted with finishing order based on forfeit timestamps.
- In a **casual room** (single game): `RoomCompleted` is emitted immediately.
- In a **tournament room** mid-match (e.g., game 1 of 3): the match ends immediately — the last active player wins the match. `MatchCompleted` + `RoomCompleted` emitted. Remaining games are not played.
- Elo (casual only): if all forfeits were due to disconnection, this is **not** an abandoned game (one player remained and "won"). Elo is updated normally.
- An abandoned game is specifically when **all** remaining players forfeit — meaning zero players are left.

---

### 6.8.2 Deck exhaustion during heavy draw phase

**Scenario:** Multiple Draw Two / Wild Draw Four cards chain, and the deck runs out mid-draw.

**Expected behavior:**
- When the deck is exhausted, the `DeckRecycled` operation fires: discard pile (except top card) is shuffled with a new server-generated seed and becomes the new deck.
- This is atomic within the draw operation — the player's draw is paused, deck is recycled, draw continues.
- If even after recycling the deck is too small (extremely rare edge case with many players), remaining draws are fulfilled as much as possible. Any unfulfilled draws are logged and skipped.
- `DeckRecycled` event is emitted and appended to the game log.

---

### 6.8.3 Tournament tiebreaker: all values identical

**Scenario:** Two players have identical match wins, identical cumulative card-point totals, and identical final-game completion times (down to the millisecond).

**Expected behavior:**
- The assignment specifies three tiebreakers: (1) match wins, (2) cumulative card-point total, (3) earliest final-game completion time.
- Completion times are recorded with millisecond precision, making true ties extremely unlikely.
- If a tie persists after all three tiebreakers: **open question** — see Section 8. Current assumption: the player with the lower `playerId` (lexicographic) advances. This is deterministic and auditable.

---

### 6.8.4 Player plays Wild Draw Four illegally (has a matching color card)

**Scenario:** Under official Uno rules, a Wild Draw Four may only be played when the player has no cards matching the current color. An opponent may challenge this.

**Expected behavior:**
- **Current scope assumption:** The domain model does not enforce the "Wild Draw Four legality" rule or its challenge mechanic, as the assignment's Uno call mechanics focus on the "Uno!" call challenge only.
- This is listed as an **open question** (see Section 8). If added, it would be a separate challenge type with its own window and penalty logic within the Room aggregate.

---

### 6.8.5 Player joins a room but never starts a game

**Scenario:** A room stays in `Waiting` state indefinitely because no one starts the game.

**Expected behavior:**
- A domain-level inactivity timeout applies to rooms in `Waiting` state (e.g., 10 minutes).
- After timeout: `RoomExpired` event emitted, room status → `Completed` with no games played.
- No Elo impact (no games completed).
- Tournament rooms do not have this issue — the `StartGame` command is issued automatically by the system.
