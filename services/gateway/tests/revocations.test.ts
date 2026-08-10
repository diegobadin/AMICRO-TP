import { EventEmitter } from "node:events";
import type Redis from "ioredis";
import { describe, expect, it } from "vitest";
import { CHANNEL_PATTERN, Revocations, listen, parse } from "../src/revocations.js";

// Enough of ioredis to hold the connection lifecycle under test: `status`, the events, and a
// psubscribe that records what it was asked for.
class FakeRedis extends EventEmitter {
  status = "connecting";
  readonly subscribed: string[] = [];
  async psubscribe(pattern: string): Promise<number> {
    if (this.status !== "ready") throw new Error("Stream isn't writeable and enableOfflineQueue options is false");
    this.subscribed.push(pattern);
    return this.subscribed.length;
  }
  ready(): void {
    this.status = "ready";
    this.emit("ready");
  }
}

describe("revocations", () => {
  it("refuses a session from the moment identity kills it", () => {
    const dead = new Revocations(1000);
    expect(dead.has("s1")).toBe(false);
    dead.revoke("s1");
    expect(dead.has("s1")).toBe(true);
  });

  it("forgets an entry once no token could still carry it", () => {
    let now = 0;
    const dead = new Revocations(1000, () => now);
    dead.revoke("s1");
    now = 1001;
    expect(dead.has("s1")).toBe(false);
    // Swept on read, so a long-lived pod does not accumulate every session it ever refused.
    expect(dead.size).toBe(0);
  });
});

describe("subscribing", () => {
  it("waits for the connection instead of subscribing into a closed socket", () => {
    const redis = new FakeRedis();
    const errors: unknown[] = [];
    listen(redis as unknown as Redis, () => undefined, (e) => errors.push(e));

    // The bug this test exists for: subscribing at construction time is rejected, quietly, and the
    // gateway then serves happily while never hearing a single session kill.
    expect(redis.subscribed).toEqual([]);
    expect(errors).toEqual([]);

    redis.ready();
    expect(redis.subscribed).toEqual([CHANNEL_PATTERN]);
  });

  it("re-arms after a reconnect", () => {
    const redis = new FakeRedis();
    listen(redis as unknown as Redis, () => undefined, () => undefined);
    redis.ready();
    redis.ready();
    expect(redis.subscribed).toHaveLength(2);
  });

  it("delivers a parsed invalidation to the handler", () => {
    const redis = new FakeRedis();
    const seen: string[] = [];
    listen(redis as unknown as Redis, (e) => seen.push(e.oldSessionId), () => undefined);
    redis.ready();
    redis.emit("pmessage", CHANNEL_PATTERN, "session:invalidated:p1", JSON.stringify({ oldSessionId: "s-old", newSessionId: "s-new" }));
    expect(seen).toEqual(["s-old"]);
  });
});

describe("the invalidation message identity has been publishing since P2", () => {
  it("reads the player from the channel and the session from the payload", () => {
    const event = parse("session:invalidated:player-9", JSON.stringify({ oldSessionId: "s-old", newSessionId: "s-new" }));
    expect(event).toEqual({ playerId: "player-9", oldSessionId: "s-old", newSessionId: "s-new" });
  });

  it("treats a logout — no successor session — as an invalidation too", () => {
    const event = parse("session:invalidated:player-9", JSON.stringify({ oldSessionId: "s-old", newSessionId: null }));
    expect(event?.newSessionId).toBeNull();
  });

  it("returns nothing for a payload it cannot read, rather than throwing on the subscriber thread", () => {
    expect(parse("session:invalidated:p1", "not json")).toBeUndefined();
    expect(parse("session:invalidated:p1", "{}")).toBeUndefined();
  });
});
