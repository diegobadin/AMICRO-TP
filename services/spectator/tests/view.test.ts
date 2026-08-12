import { describe, expect, it } from "vitest";
import { type PublicEvent, apply, emptyView } from "../src/view.js";

const ROOM = "1c1b0b7e-0000-4000-8000-000000000000";

let seq = 0;
function event(type: string, fields: Record<string, unknown> = {}): PublicEvent {
  return { type, roomId: ROOM, sequenceNumber: ++seq, at: "2026-08-12T12:00:00Z", ...fields };
}

/** A whole casual game, in the order room-gameplay commits it. */
function gameLog(): PublicEvent[] {
  seq = 0;
  return [
    event("RoomCreated", { roomType: "CASUAL", creatorId: "alice", maxPlayers: 4 }),
    event("PlayerJoined", { playerId: "alice", playerCount: 1 }),
    event("PlayerJoined", { playerId: "bob", playerCount: 2 }),
    event("GameStarted", {
      gameNumber: 1,
      playerOrder: ["alice", "bob"],
      initialDiscardCard: "R7",
      initialColor: "RED",
      turnTimeoutSeconds: 30,
    }),
    event("CardPlayed", {
      playerId: "alice",
      card: "R5",
      newDiscardTop: "R5",
      playerCardCount: 6,
      chosenColor: null,
      nextPlayerId: "bob",
    }),
    event("CardDrawn", { playerId: "bob", newCardCount: 8 }),
    event("TurnPassed", { playerId: "bob", nextPlayerId: "alice" }),
    event("UnoCallMade", { playerId: "alice" }),
    event("GameCompleted", {
      roomType: "CASUAL",
      gameNumber: 1,
      finishingOrder: ["alice", "bob"],
      cardPointTotals: { alice: 0, bob: 17 },
      isAbandoned: false,
      completedAt: "2026-08-12T12:05:00Z",
    }),
    event("RoomCompleted", { roomType: "CASUAL", finalResults: ["alice", "bob"] }),
  ];
}

function project(events: PublicEvent[]) {
  return events.reduce(apply, emptyView(ROOM));
}

describe("the projection", () => {
  it("builds a board from a game", () => {
    const view = project(gameLog());
    expect(view.roomType).toBe("CASUAL");
    expect(view.gameNumber).toBe(1);
    expect(view.players.map((p) => p.id)).toEqual(["alice", "bob"]);
    expect(view.topCard).toBe("R5");
    expect(view.finishingOrder).toEqual(["alice", "bob"]);
    expect(view.status).toBe("COMPLETED");
  });

  it("has nowhere to put a hand", () => {
    const view = project(gameLog());
    const serialised = JSON.stringify(view);
    for (const forbidden of ["hand", "deckOrder", "rngSeed", "seed"]) {
      expect(serialised).not.toContain(forbidden);
    }
  });

  it("leaves a card count unknown until an event reveals it", () => {
    // `GameStarted` does not say how many cards were dealt, and guessing 7 here would be a second
    // copy of a rule room-gameplay owns.
    const view = project(gameLog().slice(0, 4));
    expect(view.players.find((p) => p.id === "alice")?.cardCount).toBeNull();
    const afterPlay = project(gameLog().slice(0, 5));
    expect(afterPlay.players.find((p) => p.id === "alice")?.cardCount).toBe(6);
  });

  it("tracks whose turn it is", () => {
    expect(project(gameLog().slice(0, 5)).currentTurn).toBe("bob");
    expect(project(gameLog().slice(0, 7)).currentTurn).toBe("alice");
  });

  it("records a called Uno, which is public information", () => {
    const view = project(gameLog().slice(0, 8));
    expect(view.players.find((p) => p.id === "alice")?.calledUno).toBe(true);
  });

  it("marks a disconnected player without dropping them", () => {
    const log = [
      ...gameLog().slice(0, 4),
      event("PlayerDisconnected", { playerId: "bob", reconnectionDeadline: "2026-08-12T12:01:00Z" }),
    ];
    const view = project(log);
    expect(view.players.find((p) => p.id === "bob")?.isConnected).toBe(false);
    expect(view.players).toHaveLength(2);
  });

  it("expires a room that never filled", () => {
    const view = project([gameLog()[0], event("RoomExpired", { reason: "never_filled" })]);
    expect(view.status).toBe("EXPIRED");
  });
});

describe("cross-topic interleaving", () => {
  // A room's log is split across two topics with no ordering between them, so the projection has to
  // survive a lifecycle event arriving before an earlier public one. These are the orders that are
  // actually possible: per-topic order is preserved, the interleaving between topics is not.
  const LIFECYCLE = new Set(["RoomCreated", "GameCompleted", "RoomCompleted", "RoomExpired"]);

  function interleavings(events: PublicEvent[], count: number): PublicEvent[][] {
    const lifecycle = events.filter((e) => LIFECYCLE.has(e.type));
    const publicEvents = events.filter((e) => !LIFECYCLE.has(e.type));
    const orders: PublicEvent[][] = [];
    for (let n = 0; n < count; n++) {
      const left = [...lifecycle];
      const right = [...publicEvents];
      const merged: PublicEvent[] = [];
      // Deterministic pseudo-random merge, seeded by n, so a failure is reproducible.
      let state = n * 2654435761 + 1;
      while (left.length || right.length) {
        state = (state * 1103515245 + 12345) & 0x7fffffff;
        const takeLeft = right.length === 0 || (left.length > 0 && state % 2 === 0);
        merged.push((takeLeft ? left : right).shift() as PublicEvent);
      }
      orders.push(merged);
    }
    return orders;
  }

  it("reaches the same final view whatever the interleaving", () => {
    const inOrder = project(gameLog());
    for (const order of interleavings(gameLog(), 200)) {
      const view = project(order);
      expect(view.status).toBe(inOrder.status);
      expect(view.finishingOrder).toEqual(inOrder.finishingOrder);
      expect(view.lastSequence).toBe(inOrder.lastSequence);
      expect(view.currentTurn).toBe(inOrder.currentTurn);
      expect(new Set(view.players.map((p) => p.id))).toEqual(
        new Set(inOrder.players.map((p) => p.id)),
      );
    }
  });

  it("never hands a finished room back to a player", () => {
    // The exact failure: GameCompleted (seq 9) applied before CardPlayed (seq 5), which carries a
    // `nextPlayerId`. Without the terminal clamp the room shows bob to move at a table that is done.
    const log = gameLog();
    const completed = log[8];
    const cardPlayed = log[4];
    const view = [completed, cardPlayed].reduce(apply, emptyView(ROOM));
    expect(view.status).toBe("COMPLETED");
    expect(view.currentTurn).toBeNull();
  });

  it("does not reopen a completed room with a late GameStarted", () => {
    const log = gameLog();
    const view = [log[8], log[3]].reduce(apply, emptyView(ROOM));
    expect(view.status).toBe("COMPLETED");
  });
});
