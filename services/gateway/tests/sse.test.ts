import type Redis from "ioredis";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Connection, HEARTBEAT_MS, RoomStreams, encodeFrame, toFrame } from "../src/sse.js";

type Entry = [string, string[]];

const entry = (seq: number, type = "CardPlayed", payload: Record<string, unknown> = {}): Entry => [
  `${seq}-0`,
  ["type", type, "seq", String(seq), "payload", JSON.stringify({ type, ...payload }), "correlationId", "c1"],
];

/** Only the four commands the stream tier issues, over a single in-memory stream. */
class FakeRedis {
  constructor(public entries: Entry[] = []) {}
  private waiting: (() => void)[] = [];

  async xrevrange(_key: string, _plus: string, _minus: string, _count: string, _n: number): Promise<Entry[]> {
    return this.entries.length === 0 ? [] : [this.entries[this.entries.length - 1]];
  }

  async xrange(_key: string, start: string, _end: string): Promise<Entry[]> {
    const exclusive = Number(start.replace("(", "").split("-")[0]);
    return this.entries.filter(([id]) => Number(id.split("-")[0]) > exclusive);
  }

  async xread(...args: unknown[]): Promise<[string, Entry[]][] | null> {
    const ids = args.slice(args.indexOf("STREAMS") + 2) as string[];
    const after = Number(String(ids[0]).split("-")[0]);
    const fresh = this.entries.filter(([id]) => Number(id.split("-")[0]) > after);
    if (fresh.length > 0) return [["room:r1:events", fresh]];
    // A real BLOCK waits on the server. Waiting here too keeps the tail loop from spinning.
    await new Promise<void>((done) => {
      this.waiting.push(done);
      setTimeout(done, 5);
    });
    return null;
  }

  append(e: Entry): void {
    this.entries.push(e);
    this.waiting.splice(0).forEach((done) => done());
  }
}

function connection(): Connection & { chunks: string[]; ended: boolean } {
  const state = {
    chunks: [] as string[],
    ended: false,
    write(chunk: string) {
      state.chunks.push(chunk);
    },
    end() {
      state.ended = true;
    },
  };
  return state;
}

const framesIn = (chunks: string[]) =>
  chunks.map((c) => ({
    id: /^id: (\d+)$/m.exec(c)?.[1],
    event: /^event: (.+)$/m.exec(c)![1],
    data: JSON.parse(/^data: (.+)$/m.exec(c)![1]) as Record<string, unknown>,
  }));

const streamsOf = (redis: FakeRedis) =>
  new RoomStreams(redis as unknown as Redis, redis as unknown as Redis, {
    delivered: () => undefined,
    connections: () => undefined,
    log: () => undefined,
  });

const settle = () => new Promise((r) => setTimeout(r, 30));

let running: RoomStreams | undefined;
afterEach(() => {
  running?.stop();
  running = undefined;
  vi.useRealTimers();
});

describe("the wire format", () => {
  it("is `id`, `event`, one `data` line and a blank line", () => {
    expect(encodeFrame({ id: 42, event: "CardPlayed", data: { seq: 42 } })).toBe(
      'id: 42\nevent: CardPlayed\ndata: {"seq":42}\n\n',
    );
  });

  it("omits the id on control frames, which are not stream positions", () => {
    expect(encodeFrame({ event: "heartbeat", data: { seq: 7 } })).toBe('event: heartbeat\ndata: {"seq":7}\n\n');
  });

  it("names the frame after the domain event and folds the sequence number in", () => {
    const frame = toFrame(...entry(42, "CardPlayed", { playerId: "p1", card: "R5" }));
    expect(frame.event).toBe("CardPlayed");
    expect(frame.id).toBe(42);
    expect(frame.data).toEqual({ type: "CardPlayed", playerId: "p1", card: "R5", seq: 42 });
  });

  it("still produces a frame when the payload cannot be read, rather than killing the tail", () => {
    const frame = toFrame("9-0", ["type", "CardPlayed", "seq", "9", "payload", "{not json"]);
    expect(frame.id).toBe(9);
    expect(frame.data).toEqual({ seq: 9 });
  });
});

