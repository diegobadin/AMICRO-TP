import { describe, expect, it } from "vitest";
import { useSession } from "../src/api.js";
import {
  type SpectatorView,
  isOver,
  pathFor,
  readPath,
  surfaceFor,
  watchBoard,
} from "../src/watch.js";

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

describe("which surface a command means", () => {
  it("maps the three commands and their flags onto five surfaces", () => {
    expect(surfaceFor("rating", {})).toBe("rating");
    expect(surfaceFor("leaderboard", {})).toBe("leaderboard");
    expect(surfaceFor("stats", {})).toBe("overview");
    expect(surfaceFor("stats", { player: "p" })).toBe("player-stats");
    expect(surfaceFor("stats", { room: "r" })).toBe("room-stats");
  });

  it("prefers an explicit player over the room when both are given", () => {
    expect(surfaceFor("stats", { player: "p", room: "r" })).toBe("player-stats");
  });
});

describe("where each read goes", () => {
  it("defaults rating to this session's player ID, not its display name", () => {
    // ranking keys on the player id. Passing the username asks about somebody who does not exist
    // and gets a confident "1000 after 0 games" back — the bug this argument exists to prevent.
    const id = "84740d1d-9ed0-4799-9464-c42f57fec30c";
    expect(pathFor("rating", {}, id)).toBe(`/players/${id}/rating`);
    expect(pathFor("rating", { player: "bob" }, id)).toBe("/players/bob/rating");
  });

  it("has nothing to ask for when there is no player at all", () => {
    expect(pathFor("rating", {}, "")).toBeUndefined();
  });

  it("passes a limit through to the leaderboard", () => {
    expect(pathFor("leaderboard", {}, "me")).toBe("/leaderboard");
    expect(pathFor("leaderboard", { limit: "5" }, "me")).toBe("/leaderboard?limit=5");
  });

  it("picks the stats surface from the flags, defaulting to the overview", () => {
    expect(pathFor("stats", {}, "me")).toBe("/stats/overview");
    expect(pathFor("stats", { player: "alice" }, "me")).toBe("/stats/players/alice");
    expect(pathFor("stats", { room: "room-1" }, "me")).toBe("/stats/rooms/room-1");
  });

  it("encodes an id that would otherwise change the path", () => {
    expect(pathFor("stats", { player: "a/b" }, "me")).toBe("/stats/players/a%2Fb");
  });
});

describe("the wiring, not just the mapping", () => {
  // The P6 bug lived here and nowhere else: `pathFor` was correct, and the caller handed it
  // `session.user` — the display name — instead of the player id. Restoring that mistake has to
  // turn something red, or the fix is not defended.
  it("asks about the session's player id, never its display name", () => {
    useSession({ token: "t", user: "p6alice", userId: "84740d1d-9ed0-4799-9464-c42f57fec30c" });
    expect(readPath("rating", {})).toBe("/players/84740d1d-9ed0-4799-9464-c42f57fec30c/rating");
    expect(readPath("rating", {})).not.toContain("p6alice");
  });

  it("falls back to the JWT subject when the session has no userId", () => {
    const claims = Buffer.from(JSON.stringify({ sub: "from-the-token", sid: "s" })).toString("base64url");
    useSession({ token: `h.${claims}.sig`, user: "p6alice" });
    expect(readPath("rating", {})).toBe("/players/from-the-token/rating");
  });

  it("still lets an explicit --player win", () => {
    useSession({ token: "t", user: "p6alice", userId: "uuid-1" });
    expect(readPath("rating", { player: "someone-else" })).toBe("/players/someone-else/rating");
  });
});
