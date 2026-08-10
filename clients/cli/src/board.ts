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

export function board(view: GameView, me: string): string {
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
    const actions = ["play <n> [R|G|B|Y]", view.drewThisTurn ? "pass" : "draw", "uno", "challenge", "state", "quit"];
    if (view.hand.length === 2) {
      lines.push("   down to your last two - play it as 'play <n> uno' or an opponent can catch you");
    }
    lines.push(`   YOUR TURN${view.drewThisTurn ? " (already drew)" : ""}: ${actions.join(" | ")}`);
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

const COLOURS: Record<string, string> = { R: "RED", G: "GREEN", B: "BLUE", Y: "YELLOW" };

/** §5.F notation: the first letter is the colour. A wild has none until someone declares one. */
const colourOf = (card: string): string | undefined => COLOURS[card.slice(0, 1)];

/** One line of narration, or null for events a player has no reason to read. */
export function describe(event: StreamEvent, me: string): string | null {
  const d = event.data as Record<string, string & number & boolean & string[]>;
  const name = (id: string) => (id === me ? "you" : short(id));
  const verb = (id: string, singular: string, plural: string) => (id === me ? plural : singular);

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
        : `${short(d.targetPlayerId)} is on one card - 'challenge' while the window is open`;
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
    // RoomCreated, ChallengeWindowClosed and RoomCompleted change state the board already shows.
    default:
      return null;
  }
}

/**
 * Applies what the event *states* — never what a rule would imply. Whose turn it is, how many cards
 * an opponent holds and what the discard is are all carried in the payload, so the board between
 * turns is the server's account of the room rather than the client's guess at it.
 *
 * The player's own hand and which of it is legal are deliberately not derived here: they come from
 * the state read, which is why `needsResync` exists.
 */
export function apply(view: GameView, event: StreamEvent, me: string): GameView {
  const d = event.data as Record<string, string & number & boolean & string[]>;
  const next: GameView = { ...view, opponents: view.opponents.map((o) => ({ ...o })) };
  const opponent = (id: string) => next.opponents.find((o) => o.playerId === id);
  const turn = (id: string | null) => {
    next.currentPlayerId = id;
    next.yourTurn = id === me;
    if (next.yourTurn) next.drewThisTurn = false;
  };

  switch (event.event) {
    case "GameStarted":
      next.discardTop = d.initialDiscardCard;
      next.activeColor = d.initialColor;
      next.status = "IN_PROGRESS";
      turn((d.playerOrder ?? [])[0] ?? null);
      break;
    case "CardPlayed": {
      next.discardTop = d.newDiscardTop;
      next.activeColor = d.chosenColor ?? colourOf(d.newDiscardTop) ?? view.activeColor;
      const played = opponent(d.playerId);
      if (played) played.cardCount = d.playerCardCount;
      // Playing resets the flag: hasCalledUno only holds until the hand size changes.
      if (played && d.playerCardCount !== 1) played.calledUno = false;
      turn(d.nextPlayerId);
      break;
    }
    case "CardDrawn": {
      const drew = opponent(d.playerId);
      if (drew) drew.cardCount = d.newCardCount;
      if (d.playerId === me) next.drewThisTurn = true;
      break;
    }
    case "ForcedDraw": {
      const forced = opponent(d.targetPlayerId);
      if (forced) forced.cardCount = d.newHandSize;
      break;
    }
    case "TurnPassed":
      turn(d.nextPlayerId);
      break;
    case "TurnSkipped":
      turn(d.nextPlayerId);
      break;
    case "DirectionReversed":
      next.direction = d.newDirection;
      break;
    case "UnoCallMade": {
      const called = opponent(d.playerId);
      if (called) called.calledUno = true;
      break;
    }
    case "ChallengeWindowOpened":
      next.challengeWindow = { targetPlayerId: d.targetPlayerId, expiresAt: String(d.expiresAt) };
      break;
    case "ChallengeWindowClosed":
    case "UnoChallengeResolved":
      next.challengeWindow = null;
      break;
    case "PlayerDisconnected":
    case "PlayerReconnected": {
      const player = opponent(d.playerId);
      if (player) player.connection = event.event === "PlayerReconnected" ? "connected" : "disconnected";
      break;
    }
    case "DeckRecycled":
      next.deckSize = d.newDeckSize;
      break;
    case "GameCompleted":
      next.status = "COMPLETED";
      next.finishingOrder = d.finishingOrder ?? [];
      turn(null);
      break;
    default:
      break;
  }
  return next;
}

/**
 * When the local view is no longer enough and the state read has to be made.
 *
 * Two cases, both about the half of the view the events do not carry: the player's own cards
 * changed, or it is their turn and `playable` has to be the server's legality check rather than the
 * client's opinion of it.
 */
export function needsResync(before: GameView, after: GameView, event: StreamEvent, me: string): boolean {
  const d = event.data as Record<string, string>;
  const dealtToMe =
    (event.event === "CardDrawn" && d.playerId === me) ||
    (event.event === "ForcedDraw" && d.targetPlayerId === me) ||
    (event.event === "UnoChallengeResolved" && d.penaltyPlayerId === me) ||
    event.event === "GameStarted";
  return dealtToMe || (after.yourTurn && !before.yourTurn);
}