describe("subscribing", () => {
  it("without a Last-Event-ID delivers what happens next, not the whole game so far", async () => {
    const redis = new FakeRedis([entry(1), entry(2)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, undefined);
    expect(client.chunks).toEqual([]);

    redis.append(entry(3));
    await settle();
    expect(framesIn(client.chunks).map((f) => f.id)).toEqual(["3"]);
  });

  it("replays exactly what the client missed, in order", async () => {
    const redis = new FakeRedis([entry(1), entry(2), entry(3)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, 1);
    await settle();
    expect(framesIn(client.chunks).map((f) => f.id)).toEqual(["2", "3"]);
  });

  it("does not deliver the same frame twice when it arrives during the replay", async () => {
    const redis = new FakeRedis([entry(1), entry(2), entry(3)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, 1);
    redis.append(entry(4));
    await settle();
    expect(framesIn(client.chunks).map((f) => f.id)).toEqual(["2", "3", "4"]);
  });

  it("says `resync` when Redis no longer holds what the client asked to resume from", async () => {
    // The stream was trimmed: entries 2 and 3 are gone. Replaying from 4 without a word would put
    // the client mid-game with a board it cannot explain.
    const redis = new FakeRedis([entry(4), entry(5)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, 1);
    await settle();
    const frames = framesIn(client.chunks);
    expect(frames[0].event).toBe("resync");
    expect(frames[0].data).toMatchObject({ reason: "trimmed", from: 1, oldest: 4 });
    expect(frames.slice(1).map((f) => f.id)).toEqual(["4", "5"]);
  });

  it("does not cry resync when the replay is simply contiguous", async () => {
    const redis = new FakeRedis([entry(1), entry(2)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, 1);
    await settle();
    expect(framesIn(client.chunks).every((f) => f.event !== "resync")).toBe(true);
  });

  it("fans one event out to every subscriber of the room", async () => {
    const redis = new FakeRedis([entry(1)]);
    running = streamsOf(redis);
    const alice = connection();
    const bob = connection();

    await running.subscribe("r1", "s-a", alice, undefined);
    await running.subscribe("r1", "s-b", bob, undefined);
    redis.append(entry(2));
    await settle();

    expect(framesIn(alice.chunks).map((f) => f.id)).toEqual(["2"]);
    expect(framesIn(bob.chunks).map((f) => f.id)).toEqual(["2"]);
    expect(running.connections).toBe(2);
  });

  it("stops delivering once the client goes away", async () => {
    const redis = new FakeRedis([entry(1)]);
    running = streamsOf(redis);
    const client = connection();

    const detach = await running.subscribe("r1", "s1", client, undefined);
    detach();
    expect(running.connections).toBe(0);

    redis.append(entry(2));
    await settle();
    expect(client.chunks).toEqual([]);
  });
});

describe("a superseded session", () => {
  it("gets a control frame and a closed stream, and nobody else does", async () => {
    const redis = new FakeRedis([entry(1)]);
    running = streamsOf(redis);
    const doomed = connection();
    const other = connection();

    await running.subscribe("r1", "session-old", doomed, undefined);
    await running.subscribe("r1", "session-other", other, undefined);

    expect(running.kill("session-old")).toBe(1);
    expect(framesIn(doomed.chunks)[0].event).toBe("session-invalidated");
    expect(doomed.ended).toBe(true);
    expect(other.chunks).toEqual([]);
    expect(other.ended).toBe(false);
  });
});

describe("the heartbeat", () => {
  it("carries the room's latest sequence number, so a client that missed the last frame notices", async () => {
    vi.useFakeTimers();
    const redis = new FakeRedis([entry(1), entry(2)]);
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, undefined);
    await vi.advanceTimersByTimeAsync(HEARTBEAT_MS + 10);

    const beats = framesIn(client.chunks).filter((f) => f.event === "heartbeat");
    expect(beats.length).toBeGreaterThan(0);
    expect(beats[0].data.seq).toBe(2);
  });
});

describe("a tail that cannot read Redis", () => {
  // The heartbeat cannot cover this one: it reports the cursor this process holds in memory, so
  // during an outage it keeps ticking with a sequence number that has stopped moving and the
  // client hears nothing at all.
  it("tells every client once per outage, and again if Redis goes a second time", async () => {
    vi.useFakeTimers();
    const redis = new FakeRedis([entry(1)]);
    let broken = true;
    redis.xread = async () => {
      if (broken) throw new Error("Connection is closed.");
      await new Promise<void>((done) => setTimeout(done, 5));
      return null;
    };
    running = streamsOf(redis);
    const client = connection();

    await running.subscribe("r1", "s1", client, undefined);
    await vi.advanceTimersByTimeAsync(4000);

    const resync = () => framesIn(client.chunks).filter((f) => f.event === "resync");
    expect(resync()).toHaveLength(1);
    expect(resync()[0].data.reason).toBe("stream-unavailable");

    broken = false;
    await vi.advanceTimersByTimeAsync(2000);
    broken = true;
    await vi.advanceTimersByTimeAsync(4000);
    expect(resync()).toHaveLength(2);
  });
});
