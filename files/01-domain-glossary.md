# 1 — Domain Glossary (Ubiquitous Language)

> Every term below is used consistently across all deliverables. When a term appears in commands, events, or aggregate names, it carries exactly the meaning defined here.

---

## 1.1 Core Game Concepts

| Term | Definition |
|------|-----------|
| **Card** | A value object representing one Uno card, identified by its color (Red, Yellow, Green, Blue, Wild) and face (0–9, Skip, Reverse, Draw Two, Wild, Wild Draw Four). |
| **Deck** | The server-authoritative, ordered collection of cards from which players draw. Composed from a standard 108-card Uno set. Shuffled by the Authoritative RNG service at game start and whenever the draw pile is exhausted (discard pile is recycled). |
| **Discard Pile** | The ordered stack of cards that have been played. The top card defines the current play constraint (color and/or face). Public information visible to all players and spectators. |
| **Hand** | The private set of cards held by a single player. Hands are **never** exposed to other players or spectators. |
| **Play Constraint** | The rule derived from the top discard card and the active color that determines which cards are legally playable (matching color, matching face, or Wild). |
| **Turn** | The atomic unit of play in which exactly one player must perform an action: play a valid card, draw from the deck, or (if unable to play) pass after drawing. Subject to the **Turn Timer**. |
| **Turn Order** | The circular sequence of active (non-forfeited) players, maintained as a directed ring. Initially clockwise; reversed by Reverse cards. Forfeited or eliminated players are removed from the ring without altering the relative order of remaining players. |
| **Direction** | Current traversal direction of the turn order ring: `Clockwise` or `CounterClockwise`. Toggled by Reverse cards. |
| **First Card Rule** | After dealing, the top card of the deck is placed on the discard pile to start play. If it is an action card, its effect applies immediately to the first player: **Skip** → first player is skipped; **Reverse** → direction becomes counter-clockwise (in 2-player, first player is skipped); **Draw Two** → first player draws 2 and is skipped; **Wild** → first player chooses the active color (or a random color is assigned on timeout); **Wild Draw Four** → the card is buried in the deck and a new card is drawn for the initial discard. |

## 1.2 Action Card Effects

| Card | Effect When Played |
|------|-------------------|
| **Skip** | The next player in turn order loses their turn and play passes to the following player. In a **2-player game**, this effectively grants the playing player another consecutive turn. |
| **Reverse** | Reverses the direction of play (`Clockwise` ↔ `CounterClockwise`). In a **2-player game**, Reverse acts as a Skip — the playing player takes another consecutive turn. |
| **Draw Two** | The next player must draw 2 cards from the deck and **loses their turn**. The drawn cards are added to their hand automatically by the server. Stacking (playing another Draw Two on top to pass the penalty forward) is **not** permitted per official UNO rules. |
| **Wild** | Can be played on any turn regardless of the active color or face. The playing player **must** declare a new active color as part of the same `PlayCard` command (the `chosenColor` field is mandatory). |
| **Wild Draw Four** | Can be played on any turn. The playing player **must** declare a new active color. The next player must draw 4 cards from the deck and **loses their turn**. Stacking is **not** permitted. Whether the "legality challenge" (opponent can challenge that the player had no matching-color cards) is enforced is an **open question** (see Section 8, OQ-1). |

> **Forced draws** caused by Draw Two and Wild Draw Four are applied atomically by the Room aggregate as part of processing the `PlayCard` command — the affected player does not issue a separate `DrawCard` command. These draws are recorded as `ForcedDraw` events in the game log, distinct from voluntary draws.

## 1.3 Uno Call Mechanics

| Term | Definition |
|------|-----------|
| **Uno Call** | A declaration a player **must** make when playing their second-to-last card (leaving them with exactly one card). Must occur before the next player begins their turn. |
| **Challenge Window** | A 5-second period after a card is played during which any opponent may challenge the Uno call (or lack thereof). Closes early if the next player begins their turn. |
| **Uno Challenge** | An action by an opponent asserting that the current player failed to call "Uno!" when required. If the challenge succeeds, the offending player draws 2 penalty cards. If the challenge fails (player did call Uno), the challenger draws 2 penalty cards. |
| **Penalty Cards** | Cards forcibly added to a player's hand as the result of a failed or successful Uno challenge (always 2 cards). |

## 1.4 Room & Match Concepts

