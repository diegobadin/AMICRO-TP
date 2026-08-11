import { describe, expect, it } from "vitest";
import { decide, rng, windowKey } from "../src/bot.js";
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
const play = (view: GameView, random = rng(7), forget = 0, challenged = new Set<string>()) =>
  decide(view, me, random, forget, challenged);

describe("choosing a move (§5.E: a random *valid* card)", () => {
  it("only ever picks an index the server marked playable", () => {
    const random = rng(1);
    for (let i = 0; i < 200; i++) {
      const move = play({ ...base, hand: ["R7", "B2", "WILD", "Y+2"], playable: [0, 2] }, random);
      expect(["R7", "WILD"]).toContain(move?.body.card);
    }
  });

  it("declares a colour for a wild and never for a coloured card", () => {
    const wild = play({ ...base, hand: ["WILD+4"], playable: [0] });
    expect(["RED", "GREEN", "BLUE", "YELLOW"]).toContain(wild?.body.chosenColor);
    expect(play({ ...base, hand: ["R7"], playable: [0] })?.body).not.toHaveProperty("chosenColor");
  });

  it("draws when nothing is playable, and passes only once it has drawn", () => {
    expect(play({ ...base, playable: [] })?.action).toBe("draw_card");
    expect(play({ ...base, playable: [], drewThisTurn: true })?.action).toBe("pass");
  });

  it("does nothing while it is somebody else's turn", () => {
    expect(play({ ...base, yourTurn: false, currentPlayerId: bob })).toBeNull();
  });
});

describe("calling Uno! (and forgetting to)", () => {
  const twoCards = { ...base, hand: ["R7", "B2"], playable: [0, 1] };

  it("calls with the play that leaves it on one card", () => {
    expect(play(twoCards, rng(3), 0)?.body.callingUno).toBe(true);
  });

  it("forgets when the dice say so, which is what keeps the challenge reachable", () => {
    expect(play(twoCards, rng(3), 1)?.body.callingUno).toBe(false);
  });

  it("has nothing to call with a fuller hand", () => {
    expect(play({ ...base, hand: ["R7", "B2", "G4"], playable: [0] }, rng(3), 0)?.body.callingUno).toBe(false);
  });

  it("forgets about a quarter of the time at the default rate", () => {
    const random = rng(99);
    let called = 0;
    for (let i = 0; i < 400; i++) if (play(twoCards, random, 0.25)?.body.callingUno) called++;
    expect(called).toBeGreaterThan(240);
    expect(called).toBeLessThan(360);
  });
});

describe("challenging", () => {
  it("takes an open window on an opponent who did not call", () => {
    const move = play({ ...base, yourTurn: false, challengeWindow: window });
    expect(move).toEqual({ action: "challenge_uno", body: { type: "challenge_uno", targetPlayerId: bob } });
  });

  it("leaves alone an opponent who did call — the engine refuses it, so offering it is a 409", () => {
    const view = {
      ...base,
      yourTurn: false,
      challengeWindow: window,
      opponents: [{ playerId: bob, cardCount: 1, calledUno: true, connection: "connected" }],
    };
    expect(play(view)).toBeNull();
  });

  it("never challenges the window that names itself", () => {
    const view = { ...base, yourTurn: false, challengeWindow: { targetPlayerId: me, expiresAt: window.expiresAt } };
    expect(play(view)).toBeNull();
  });

  it("answers one window once — the view still shows it until the next read", () => {
    const view = { ...base, challengeWindow: window };
    const challenged = new Set([windowKey(window)]);
    expect(play(view, rng(7), 0, challenged)?.action).toBe("play_card");
  });
});

describe("--seed", () => {
  it("makes a run reproducible", () => {
    const draw = (seed: number) => {
      const random = rng(seed);
      return Array.from({ length: 12 }, () => play({ ...base, playable: [0, 1, 2, 3] }, random)?.body.card);
    };
    expect(draw(4242)).toEqual(draw(4242));
    expect(draw(4242)).not.toEqual(draw(4243));
  });
});
