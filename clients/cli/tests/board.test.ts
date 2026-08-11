import { describe, expect, it } from "vitest";
import { GameView, board, describe as narrate, mustRefresh, remaining } from "../src/board.js";
import { seqOf } from "../src/api.js";
import type { StreamEvent } from "../src/stream.js";

const base: GameView = {
  roomId: "3f2a1b7c-0000-4000-8000-000000000000",
  gameNumber: 1,
  status: "IN_PROGRESS",
  sequenceNumber: 42,
  discardTop: "R5",
  activeColor: "RED",
  direction: "CLOCKWISE",
  deckSize: 34,
  currentPlayerId: "alice-0000-0000-0000-000000000000",
  yourTurn: true,
  hand: ["R7", "B2", "WILD", "Y+2"],
  playable: [0, 2],
  opponents: [
    { playerId: "bob-000000-0000-0000-000000000000", cardCount: 3, calledUno: false, connection: "connected" },
    { playerId: "carla-00000-0000-0000-000000000000", cardCount: 1, calledUno: true, connection: "connected" },
  ],
  challengeWindow: null,
  finishingOrder: [],
  turnDeadline: null,
  drewThisTurn: false,
};

const me = "alice-0000-0000-0000-000000000000";

describe("turn board (Client-Checkpoint §5.C)", () => {
  const rendered = board(base, me);

  it("shows the discard top, the active colour, the direction and the draw pile", () => {
    expect(rendered).toContain("discard R5");
    expect(rendered).toContain("color RED");
    expect(rendered).toContain("deck 34");
  });

  it("numbers the hand from 1 and marks only the cards the server says are legal", () => {
    expect(rendered).toContain(" 1) R7*");
    expect(rendered).toContain(" 2) B2 ");
    expect(rendered).toContain(" 3) WILD*");
    expect(rendered).toContain(" 4) Y+2 ");
  });

  it("shows opponents with their card counts and the UNO flag", () => {
    expect(rendered).toMatch(/bob-0000 3/);
    expect(rendered).toMatch(/carla-00 1 UNO!/);
  });

  it("prints cards in the canonical §5.F notation, unchanged from the wire", () => {
    // The CLI must not invent its own spelling: what the backend sent is what the faculty reads.
    for (const card of base.hand) expect(rendered).toContain(card);
  });

  it("offers the turn actions only when it is the player's turn", () => {
    expect(rendered).toContain("YOUR TURN");
    expect(board({ ...base, yourTurn: false, currentPlayerId: "bob-000000-0000-0000-000000000000" }, me))
      .toContain("waiting for bob-0000");
  });

  it("never shows an opponent's cards, because it is never given them", () => {
    expect(rendered).not.toContain("undefined");
    const others = rendered.split("your hand")[0];
    for (const card of ["R7", "B2", "Y+2"]) expect(others).not.toContain(card);
  });
});

const bob = "bob-000000-0000-0000-000000000000";
const ev = (event: string, data: Record<string, unknown>, id = 43): StreamEvent => ({ id, event, data });

describe("live feed, read from the room's own events", () => {
  it("reports a card played, with the colour when one was declared", () => {
    const played = ev("CardPlayed", { playerId: bob, card: "WILD", newDiscardTop: "WILD", chosenColor: "BLUE", playerCardCount: 2, nextPlayerId: me });
    expect(narrate(played, me)).toBe("bob-0000 played WILD - color BLUE");
  });

  it("says when someone is down to their last card, because that is when the mechanic matters", () => {
    const played = ev("CardPlayed", { playerId: bob, card: "R7", newDiscardTop: "R7", playerCardCount: 1, nextPlayerId: me });
    expect(narrate(played, me)).toContain("one card left!");
  });

  it("names the player as 'you' rather than an id", () => {
    expect(narrate(ev("CardDrawn", { playerId: me, newCardCount: 5 }), me)).toBe("you drew a card");
    expect(narrate(ev("UnoCallMade", { playerId: bob }), me)).toBe("bob-0000 called UNO!");
  });

  it("announces the winner when the game completes", () => {
    expect(narrate(ev("GameCompleted", { finishingOrder: [me, bob] }), me)).toBe("game over - you win!");
  });

  it("reports a disconnection so the table knows why the turn is being skipped", () => {
    expect(narrate(ev("PlayerDisconnected", { playerId: bob }), me)).toBe("bob-0000 disconnected");
  });

  it("gives one line per event — two things in the same instant are two lines, not one", () => {
    // The P3 feed diffed two polls, so a draw and the play that followed it inside one interval
    // collapsed into a single line. One frame in, one line out.
    const burst = [
      ev("CardDrawn", { playerId: bob, newCardCount: 4 }, 43),
      ev("CardPlayed", { playerId: bob, card: "R5", newDiscardTop: "R5", playerCardCount: 3, nextPlayerId: me }, 44),
    ];
    expect(burst.map((e) => narrate(e, me))).toEqual(["bob-0000 drew a card", "bob-0000 played R5"]);
  });

  it("reads as English when the player is the subject", () => {
    // "you was skipped" is what a line written for third parties looks like when it is handed to
    // the player it is about. The drill transcript is read by the faculty, so it has to read.
    expect(narrate(ev("TurnSkipped", { skippedPlayerId: me, nextPlayerId: bob }), me)).toBe("you were skipped");
    expect(narrate(ev("TurnSkipped", { skippedPlayerId: bob, nextPlayerId: me }), me)).toBe("bob-0000 was skipped");
    expect(narrate(ev("PlayerReconnected", { playerId: me }), me)).toBe("you are back");
    expect(narrate(ev("ForcedDraw", { targetPlayerId: me, cardCount: 2, newHandSize: 6, reason: "draw_two" }), me))
      .toBe("you draw 2 (draw_two)");
  });

  it("stays quiet about events a player has no reason to read", () => {
    expect(narrate(ev("RoomCompleted", { roomType: "CASUAL" }), me)).toBeNull();
  });
});

