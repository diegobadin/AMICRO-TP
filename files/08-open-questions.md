# 8 — Open Questions & Assumptions

---

## 8.1 Assumptions (Treated as Validated for This Iteration)

These assumptions were made to complete the domain model. They should be validated with stakeholders before implementation.

### 8.1.1 Connection Semantics Assumptions

> Per Section 3 scope constraints: "You may state explicit assumptions about connection semantics without designing the protocol."

| ID | Assumption | Rationale |
|----|-----------|-----------|
| **CS-1** | **At-least-once event delivery** between bounded contexts. Consumers are responsible for idempotent processing. | Standard for event-driven systems. Exactly-once is impractical across context boundaries without distributed transactions. |
| **CS-2** | **Ordered delivery per room**: events from a single Room aggregate are delivered to consumers in sequence-number order. Cross-room ordering is not guaranteed. | Spectator View and other consumers need to process events in the order they occurred within a room. Cross-room ordering is irrelevant. |
| **CS-3** | **Server-authoritative time**: all timestamps (challenge windows, reconnection deadlines, game completion times) are based on server clock, not client clocks. | Prevents client-side time manipulation. Clients may display local-time approximations, but domain decisions use server time exclusively. |
| **CS-4** | **Client-to-server: request/response for commands** (REST-like). **Server-to-client: push-based event stream** (SSE-like) for real-time updates. | Commands are serialized and acknowledged individually. State updates are pushed so clients don't poll. |
| **CS-5** | **Disconnection detection** is handled by the transport layer (e.g., heartbeat/ping-pong mechanism). The domain layer receives a `PlayerDisconnected` signal when the transport detects a lost connection. | The domain model does not define how disconnection is detected — only how it reacts. |
| **CS-6** | **Reconnection** requires presenting a valid (refreshed or original) session token. The domain verifies identity and room membership, not connection-layer details. | Prevents impersonation during reconnection. |

### 8.1.2 Game Rule Assumptions

| ID | Assumption | Rationale |
|----|-----------|-----------|
| **GR-1** | A standard 108-card Uno deck is used (no custom or expansion cards). | Assignment references standard Uno rules. |
| **GR-2** | The initial discard card determines the starting play constraint. If the first card is an action card (Skip, Reverse, Draw Two) or Wild, its effect applies to the first player. If Wild Draw Four, it is buried and a new card is drawn (standard Uno rule). | Standard Uno dealing rule. Prevents unfair first-turn penalties. |
| **GR-3** | "Stacking" Draw Two on Draw Two (or Wild Draw Four on Wild Draw Four) is **not** allowed. Each draw card affects only the next player. | The assignment does not mention stacking; we default to official UNO rules where stacking is not permitted. |
| **GR-4** | A player who draws a card and can play it **may** play it immediately on the same turn (optional). If they cannot or choose not to play, they pass. | Standard Uno rule. |
| **GR-5** | When a player goes out (plays their last card), the game ends immediately. Card-point totals are calculated from remaining players' hands. | Standard scoring variant. |
| **GR-6** | The Uno call can be bundled with the `PlayCard` command (via a `callingUno` flag) or sent as a separate `CallUno` command within the challenge window. Both are valid. | Improves UX by allowing atomic play-and-call in a single request, while also supporting a separate call for edge cases. |

### 8.1.3 Tournament Assumptions

| ID | Assumption | Rationale |
|----|-----------|-----------|
| **TA-1** | Player seeding into rooms is **random** within each round (not Elo-based). | Simplest fair approach. Elo-based seeding is listed as an open question. |
| **TA-2** | Tournament registration has a **fixed deadline** (time-based), after which no new players can join. | Standard tournament structure. |
| **TA-3** | If a tournament room has fewer than 10 players (due to uneven division in later rounds), the match still uses best-of-three, and the top 3 advance (or all non-forfeited players if fewer than 3 remain). | Handles non-power-of-10 player counts gracefully. |
| **TA-4** | A round with an odd player distribution (e.g., 11 remaining players) creates one room of 10 and one room of 1. The solo player advances automatically (bye). | A "bye" mechanic is needed when player counts don't divide evenly. Alternative: rooms of variable size (e.g., 6 and 5). See open question. |
| **TA-5** | Tournament room games start automatically once all assigned players have connected (or after a reasonable connection timeout). | Tournament rooms don't wait for a human "start" command. |
| **TA-6** | If a registered player fails to connect to their tournament room within the connection timeout, they are treated as an immediate forfeit for that match. | Prevents stalling the entire tournament. |

### 8.1.4 Elo Assumptions

| ID | Assumption | Rationale |
|----|-----------|-----------|
| **EL-1** | The Elo calculation uses a multiplayer variant where each pair of players in the room contributes to the expected score. The delta is based on the player's finishing position vs. their expected position given all ratings. | Standard multiplayer Elo extension. Specific K-factor and formula are implementation details. |
| **EL-2** | New players start with an Elo of 1200. | Common default in Elo systems. |
| **EL-3** | There is no Elo floor or ceiling. | Simplest model. Floors/ceilings can be added later if needed. |

### 8.1.5 Security Assumptions

| ID | Assumption | Rationale |
|----|-----------|-----------|
| **SE-1** | All client-server communication occurs over TLS. | Baseline security requirement. |
| **SE-2** | Session tokens are JWTs (or similar signed tokens) with a configurable expiry (e.g., 24 hours), refreshable. | Standard auth pattern. |
| **SE-3** | Rate limits are configurable per action type and can be adjusted without code changes. | Operational flexibility for tuning against abuse. |
| **SE-4** | The game log's HMAC key is managed server-side and rotated periodically. Players cannot forge log entries. | Ensures integrity of the audit trail. |

