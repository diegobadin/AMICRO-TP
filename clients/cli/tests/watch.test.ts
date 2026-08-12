import { describe, expect, it } from "vitest";
import { type SpectatorView, isOver, pathFor, watchBoard } from "../src/watch.js";

const view = (overrides: Partial<SpectatorView> = {}): SpectatorView => ({
  roomId: "1c1b0b7e-0000-4000-8000-000000000000",
  roomType: "CASUAL",
  status: "IN_PROGRESS",
  gameNumber: 1,
  players: [
    { id: "alice", cardCount: 6, isConnected: true, calledUno: false, forfeited: false },
    { id: "bob", cardCount: 8, isConnected: true, calledUno: false, forfeited: false },
  ],
  topCard: "R5",
  color: "RED",
  direction: "CLOCKWISE",
  currentTurn: "bob",
  deckSize: null,
  finishingOrder: [],
  lastSequence: 5,
  spectatorCount: 1,
  ...overrides,
});

describe("the spectator board", () => {
  it("marks whose turn it is", () => {
    expect(watchBoard(view())).toContain("*bob");
  });

  it("never prints a hand, because the view has none", () => {
    const rendered = watchBoard(view());
    for (const forbidden of ["hand", "deck order", "seed"]) {
      expect(rendered.toLowerCase()).not.toContain(forbidden);
    }
  });

  it("shows an unknown card count as ? rather than inventing one", () => {
    // `GameStarted` does not say how many cards were dealt. A `?` is honest; a 7 would be this
    // client asserting a rule that belongs to room-gameplay.
    const rendered = watchBoard(
      view({
        players: [{ id: "alice", cardCount: null, isConnected: true, calledUno: false, forfeited: false }],
      }),
    );
    expect(rendered).toContain("alice ?");
  });

  it("shows uno, absence and forfeits", () => {
    const rendered = watchBoard(
      view({
        players: [
          { id: "alice", cardCount: 1, isConnected: true, calledUno: true, forfeited: false },
          { id: "bob", cardCount: 4, isConnected: false, calledUno: false, forfeited: false },
          { id: "carol", cardCount: 3, isConnected: true, calledUno: false, forfeited: true },
        ],
      }),
    );
    expect(rendered).toContain("UNO!");
    expect(rendered).toContain("(away)");
    expect(rendered).toContain("(forfeited)");
  });

  it("reverses the arrow with the direction", () => {
    expect(watchBoard(view())).toContain("▸");
    expect(watchBoard(view({ direction: "COUNTERCLOCKWISE" }))).toContain("◂");
  });

  it("prints the finishing order once there is one", () => {
    const rendered = watchBoard(view({ status: "COMPLETED", finishingOrder: ["alice", "bob"] }));
    expect(rendered).toContain("finished: alice > bob");
    expect(rendered).toContain("completed");
  });

  it("counts the other spectators", () => {
    expect(watchBoard(view({ spectatorCount: 3 }))).toContain("3 watching");
  });

  it("survives a room nobody has joined", () => {
    expect(watchBoard(view({ players: [], currentTurn: null, topCard: null, color: null }))).toContain(
      "(nobody yet)",
    );
  });
});

describe("knowing when to stop", () => {
  it("ends on a terminal status and not before", () => {
    expect(isOver(view())).toBe(false);
    expect(isOver(view({ status: "WAITING" }))).toBe(false);
    expect(isOver(view({ status: "COMPLETED" }))).toBe(true);
    expect(isOver(view({ status: "EXPIRED" }))).toBe(true);
  });
});

describe("where each read goes", () => {
  it("defaults rating to the logged-in player", () => {
    expect(pathFor("rating", {}, "alice")).toBe("/players/alice/rating");
    expect(pathFor("rating", { player: "bob" }, "alice")).toBe("/players/bob/rating");
  });

  it("has nothing to ask for when there is no player at all", () => {
    expect(pathFor("rating", {}, undefined)).toBeUndefined();
  });

  it("passes a limit through to the leaderboard", () => {
    expect(pathFor("leaderboard", {})).toBe("/leaderboard");
    expect(pathFor("leaderboard", { limit: "5" })).toBe("/leaderboard?limit=5");
  });

  it("picks the stats surface from the flags, defaulting to the overview", () => {
    expect(pathFor("stats", {})).toBe("/stats/overview");
    expect(pathFor("stats", { player: "alice" })).toBe("/stats/players/alice");
    expect(pathFor("stats", { room: "room-1" })).toBe("/stats/rooms/room-1");
  });

  it("encodes an id that would otherwise change the path", () => {
    expect(pathFor("stats", { player: "a/b" })).toBe("/stats/players/a%2Fb");
  });
});