| Term | Definition |
|------|-----------|
| **Room** | The top-level container for a group of 2–10 players. A Room has a lifecycle: `Waiting → InProgress → Completed`. A Room is either *casual* (ad-hoc) or *tournament-linked*. |
| **Casual Room** | A room created by players for ad-hoc play. Plays a **single Game**. Elo changes apply to the game result. No tournament context. |
| **Tournament Room** | A room created by the Tournament Orchestration context to host a specific **Match** within a tournament round. Plays up to 3 Games (best-of-three series). Elo changes do **not** apply; tournament placement rating is affected instead. |
| **Game** | A single Uno game within a room: cards are dealt, play proceeds, and a single winner is determined when one player empties their hand. A game produces a **Finishing Order**. |
| **Match** | A series of up to 3 Games played within a **Tournament Room**. All games are played to give every player a fair chance at accumulating wins (unless the remaining games provably cannot change the advancement outcome). After the match, players are ranked by game wins to determine the top 3 who advance. The concept of "match" does **not** apply to casual rooms. |
| **Match Result** | The final outcome of a match for each player: game wins, and cumulative card-point total across games (used as a tiebreaker for advancement). |
| **Card-Point Total** | The sum of point values of cards remaining in a player's hand when a game ends. Used as a tiebreaker in tournament advancement and to determine Finishing Order among non-winning players. Point values: number cards = face value; Skip/Reverse/Draw Two = 20; Wild/Wild Draw Four = 50. |
| **Finishing Order** | The ranking of all players at the end of a completed game. The player who empties their hand is **1st**. Remaining players are ranked by **ascending card-point total** (fewer points = higher rank). If two players have the same card-point total, the player with **fewer cards remaining** ranks higher. |
| **Sequence Number** | A monotonically increasing integer attached to every state-mutating command accepted by a Room. Used to enforce strict serialization and reject stale commands (HTTP 409). |

## 1.5 Tournament Concepts

| Term | Definition |
|------|-----------|
| **Tournament** | A multi-round elimination event for up to 1,000,000 players. Lifecycle: `Registration → InProgress → Completed`. |
| **Round** | One elimination tier within a tournament. In each round, all remaining players are distributed into rooms of up to 10. After all rooms complete their matches, the top 3 from each room advance to the next round. |
| **Final Room** | Created when 10 or fewer players remain. This is the last room of the tournament; its match result determines the tournament champion and final placements. |
| **Bracket** | The read-optimized structure that tracks which players advanced from which rooms across rounds, enabling bracket visualization. |
| **Advancement** | The act of promoting the top 3 players from a completed tournament room into the next round. Ties are broken first by cumulative card-point total (lower is better), then by earliest final-game completion time. |
| **Seeding** | The assignment of advancing players into rooms for the next round. May be random or Elo-based (see Open Questions). |
| **Tournament Placement Rating** | A rating separate from Elo that reflects a player's performance in tournaments. Updated based on tournament final placement, not individual game results. |

## 1.6 Ranking

| Term | Definition |
|------|-----------|
| **Elo Rating** | A numerical skill rating for casual (ad-hoc) play. Updated once per completed **Game** (not per match, not per tournament). The Elo delta is derived from a player's finishing order within the room relative to all other players in that room. |
| **Elo Delta** | The change applied to a player's Elo after a game. Positive for players who outperformed their expected rank; negative otherwise. |
| **Abandoned Game** | A game in which all remaining players forfeit. Abandoned games produce **no Elo changes**. |

## 1.7 Connection & Session

| Term | Definition |
|------|-----------|
| **Session** | A single authenticated connection binding a player to the platform. Single-active-session policy: a new login invalidates any prior session. |
| **Reconnection Window** | A 60-second grace period after a player disconnects. During this window, turns are skipped (as if passed) and the player is not replaced. |
| **Inactive Player** | A player whose reconnection window has expired without reconnection. Triggers automatic forfeit. |
| **Forfeit** | The forced removal of a player from a game. In a casual room, the player's participation ends and the game continues. In a tournament room, the forfeit counts as a match loss and the player is eliminated. |
| **Turn Skip (Disconnection)** | When a disconnected player's turn arrives, it is automatically skipped as if the player passed. No bot substitution occurs. |
| **Turn Timer** | A configurable time limit for a connected player to act on their turn (default: 30 seconds). If the timer expires without a player action, the system automatically draws a card (if the player has not yet drawn this turn) and passes the turn. Prevents connected-but-inactive players from stalling the game. In tournament rooms, a shorter or longer timer may be configured per tournament. |

## 1.8 Spectator

| Term | Definition |
|------|-----------|
| **Spectator (Observer)** | A user watching a room without participating. Spectators see only **public information**: player names, card counts per player, discard pile, current turn, and game events. They never see any player's hand. |
| **Public Game State** | The subset of room state visible to spectators: player names, card counts, discard pile top card, turn order, direction, current player, Uno call status, and game score. |
| **Private Game State** | Information restricted to individual players: their own hand contents. Never crosses the spectator boundary. |

## 1.9 Security & Integrity

| Term | Definition |
|------|-----------|
| **Game Log** | An append-only, immutable sequence of every state change in a game. Each entry is signed and timestamped. Used for dispute resolution and replay. |
| **Rate Limit** | Throttling applied at multiple layers: per-IP, per-user, per-room action, per-tournament action. Adaptive throttling increases restrictions under detected abuse patterns. |
| **Signed Event** | A domain event that includes a cryptographic signature or HMAC, ensuring integrity and non-repudiation for sensitive operations (e.g., card draws, Uno challenges, forfeit triggers). |
| **Audit Log** | A record of sensitive operations: session invalidations, forfeit triggers, Elo adjustments, tournament advancement overrides. Separate from the game log; scoped to platform-level operations. |