---

## 8.2 Open Questions (Require Stakeholder Decision)

### Game Rules

| ID | Question | Options | Impact |
|----|----------|---------|--------|
| **OQ-1** | Should the "Wild Draw Four challenge" mechanic be implemented? (Challenger can force the player to reveal their hand to verify they had no matching-color cards.) | A) Yes, full mechanic. B) No, Wild Draw Four is always legal. | If yes: adds a new challenge type, a temporary hand-reveal to the challenger only (privacy implications), and a separate penalty flow. Significant complexity increase in the Room aggregate. |
| **OQ-2** | Should "jump-in" rules be supported? (A player with an identical card can play out of turn.) | A) Yes. B) No, strict turn order only. | If yes: fundamentally changes turn serialization. Any player could play at any time if they hold a matching card. Major impact on concurrency model. |
| **OQ-3** | How are card points calculated when a game ends: only the winner's score (sum of opponents' remaining cards) or per-player individual remaining? | A) Winner accumulates points (official rule). B) Each player keeps their own total (used for tiebreaking). | The assignment mentions "card-point total" for tiebreaking. We assume option B for tournament advancement. Needs clarification for casual play scoring. |

### Tournament Structure

| ID | Question | Options | Impact |
|----|----------|---------|--------|
| **OQ-4** | How should uneven player counts be handled in round generation? | A) Byes (solo player auto-advances). B) Variable room sizes (e.g., rooms of 8 and 9 instead of 10 and 1). C) Hybrid (prefer full rooms, use byes only when remainder = 1). | Affects fairness. Byes give an advantage (rest time, no risk). Variable room sizes are fairer but complicate advancement logic (top 3 from a 5-player room vs. a 10-player room). |
| **OQ-5** | Should tournament seeding be random or Elo-based? | A) Random (current assumption). B) Elo-based (higher-rated players are separated). C) Swiss-system (pair similarly-rated players). | Elo-based seeding is fairer at the top level but requires access to Elo data from the Ranking context (cross-context dependency at round generation time). |
| **OQ-6** | What is the connection timeout for tournament room players? (How long to wait for all assigned players to connect before starting the game.) | A) 60 seconds (same as reconnection window). B) 120 seconds (more lenient for round transitions). C) Configurable per tournament. | Too short: penalizes players on slow connections between rounds. Too long: delays the entire round. |
| **OQ-7** | Should there be a "warm-up" period between tournament rounds? (e.g., 5 minutes for players to reconnect and prepare.) | A) Yes, configurable. B) No, next round starts immediately. | Impacts UX for large tournaments. Without warm-up, players advancing from a quick room may wait a long time for others, while players from a long room are thrust immediately into the next round. |
| **OQ-8** | What happens if a tournament round cannot complete? (e.g., all players in a room disconnect and forfeit, leaving no advancers.) | A) The room contributes 0 advancers; round still completes. B) The tournament is paused pending admin review. | If many rooms produce 0 advancers, the tournament may collapse too quickly. Admin intervention may be preferred for high-stakes tournaments. |

### Ranking & Elo

| ID | Question | Options | Impact |
|----|----------|---------|--------|
| **OQ-9** | What K-factor should the Elo calculation use? Should it vary by player experience (higher K for new players)? | A) Fixed K (e.g., 32). B) Variable K (e.g., 40 for first 30 games, then 20). | Variable K helps new players reach their true rating faster. |
| **OQ-10** | How is tournament placement rating calculated? | A) Based on final placement (champion gets most points). B) Based on round reached (later rounds = more points). C) Hybrid. | Affects player motivation and tournament value perception. |
| **OQ-11** | Should Elo changes be visible to players in real-time after each game? | A) Yes, immediately. B) Yes, after a short delay (eventual consistency). C) Only visible on profile page. | Option A requires the Ranking context to update faster. Option C is simplest. |

### Platform & UX

| ID | Question | Options | Impact |
|----|----------|---------|--------|
| **OQ-12** | Can a player be in multiple casual rooms simultaneously? | A) Yes. B) No, one active room at a time. | If yes: a player may need to manage multiple turn timers. The single-session constraint still applies (one connection), but the player could switch between rooms. If no: simpler model, but limits engagement. Current model assumes one active room per player. |
| **OQ-13** | Should there be a "quick play" matchmaking system that automatically creates and fills rooms? | A) Yes, separate matchmaking service. B) No, only manual room creation. | If yes: introduces a new bounded context (Matchmaking) that creates rooms and assigns players based on Elo, queue time, etc. Significant additional domain complexity. |
| **OQ-14** | What is the maximum spectator count per room? | A) Unlimited (handled by scaling). B) Capped (e.g., 10,000 per room). | Impacts the Spectator View context's scaling requirements. High-profile tournament final rooms may attract enormous spectator loads. |
| **OQ-15** | Should the platform support "rematch" (same players, new room) in casual mode? | A) Yes, one-click rematch. B) No, create a new room manually. | If yes: a convenience command that creates a new room pre-populated with the same players. Minimal domain impact (syntactic sugar over `CreateRoom` + `JoinRoom`). |
| **OQ-16** | What is the exhaustive tiebreaker if all three criteria (match wins, card-point total, completion time) are identical? | A) Lower playerId (lexicographic, deterministic). B) Coin flip (random, non-deterministic but fair). C) Both players advance (increases next-round room size). | Current assumption is option A for determinism. Stakeholders may prefer option C for fairness. |
