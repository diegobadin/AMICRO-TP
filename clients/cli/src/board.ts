// Rendering, and the small amount of bookkeeping the event stream makes possible. Cards arrive from
// the backend already in the canonical notation of Client-Checkpoint §5.F (`R5`, `BSKIP`, `Y+2`,
// `WILD+4`) — the CLI prints what it is given rather than translating, so the string in the
// terminal, in the API response, in the SSE frame and in the game log is one and the same.

import type { StreamEvent } from "./stream.js";

export interface Opponent {
  playerId: string;
  cardCount: number;
  calledUno: boolean;
  connection: string;
}

export interface GameView {
  roomId: string;
  gameNumber: number;
  status: string;
  sequenceNumber: number;
  discardTop: string;
  activeColor: string;
  direction: string;
  deckSize: number;
  currentPlayerId: string | null;
  yourTurn: boolean;
  hand: string[];
  playable: number[];
  opponents: Opponent[];
  challengeWindow: { targetPlayerId: string; expiresAt: string } | null;
  finishingOrder: string[];
  turnDeadline: string | null;
  drewThisTurn: boolean;
}

const short = (id: string) => id.slice(0, 8);

/**
 * Seconds left on the turn, read off the deadline the server put in the view. It is printed when
 * the board is drawn rather than ticked down in place: the feed writes lines to the same terminal,
 * and a counter rewriting itself underneath them would fight the narration for the cursor. The
 * client still holds no opinion about when the deadline passes — it renders a number and the server
 * decides (P5 E4, mechanism refined in requirements.md).
 */
export function remaining(deadline: string | null, now: number = Date.now()): string {
  if (!deadline) return "";
  const seconds = Math.round((Date.parse(deadline) - now) / 1000);
  if (Number.isNaN(seconds)) return "";
  return seconds > 0 ? ` (${seconds}s)` : " (time is up)";
}

export function board(view: GameView, me: string, now: number = Date.now()): string {
  const arrow = view.direction === "CLOCKWISE" ? "▸" : "◂";
  const others = view.opponents
    .map((o) => {
      const uno = o.calledUno ? " UNO!" : "";
      const gone = o.connection === "connected" ? "" : ` (${o.connection})`;
      const turn = o.playerId === view.currentPlayerId ? "*" : " ";
      return `${turn}${short(o.playerId)} ${o.cardCount}${uno}${gone}`;
    })
    .join("   ");

  // Playable cards are marked from the server's own legality check, so the board can never
  // disagree with what the engine will accept.
  const playable = new Set(view.playable);
  // Fixed-width so the columns line up whatever the cards are: WILD+4 is six characters, R5 is two.
  const hand = view.hand
    .map((card, i) => `${String(i + 1).padStart(2)}) ${(card + (playable.has(i) ? "*" : "")).padEnd(7)}`)
    .join("");

  const lines = [
    `-- room ${short(view.roomId)} - game ${view.gameNumber} - seq ${view.sequenceNumber}`,
    `   discard ${view.discardTop}  color ${view.activeColor}  ${arrow}  deck ${view.deckSize}`,
    `   players  ${others}`,
    `   your hand (${view.hand.length}):`,
    `     ${hand || "(empty)"}`,
  ];
  if (view.challengeWindow && view.challengeWindow.targetPlayerId !== me) {
    lines.push(`   ${short(view.challengeWindow.targetPlayerId)} is on one card - 'challenge' while the window is open`);
  }
  if (view.yourTurn) {
    // Only the actions that are legal right now. `pass` before drawing is a 409, and offering it
    // teaches the player to expect an error — the drill harness fell for exactly that.
    const actions = ["<n> or play <n> [R|G|B|Y]", view.drewThisTurn ? "pass" : "draw", "uno", "challenge", "state", "quit"];
    if (view.hand.length === 2) {
      lines.push("   down to your last two - play it as 'play <n> uno' or an opponent can catch you");
    }
    lines.push(`   YOUR TURN${view.drewThisTurn ? " (already drew)" : ""}${remaining(view.turnDeadline, now)}: ${actions.join(" | ")}`);
  } else {
    lines.push(`   waiting for ${view.currentPlayerId ? short(view.currentPlayerId) : "the next turn"}`);
  }
  return lines.join("\n");
}

// ---- the live feed, from the events themselves
//
// P3 inferred it by diffing two polls, which collapsed anything that happened inside one interval
// into a single line. These are the room's own events, in the room's own order, so what the player
// reads is what the log says happened.

/**
 * The fields the room's events carry, as this client reads them — the catalog of
 * `docs/design/04-commands-events.md` narrowed to what the feed needs. Every field is optional
 * because each event carries its own subset, and naming them beats casting the payload to
 * something permissive and hoping.
 */
