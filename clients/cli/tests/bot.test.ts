import { describe, expect, it } from "vitest";
import { brain } from "../src/bot.js";
import type { GameView } from "../src/board.js";

const me = "alice-0000-0000-0000-000000000000";
const bob = "bob-000000-0000-0000-000000000000";

const base: GameView = {
  roomId: "3f2a1b7c-0000-4000-8000-000000000000",
  gameNumber: 1,
  status: "IN_PROGRESS",
  sequenceNumber: 42,
  discardTop: "R5",
  activeColor: "RED",
  direction: "CLOCKWISE",
  deckSize: 34,
  currentPlayerId: me,
  yourTurn: true,
  hand: ["R7", "B2", "WILD", "Y+2"],
  playable: [0, 2],
  opponents: [{ playerId: bob, cardCount: 3, calledUno: false, connection: "connected" }],
  challengeWindow: null,
  finishingOrder: [],
  turnDeadline: null,
  drewThisTurn: false,
};

const window = { targetPlayerId: bob, expiresAt: "2026-08-10T12:00:05Z" };
/** Always calls Uno! unless a test says otherwise, so the dice only decide what is being tested. */
const think = (forgetUno = 0, seed = 7) => brain(me, seed, forgetUno);

describe("choosing a move (§5.E: a random *valid* card)", () => {
  it("only ever picks an index the server marked playable", () => {
    const bot = think();
    for (let i = 0; i < 200; i++) {
      expect(["R7", "WILD"]).toContain(bot({ ...base, playable: [0, 2] })?.body.card);
    }
  });

  it("declares a colour for a wild and never for a coloured card", () => {
    const wild = think()({ ...base, hand: ["WILD+4"], playable: [0] });
    expect(["RED", "GREEN", "BLUE", "YELLOW"]).toContain(wild?.body.chosenColor);
    expect(think()({ ...base, hand: ["R7"], playable: [0] })?.body).not.toHaveProperty("chosenColor");
  });

  it("draws when nothing is playable, and passes only once it has drawn", () => {
    expect(think()({ ...base, playable: [] })?.action).toBe("draw_card");
    expect(think()({ ...base, playable: [], drewThisTurn: true })?.action).toBe("pass");
  });

  it("does nothing while it is somebody else's turn", () => {
    expect(think()({ ...base, yourTurn: false, currentPlayerId: bob })).toBeNull();
  });
});

describe("calling Uno! (and forgetting to)", () => {
  const twoCards = { ...base, hand: ["R7", "B2"], playable: [0, 1] };

  it("calls with the play that leaves it on one card", () => {
    expect(think(0)(twoCards)?.body.callingUno).toBe(true);
  });

  it("forgets when the dice say so, which is what keeps the challenge reachable", () => {
    expect(think(1)(twoCards)?.body.callingUno).toBe(false);
  });

  it("has nothing to call with a fuller hand", () => {
    expect(think(0)({ ...base, hand: ["R7", "B2", "G4"], playable: [0] })?.body.callingUno).toBe(false);
  });

  it("forgets about a quarter of the time at the default rate", () => {
    const bot = think(0.25, 99);
    let called = 0;
    for (let i = 0; i < 400; i++) if (bot(twoCards)?.body.callingUno) called++;
    expect(called).toBeGreaterThan(240);
    expect(called).toBeLessThan(360);
  });
});

describe("challenging", () => {
  const open = { ...base, yourTurn: false, challengeWindow: window };

  it("takes an open window on an opponent who did not call", () => {
    expect(think()(open)).toEqual({
      action: "challenge_uno",
      body: { type: "challenge_uno", targetPlayerId: bob },
    });
  });

  it("leaves alone an opponent who did call — the engine refuses it, so offering it is a 409", () => {
    const called = [{ playerId: bob, cardCount: 1, calledUno: true, connection: "connected" }];
    expect(think()({ ...open, opponents: called })).toBeNull();
  });

  it("never challenges the window that names itself", () => {
    expect(think()({ ...open, challengeWindow: { ...window, targetPlayerId: me } })).toBeNull();
  });

  it("answers one window once — the view keeps showing it until the next read", () => {
    const bot = think();
    expect(bot(open)?.action).toBe("challenge_uno");
    expect(bot(open)).toBeNull();
    // A new window on the same player is a new window, not the one already answered.
    expect(bot({ ...open, challengeWindow: { ...window, expiresAt: "2026-08-10T12:00:30Z" } })?.action)
      .toBe("challenge_uno");
  });
});

describe("--seed", () => {
  const cards = (seed: number) => {
    const bot = brain(me, seed, 0.25);
    return Array.from({ length: 12 }, () => bot({ ...base, playable: [0, 1, 2, 3] })?.body.card);
  };

  it("makes a run reproducible", () => {
    expect(cards(4242)).toEqual(cards(4242));
    expect(cards(4242)).not.toEqual(cards(4243));
  });
});

describe("--idle (P5)", () => {
  const idle = brain(me, 7, 0, true);

  it("answers nothing, so its turns lapse and the timer worker moves the game on", () => {
    expect(idle({ ...base, yourTurn: true, playable: [0, 2] })).toBeNull();
    expect(idle({ ...base, yourTurn: true, playable: [], drewThisTurn: false })).toBeNull();
    expect(idle({ ...base, yourTurn: true, playable: [], drewThisTurn: true })).toBeNull();
  });

  it("does not take a challenge window either — a player who walked away takes nothing", () => {
    expect(idle({ ...base, yourTurn: false, challengeWindow: window })).toBeNull();
  });

  it("is off unless asked for: the same seed still plays a normal game", () => {
    expect(brain(me, 7, 0, false)({ ...base, yourTurn: true, playable: [0, 2] })).not.toBeNull();
  });
});
