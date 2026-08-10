import { describe, expect, it } from "vitest";
import { GameView, board, feed } from "../src/board.js";
import { seqOf } from "../src/api.js";

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

describe("live feed derived from consecutive polls", () => {
  it("reports a card played and a colour change", () => {
    const after = { ...base, discardTop: "WILD", activeColor: "BLUE", sequenceNumber: 43 };
    expect(feed(base, after, "someone-else")).toContain("alice-00 played WILD - color BLUE");
  });

  it("reports an opponent drawing and calling uno", () => {
    const after = {
      ...base,
      opponents: [
        { ...base.opponents[0], cardCount: 5 },
        { ...base.opponents[1], calledUno: true },
      ],
    };
    expect(feed(base, after, me)).toContain("bob-0000 drew 2");
  });

  it("names the player as 'you' rather than an id", () => {
    const after = { ...base, hand: [...base.hand, "G9"] };
    expect(feed(base, after, me)).toContain("you drew G9");
  });

  it("announces the winner when the game completes", () => {
    const after = { ...base, status: "COMPLETED", finishingOrder: [me, "bob-000000-0000-0000-000000000000"] };
    expect(feed(base, after, me)).toContain("game over - you win!");
  });

  it("reports a disconnection so the table knows why the turn is being skipped", () => {
    const after = {
      ...base,
      opponents: [{ ...base.opponents[0], connection: "disconnected" }, base.opponents[1]],
    };
    expect(feed(base, after, me)).toContain("bob-0000 is disconnected");
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