interface EventData {
  playerId?: string;
  playerCount?: number;
  gameNumber?: number;
  initialDiscardCard?: string;
  initialColor?: string;
  card?: string;
  chosenColor?: string;
  playerCardCount?: number;
  nextPlayerId?: string;
  newCardCount?: number;
  targetPlayerId?: string;
  cardCount?: number;
  reason?: string;
  skippedPlayerId?: string;
  newDirection?: string;
  autoAction?: string;
  challengerId?: string;
  challengeSucceeded?: boolean;
  penaltyPlayerId?: string;
  penaltyCardCount?: number;
  newDeckSize?: number;
  finishingOrder?: string[];
}

/** One line of narration, or null for events a player has no reason to read. */
export function describe(event: StreamEvent, me: string): string | null {
  const d = event.data as EventData;
  const name = (id?: string) => (id === me ? "you" : short(id ?? "?"));
  const verb = (id: string | undefined, singular: string, plural: string) => (id === me ? plural : singular);

  switch (event.event) {
    case "PlayerJoined":
      return `${name(d.playerId)} joined (${d.playerCount} at the table)`;
    case "PlayerLeft":
      return `${name(d.playerId)} left`;
    case "GameStarted":
      return `game ${d.gameNumber} started - discard ${d.initialDiscardCard}, color ${d.initialColor}`;
    case "CardPlayed": {
      const colour = d.chosenColor ? ` - color ${d.chosenColor}` : "";
      const last = d.playerCardCount === 1 ? " - one card left!" : "";
      return `${name(d.playerId)} played ${d.card}${colour}${last}`;
    }
    case "CardDrawn":
      return `${name(d.playerId)} drew a card`;
    case "ForcedDraw":
      return `${name(d.targetPlayerId)} ${verb(d.targetPlayerId, "draws", "draw")} ${d.cardCount} (${d.reason})`;
    case "TurnPassed":
      return `${name(d.playerId)} passed`;
    case "TurnSkipped":
      return `${name(d.skippedPlayerId)} ${verb(d.skippedPlayerId, "was", "were")} skipped`;
    case "DirectionReversed":
      return `direction is now ${String(d.newDirection).toLowerCase().replace("_", "-")}`;
    case "TurnTimedOut":
      return `${name(d.playerId)} ran out of time (${d.autoAction})`;
    case "UnoCallMade":
      return `${name(d.playerId)} called UNO!`;
    case "ChallengeWindowOpened":
      return d.targetPlayerId === me
        ? "you are on one card - an opponent can challenge if you did not call it"
        : `${name(d.targetPlayerId)} is on one card - 'challenge' while the window is open`;
    case "UnoChallengeIssued":
      return `${name(d.challengerId)} challenged ${name(d.targetPlayerId)}`;
    case "UnoChallengeResolved": {
      const outcome = d.challengeSucceeded ? "the challenge stuck" : "the challenge failed";
      if (!d.penaltyPlayerId) return outcome;
      return `${outcome} - ${name(d.penaltyPlayerId)} ${verb(d.penaltyPlayerId, "draws", "draw")} ${d.penaltyCardCount}`;
    }
    case "DeckRecycled":
      return `deck reshuffled (${d.newDeckSize} cards)`;
    case "PlayerDisconnected":
      return `${name(d.playerId)} disconnected`;
    case "PlayerReconnected":
      return `${name(d.playerId)} ${verb(d.playerId, "is", "are")} back`;
    case "PlayerForfeited":
      return `${name(d.playerId)} forfeited (${d.reason})`;
    case "GameCompleted": {
      const winner = (d.finishingOrder ?? [])[0];
      return `game over - ${winner === me ? "you win!" : `${short(winner ?? "?")} wins`}`;
    }
    // A room the clock closed before it ever filled. Worth a line precisely because nobody did it.
    case "RoomExpired":
      return `the room expired without enough players (${d.reason})`;
    // RoomCreated, ChallengeWindowClosed and RoomCompleted change state the board already shows.
    default:
      return null;
  }
}

/**
 * When the client has to re-read the state (E4's endpoint, the one P3 polled).
 *
 * One rule: **read when the player can act, or when their own cards changed.** Everything else the
 * player sees while waiting is narration, and narration needs no state. Keeping a second copy of
 * the game here — applying each event to a local board — bought nothing once the board stopped
 * being rendered from it, and a second copy of a game's state is the thing most likely to lie.
 */
export function mustRefresh(event: StreamEvent, me: string): boolean {
  const d = event.data as EventData;
  switch (event.event) {
    // The hand is dealt, or the final board is worth having in full.
    case "GameStarted":
    case "GameCompleted":
    // The clock ended the room; whatever the player was looking at is now history.
    case "RoomExpired":
    // These can move the turn without naming who is next.
    case "PlayerForfeited":
    case "PlayerDisconnected":
      return true;
    case "CardPlayed":
    case "TurnPassed":
    case "TurnSkipped":
      return d.nextPlayerId === me;
    case "CardDrawn":
      return d.playerId === me;
    case "ForcedDraw":
      return d.targetPlayerId === me;
    case "UnoChallengeResolved":
      return d.penaltyPlayerId === me;
    // A window on someone else is the one thing a player may act on out of turn.
    case "ChallengeWindowOpened":
      return d.targetPlayerId !== me;
    default:
      return false;
  }
}