describe("when the client re-reads the state", () => {
  it("reads when its own cards changed", () => {
    expect(mustRefresh(ev("CardDrawn", { playerId: me, newCardCount: 5 }), me)).toBe(true);
    expect(mustRefresh(ev("ForcedDraw", { targetPlayerId: me, cardCount: 2 }), me)).toBe(true);
    expect(mustRefresh(ev("UnoChallengeResolved", { penaltyPlayerId: me, penaltyCardCount: 2 }), me)).toBe(true);
  });

  it("reads when the turn arrives, so `playable` is the server's answer and not a guess", () => {
    const played = ev("CardPlayed", { playerId: bob, card: "R5", newDiscardTop: "R5", playerCardCount: 2, nextPlayerId: me });
    expect(mustRefresh(played, me)).toBe(true);
    expect(mustRefresh(ev("TurnPassed", { playerId: bob, nextPlayerId: me }), me)).toBe(true);
    expect(mustRefresh(ev("TurnSkipped", { skippedPlayerId: bob, nextPlayerId: me }), me)).toBe(true);
  });

  it("reads when a window opens on someone else — the one thing playable out of turn", () => {
    expect(mustRefresh(ev("ChallengeWindowOpened", { targetPlayerId: bob }), me)).toBe(true);
    expect(mustRefresh(ev("ChallengeWindowOpened", { targetPlayerId: me }), me)).toBe(false);
  });

  it("does not read for something that happened between other players", () => {
    const carla = "carla-00000-0000-0000-000000000000";
    const played = ev("CardPlayed", { playerId: bob, card: "R5", newDiscardTop: "R5", playerCardCount: 2, nextPlayerId: carla });
    expect(mustRefresh(played, me)).toBe(false);
    expect(mustRefresh(ev("CardDrawn", { playerId: bob, newCardCount: 4 }), me)).toBe(false);
    expect(mustRefresh(ev("UnoCallMade", { playerId: bob }), me)).toBe(false);
  });

  it("reads when the game starts or ends, and when a player drops out of it", () => {
    // These can move the turn without naming who is next, so the read is the only honest answer.
    for (const e of ["GameStarted", "GameCompleted", "PlayerForfeited", "PlayerDisconnected"]) {
      expect(mustRefresh(ev(e, { playerId: bob }), me)).toBe(true);
    }
  });
});

describe("ETag handling", () => {
  it("reads the sequence number out of strong and weak tags", () => {
    expect(seqOf('"42"')).toBe(42);
    expect(seqOf('W/"42"')).toBe(42);
    expect(seqOf(undefined)).toBeNull();
    expect(seqOf("not-a-tag")).toBeNull();
  });
});

describe("the turn deadline (P5)", () => {
  const at = Date.parse("2026-08-11T12:00:00Z");

  it("shows the seconds left on your own turn", () => {
    const view = { ...base, turnDeadline: "2026-08-11T12:00:18Z" };
    expect(remaining(view.turnDeadline, at)).toBe(" (18s)");
    expect(board(view, me, at)).toContain("YOUR TURN (18s)");
  });

  it("says so when the deadline has already gone by", () => {
    expect(remaining("2026-08-11T11:59:55Z", at)).toBe(" (time is up)");
  });

  // A room with no game in progress has no turn timer, and a nonsense value is not worth a crash.
  it("renders nothing at all when there is no deadline to show", () => {
    expect(remaining(null, at)).toBe("");
    expect(remaining("not a timestamp", at)).toBe("");
    expect(board(base, me)).toContain("YOUR TURN:");
  });

  it("narrates a room the clock closed, and re-reads on it", () => {
    const expired: StreamEvent = { id: 9, event: "RoomExpired", data: { reason: "waiting_timeout" } };
    expect(narrate(expired, me)).toContain("expired");
    expect(mustRefresh(expired, me)).toBe(true);
  });
});
