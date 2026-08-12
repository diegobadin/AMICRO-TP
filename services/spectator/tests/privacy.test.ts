import { describe, expect, it } from "vitest";
import { isClean, privateFields } from "../src/privacy.js";
import { encodeFrame, snapshotFrame, updateFrame } from "../src/sse.js";
import { emptyView } from "../src/view.js";

describe("the ACL boundary", () => {
  it("passes a real public payload", () => {
    const body = {
      type: "CardPlayed",
      roomId: "r",
      sequenceNumber: 5,
      playerId: "alice",
      card: "R5",
      newDiscardTop: "R5",
      playerCardCount: 6,
    };
    expect(isClean(body)).toBe(true);
  });

  it("refuses an event carrying a seed", () => {
    // `publicPayload` strips this three services upstream, in the same transaction that writes the
    // event — so this can only fire if that filter has stopped working, which is exactly why the
    // counter beside it is an alert and not noise.
    const leaked = { type: "GameStarted", roomId: "r", sequenceNumber: 4, seed: 123456789 };
    expect(privateFields(leaked)).toEqual(["seed"]);
    expect(isClean(leaked)).toBe(false);
  });

  it("refuses hands, decks and rng seeds by any of their names", () => {
    for (const field of ["hand", "cards", "deckOrder", "rngSeed", "seed"]) {
      expect(isClean({ [field]: "anything" })).toBe(false);
    }
  });
});

describe("SSE frames", () => {
  it("encodes id, event and one data line", () => {
    const frame = encodeFrame({ id: 7, event: "CardPlayed", data: { a: 1 } });
    expect(frame).toBe('id: 7\nevent: CardPlayed\ndata: {"a":1}\n\n');
  });

  it("omits the id when there is none", () => {
    expect(encodeFrame({ event: "heartbeat", data: { seq: -1 } })).toBe(
      'event: heartbeat\ndata: {"seq":-1}\n\n',
    );
  });

  it("a snapshot of an unseen room carries no id", () => {
    expect(snapshotFrame(emptyView("r")).id).toBeUndefined();
  });

  it("an update carries the event to narrate and the view to render", () => {
    const view = { ...emptyView("r"), lastSequence: 9 };
    const event = { type: "CardPlayed", roomId: "r", sequenceNumber: 9 };
    const frame = updateFrame({ event, view });
    expect(frame.id).toBe(9);
    expect(frame.event).toBe("CardPlayed");
    expect(frame.data).toEqual({ event, view });
  });
});
