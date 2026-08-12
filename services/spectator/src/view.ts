// The SpectatorRoomView of Architecture §6.3, as a pure function over the public event stream.
//
// Privacy here is structural, not a filter: there is no field on this type that could hold a hand,
// a deck order or a seed, so there is physically nowhere to put one. `publicPayload` already
// stripped the seed three services upstream, in the same transaction that wrote the event — this
// type is the last of architecture §6's three layers, and the one that cannot be bypassed by a bug.
//
// Two things this view deliberately does NOT carry, because both would be a second copy of a rule
// room-gameplay owns and a copy is free to drift:
//   - the turn deadline: no public event carries it, and deriving it from `turnTimeoutSeconds`
//     would put the deadline rule in two places. The player's own board gets it from the service
//     that owns it.
//   - the deal size: `GameStarted` does not say how many cards were dealt, so card counts start
//     `null` and fill in as players act. Unknown is honest; 7 would be a guess wearing a fact's
//     clothes.

export type RoomStatus = "WAITING" | "IN_PROGRESS" | "COMPLETED" | "EXPIRED";

export interface SpectatorPlayer {
  id: string;
  cardCount: number | null;
  isConnected: boolean;
  calledUno: boolean;
  forfeited: boolean;
}

export interface SpectatorView {
  roomId: string;
  roomType: string | null;
  status: RoomStatus;
  maxPlayers: number | null;
  gameNumber: number | null;
  playerOrder: string[];
  players: SpectatorPlayer[];
  topCard: string | null;
  color: string | null;
  direction: string;
  currentTurn: string | null;
  deckSize: number | null;
  finishingOrder: string[];
  lastSequence: number;
  spectatorCount: number;
}

/** The domain event as it arrives: `publicPayload` plus `roomId` and `sequenceNumber`, flat. */
export interface PublicEvent {
  type: string;
  roomId: string;
  sequenceNumber: number;
  [field: string]: unknown;
}

export function emptyView(roomId: string): SpectatorView {
  return {
    roomId,
    roomType: null,
    status: "WAITING",
    maxPlayers: null,
    gameNumber: null,
    playerOrder: [],
    players: [],
    topCard: null,
    color: null,
    direction: "CLOCKWISE",
    currentTurn: null,
    deckSize: null,
    finishingOrder: [],
    lastSequence: -1,
    spectatorCount: 0,
  };
}

function player(view: SpectatorView, id: string): SpectatorPlayer {
  const existing = view.players.find((p) => p.id === id);
  if (existing) return existing;
  const created: SpectatorPlayer = {
    id,
    cardCount: null,
    isConnected: true,
    calledUno: false,
    forfeited: false,
  };
  view.players.push(created);
  return created;
}

const str = (value: unknown): string | null => (typeof value === "string" ? value : null);
const num = (value: unknown): number | null => (typeof value === "number" ? value : null);

/**
 * Apply one event. Returns a new view; the caller decides whether to keep it.
 *
 * The terminal state is **sticky**: a room that has completed or expired never goes back to
 * `IN_PROGRESS`. The room's log arrives on two topics with no ordering between them, so a
 * `GameCompleted` (lifecycle) can genuinely land before a `CardPlayed` (public) from earlier in the
 * same room. Without stickiness that finished game would show as live for as long as the lag.
 */
export function apply(previous: SpectatorView, event: PublicEvent): SpectatorView {
  const view: SpectatorView = {
    ...previous,
    playerOrder: [...previous.playerOrder],
    finishingOrder: [...previous.finishingOrder],
    players: previous.players.map((p) => ({ ...p })),
  };
  const terminal = previous.status === "COMPLETED" || previous.status === "EXPIRED";

  switch (event.type) {
    case "RoomCreated":
      view.roomType = str(event.roomType);
      view.maxPlayers = num(event.maxPlayers);
      view.status = "WAITING";
      break;

    case "PlayerJoined":
      player(view, String(event.playerId));
      break;

    case "PlayerLeft":
      view.players = view.players.filter((p) => p.id !== event.playerId);
      break;

    case "GameStarted": {
      const order = Array.isArray(event.playerOrder) ? (event.playerOrder as string[]) : [];
      view.gameNumber = num(event.gameNumber);
      view.playerOrder = order;
      order.forEach((id) => player(view, id));
      // Ordered as the table is seated, so a spectator's board reads round the table.
      view.players.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
      view.topCard = str(event.initialDiscardCard);
      view.color = str(event.initialColor);
      view.currentTurn = order[0] ?? null;
      view.players.forEach((p) => {
        p.calledUno = false;
      });
      view.status = "IN_PROGRESS";
      break;
    }

    case "CardPlayed": {
      const id = String(event.playerId);
      view.topCard = str(event.newDiscardTop);
      view.color = str(event.chosenColor) ?? view.color;
      player(view, id).cardCount = num(event.playerCardCount);
      view.currentTurn = str(event.nextPlayerId);
      break;
    }

    case "CardDrawn":
      player(view, String(event.playerId)).cardCount = num(event.newCardCount);
      break;

    case "ForcedDraw":
      player(view, String(event.targetPlayerId)).cardCount = num(event.newHandSize);
      break;

    case "TurnPassed":
    case "TurnSkipped":
      view.currentTurn = str(event.nextPlayerId);
      break;

    case "DirectionReversed":
      view.direction = str(event.newDirection) ?? view.direction;
      break;

    case "UnoCallMade":
      player(view, String(event.playerId)).calledUno = true;
      break;

    case "DeckRecycled":
      view.deckSize = num(event.newDeckSize);
      break;

    case "PlayerDisconnected":
      player(view, String(event.playerId)).isConnected = false;
      break;

    case "PlayerReconnected":
      player(view, String(event.playerId)).isConnected = true;
      break;

    case "PlayerForfeited":
      player(view, String(event.playerId)).forfeited = true;
      break;

    case "GameCompleted":
      view.gameNumber = num(event.gameNumber) ?? view.gameNumber;
      view.finishingOrder = Array.isArray(event.finishingOrder)
        ? (event.finishingOrder as string[])
        : [];
      view.status = "COMPLETED";
      view.currentTurn = null;
      break;

    case "RoomCompleted":
      view.status = "COMPLETED";
      view.currentTurn = null;
      break;

    case "RoomExpired":
      view.status = "EXPIRED";
      view.currentTurn = null;
      break;

    default:
      // An event this projection has no opinion about still advances the sequence: the spectator
      // is not required to understand every event to stay in step with the log.
      break;
  }

  // The terminal clamp. Applied after the switch rather than guarded inside it, so it covers every
  // field an out-of-order event could use to reopen a finished room — `status` and, just as
  // importantly, `currentTurn`, which a late `CardPlayed` would otherwise hand back to a player at
  // a table that is no longer playing. Board fields are left alone on purpose: a late event filling
  // in the final card counts is history arriving late, not a room coming back to life.
  if (terminal) {
    view.status = previous.status;
    view.currentTurn = null;
  }

  view.lastSequence = Math.max(previous.lastSequence, event.sequenceNumber);
  return view;
}
