// Against a real Redis. The dedup IS `SADD`'s return value and the TTL IS a Redis behaviour — a
// fake would only test the fake. Without TEST_REDIS_URL these fail rather than skip: a
// silently-skipped proof is worse than no proof (the rule room-gameplay's suite already follows).

import Redis from "ioredis";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { Broker } from "../src/broker.js";
import { project } from "../src/consumer.js";
import { Store, TTL_SECONDS } from "../src/store.js";
import { type PublicEvent, emptyView } from "../src/view.js";

const url = process.env.TEST_REDIS_URL;
if (!url) throw new Error("TEST_REDIS_URL is required — this suite proves nothing without a Redis");

const redis = new Redis(url);
const store = new Store(redis);
const ROOM = "test-room";

beforeEach(async () => {
  await redis.del(
    `spectator:room:${ROOM}`,
    `spectator:room:${ROOM}:seen`,
    `spectator:room:${ROOM}:subs`,
  );
});

afterAll(async () => {
  await redis.quit();
});

function event(type: string, sequenceNumber: number, fields: Record<string, unknown> = {}): PublicEvent {
  return { type, roomId: ROOM, sequenceNumber, ...fields };
}

describe("the dedup set", () => {
  it("claims a sequence number once", async () => {
    expect(await store.claim(ROOM, 5)).toBe(true);
    expect(await store.claim(ROOM, 5)).toBe(false);
  });

  it("claims out-of-order numbers independently", async () => {
    // The whole reason this is a set and not a high-water mark: seq 90 arriving first must not
    // consume seq 88, which is still in flight on the other topic.
    expect(await store.claim(ROOM, 90)).toBe(true);
    expect(await store.claim(ROOM, 88)).toBe(true);
    expect(await store.claim(ROOM, 88)).toBe(false);
  });

  it("gives a claim back when the apply did not land", async () => {
    await store.claim(ROOM, 5);
    await store.release(ROOM, 5);
    expect(await store.claim(ROOM, 5)).toBe(true);
  });
});

describe("the view", () => {
  it("round-trips", async () => {
    const view = { ...emptyView(ROOM), lastSequence: 12, topCard: "R5" };
    await store.write(view);
    expect(await store.read(ROOM)).toEqual(view);
  });

  it("is null for a room nobody has projected", async () => {
    expect(await store.read("never-seen")).toBeNull();
  });

  it("refreshes every key's TTL on every write, not on the tidy ending", async () => {
    // P4 expired the room stream on `RoomCompleted` and abandoned rooms lived forever. A TTL keyed
    // to the ending reclaims exactly the rooms that would have been cleaned up anyway.
    await store.claim(ROOM, 1);
    await store.addSpectator(ROOM, "carol");
    await store.write({ ...emptyView(ROOM), lastSequence: 1 });
    for (const key of [`spectator:room:${ROOM}`, `spectator:room:${ROOM}:seen`, `spectator:room:${ROOM}:subs`]) {
      const ttl = await redis.ttl(key);
      expect(ttl).toBeGreaterThan(0);
      expect(ttl).toBeLessThanOrEqual(TTL_SECONDS);
    }
  });
});

describe("spectator subscriptions", () => {
  it("counts distinct spectators", async () => {
    expect(await store.addSpectator(ROOM, "carol")).toBe(1);
    expect(await store.addSpectator(ROOM, "dave")).toBe(2);
    expect(await store.addSpectator(ROOM, "carol")).toBe(2);
    expect(await store.removeSpectator(ROOM, "carol")).toBe(1);
  });
});

describe("projecting a message", () => {
  it("applies an event and tells the room's spectators", async () => {
    const broker = new Broker();
    const seen: number[] = [];
    broker.subscribe(ROOM, (update) => seen.push(update.view.lastSequence));

    const outcome = await project(
      "room.public.events",
      event("PlayerJoined", 1, { playerId: "alice", playerCount: 1 }),
      store,
      broker,
    );
    expect(outcome).toBe("projected");
    expect(seen).toEqual([1]);
    expect((await store.read(ROOM))?.players.map((p) => p.id)).toEqual(["alice"]);
  });

  it("drops a redelivery without re-applying it", async () => {
    const broker = new Broker();
    const message = event("PlayerJoined", 1, { playerId: "alice", playerCount: 1 });
    expect(await project("room.public.events", message, store, broker)).toBe("projected");
    expect(await project("room.public.events", message, store, broker)).toBe("duplicate");
    expect((await store.read(ROOM))?.players).toHaveLength(1);
  });

  it("refuses an event carrying a private field and does not project it", async () => {
    const broker = new Broker();
    const leaked = event("GameStarted", 4, { seed: 987654321, playerOrder: ["alice"] });
    expect(await project("room.public.events", leaked, store, broker)).toBe("rejected");
    expect(await store.read(ROOM)).toBeNull();
  });

  it("refuses a body without the two identity fields", async () => {
    const broker = new Broker();
    const malformed = { type: "CardPlayed" } as unknown as PublicEvent;
    expect(await project("room.public.events", malformed, store, broker)).toBe("malformed");
  });

  it("keeps the spectator count on the view it writes", async () => {
    const broker = new Broker();
    await store.addSpectator(ROOM, "carol");
    await project("room.public.events", event("PlayerJoined", 1, { playerId: "alice" }), store, broker);
    expect((await store.read(ROOM))?.spectatorCount).toBe(1);
  });

  it("survives a lifecycle event overtaking an earlier public one", async () => {
    // The end-to-end version of the interleaving problem, through the real dedup rather than the
    // projection alone: `GameCompleted` at seq 9 is delivered on the lifecycle topic BEFORE
    // `CardPlayed` at seq 5 arrives on the public one. Both must be applied — a high-water mark
    // would swallow seq 5 for good, and this is the test that catches it.
    const broker = new Broker();
    const completed = event("GameCompleted", 9, {
      roomType: "CASUAL",
      gameNumber: 1,
      finishingOrder: ["alice", "bob"],
      cardPointTotals: { alice: 0, bob: 17 },
      isAbandoned: false,
    });
    const played = event("CardPlayed", 5, {
      playerId: "alice",
      card: "R5",
      newDiscardTop: "R5",
      playerCardCount: 6,
      nextPlayerId: "bob",
    });

    expect(await project("room.lifecycle.events", completed, store, broker)).toBe("projected");
    expect(await project("room.public.events", played, store, broker)).toBe("projected");

    const view = await store.read(ROOM);
    expect(view?.topCard).toBe("R5"); // the late public event was applied, not dropped
    expect(view?.status).toBe("COMPLETED"); // and did not reopen the room
    expect(view?.currentTurn).toBeNull();
    expect(view?.lastSequence).toBe(9);
  });
});
