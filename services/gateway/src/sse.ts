// The realtime tier. room-gameplay appends every committed event to `room:{id}:events` with the
// sequence number as the entry id; this reads those streams and fans them out to the players
// watching, as Server-Sent Events (Architecture §1.1 — REST + SSE, no WebSocket).
//
// Three guards, each for a different way a client can end up believing something false:
//
//   the gap check — a frame lost between two others; the client sees the jump and re-reads.
//   the `resync` frame — the stream cannot be trusted to carry the client forward: a
//     `Last-Event-ID` older than what Redis still holds, so the replay would silently start
//     mid-game, or a tail that cannot read Redis at all.
//   the heartbeat — a lost *last* frame, with nothing after it to reveal the hole. That is
//     `GameCompleted`: a client that misses it waits forever. The heartbeat carries the room's
//     latest sequence number, so being behind is visible within one interval.

import type Redis from "ioredis";

export const HEARTBEAT_MS = 15_000;
/** How long a blocking read waits before it is re-issued with the current set of rooms. */
const BLOCK_MS = 1_000;

const streamKey = (roomId: string) => `room:${roomId}:events`;

interface Frame {
  id?: number;
  event: string;
  data: unknown;
}

/** SSE wire format: `id`, `event`, one `data` line, blank line to end the frame. */
export function encodeFrame(frame: Frame): string {
  const id = frame.id === undefined ? "" : `id: ${frame.id}\n`;
  return `${id}event: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`;
}

/**
 * A Redis stream entry as ioredis returns it: `[id, [field, value, field, value, ...]]`. The id
 * is `{sequenceNumber}-0`, which is the whole point — it makes `Last-Event-ID` a stream position.
 */
export function toFrame(id: string, fields: string[]): Frame {
  const map: Record<string, string> = {};
  for (let i = 0; i + 1 < fields.length; i += 2) map[fields[i]] = fields[i + 1];
  const seq = Number(map.seq ?? id.split("-")[0]);
  let payload: Record<string, unknown>;
  try {
    payload = JSON.parse(map.payload ?? "{}") as Record<string, unknown>;
  } catch {
    payload = {};
  }
  // The event name is the domain event's own name, the one in `room_events.type` and in the
  // architecture's catalog. Control frames below are hyphenated, so a client can tell at a glance
  // which frames came from the game and which from the gateway.
  return { id: seq, event: map.type ?? "event", data: { ...payload, seq } };
}

export interface Connection {
  write(chunk: string): void;
  end(): void;
}

class Subscriber {
  /**
   * Frames the tail delivered while the replay was still being written. Without this they would
   * overtake it — the replay awaits Redis, and the tail is free to run in that gap — and the client
   * would see frame 10 before frames 5 to 9.
   */
  private readonly pending: Frame[] = [];
  private catchingUp = true;

  constructor(
    readonly sessionId: string,
    private readonly connection: Connection,
    public lastSent: number,
  ) {}

  send(frame: Frame): boolean {
    if (frame.id !== undefined) {
      if (frame.id <= this.lastSent) return false; // already written, or older than we joined
      if (this.catchingUp) {
        this.pending.push(frame);
        return false;
      }
      this.lastSent = frame.id;
    }
    this.connection.write(encodeFrame(frame));
    return true;
  }

  /** Called once the replay is written: anything that arrived meanwhile goes out in order. */
  caughtUp(): void {
    this.catchingUp = false;
    for (const frame of this.pending.splice(0)) this.send(frame);
  }

  close(): void {
    this.connection.end();
  }
}

interface StreamHooks {
  delivered(count: number): void;
  connections(count: number): void;
  log(action: string, fields: Record<string, unknown>): void;
}

export class RoomStreams {
  private readonly rooms = new Map<string, Set<Subscriber>>();
  /** Last entry id handed to the tail, per room — where the next blocking read resumes from. */
  private readonly cursors = new Map<string, string>();
  private tailing = false;
  private stopped = false;

  constructor(
    private readonly redis: Redis,
    private readonly tail: Redis,
    private readonly hooks: StreamHooks,
  ) {}

  get connections(): number {
    let total = 0;
    for (const subscribers of this.rooms.values()) total += subscribers.size;
    return total;
  }

  /**
   * Attaches a connection to a room and replays whatever it missed.
   *
   * `lastEventId` is the client's own baseline — the sequence number of the state read it did
   * before connecting (D15). Subscribing at the tail and reading the state afterwards would drop
   * everything committed in between, which is invisible by hand and fatal in a two-player drill.
   */
  async subscribe(
    roomId: string,
    sessionId: string,
    connection: Connection,
    lastEventId: number | undefined,
  ): Promise<() => void> {
    const key = streamKey(roomId);
    const latest = await this.latestSequence(key);
    const from = lastEventId ?? latest;

    const subscriber = new Subscriber(sessionId, connection, from);
    const subscribers = this.rooms.get(roomId) ?? new Set<Subscriber>();
    if (!this.rooms.has(roomId)) {
      this.rooms.set(roomId, subscribers);
      this.cursors.set(roomId, `${latest}-0`);
    }
    subscribers.add(subscriber);
    this.hooks.connections(this.connections);
    this.startTail();

    if (lastEventId !== undefined) await this.replay(key, subscriber, lastEventId);
    subscriber.caughtUp();

    const heartbeat = setInterval(() => {
      subscriber.send({ event: "heartbeat", data: { seq: this.cursorSequence(roomId) } });
    }, HEARTBEAT_MS);
    heartbeat.unref?.();

    return () => {
      clearInterval(heartbeat);
      subscribers.delete(subscriber);
      if (subscribers.size === 0) {
        this.rooms.delete(roomId);
        this.cursors.delete(roomId);
      }
      this.hooks.connections(this.connections);
    };
  }

  /** Closes every stream held by a session identity has killed (Architecture §5.5). */
  kill(sessionId: string): number {
    let closed = 0;
    for (const subscribers of this.rooms.values()) {
      for (const subscriber of subscribers) {
        if (subscriber.sessionId !== sessionId) continue;
        subscriber.send({ event: "session-invalidated", data: { reason: "superseded" } });
        subscriber.close();
        closed++;
      }
    }
    return closed;
  }

  stop(): void {
    this.stopped = true;
  }

  /** A control frame to everyone watching, whatever room they are in. */
  private broadcast(frame: Frame): void {
    for (const subscribers of this.rooms.values()) {
      for (const subscriber of subscribers) subscriber.send(frame);
    }
  }

  private cursorSequence(roomId: string): number {
    return Number((this.cursors.get(roomId) ?? "0-0").split("-")[0]);
  }

  private async latestSequence(key: string): Promise<number> {
    const last = await this.redis.xrevrange(key, "+", "-", "COUNT", 1);
    return last.length === 0 ? 0 : Number(last[0][0].split("-")[0]);
  }

  /**
   * Everything after `lastEventId`. If Redis no longer holds the entry right after it, the replay
   * would start mid-game with no way for the client to know — so it gets told to re-read instead.
   */
  private async replay(key: string, subscriber: Subscriber, lastEventId: number): Promise<void> {
    const missed = await this.redis.xrange(key, `(${lastEventId}-0`, "+");
    const oldest = missed.length === 0 ? undefined : Number(missed[0][0].split("-")[0]);
    if (oldest !== undefined && oldest > lastEventId + 1) {
      subscriber.send({ event: "resync", data: { reason: "trimmed", from: lastEventId, oldest } });
      subscriber.lastSent = oldest - 1;
    }
    for (const [id, fields] of missed) subscriber.send(toFrame(id, fields));
  }

  private startTail(): void {
    if (this.tailing) return;
    this.tailing = true;
    void this.loop().finally(() => {
      this.tailing = false;
    });
  }

  /**
   * One blocking read across every subscribed room, re-issued with the current set. A read per room
   * would mean a connection per room, and a room set that changes between reads is exactly what a
   * short block handles for free.
   */
  private async loop(): Promise<void> {
    // Scoped to this run rather than to the object: the loop ends when the last subscriber leaves,
    // and the next one to arrive deserves to be told afresh if Redis is still gone.
    let toldThemItIsDown = false;
    while (!this.stopped && this.rooms.size > 0) {
      const roomIds = [...this.rooms.keys()];
      const ids = roomIds.map((roomId) => this.cursors.get(roomId) ?? "0-0");
      let batches: [string, [string, string[]][]][] | null;
      try {
        batches = (await this.tail.xread(
          "BLOCK",
          BLOCK_MS,
          "STREAMS",
          ...roomIds.map(streamKey),
          ...ids,
        )) as [string, [string, string[]][]][] | null;
      } catch (e) {
        this.hooks.log("stream-tail-failed", { error: String(e) });
        // The tail is the only thing that can notice Redis has gone: the heartbeat reports a
        // cursor this process holds in memory, so it keeps ticking with a sequence number that
        // has stopped moving, and a client watching a room that advances meanwhile is told
        // nothing. One `resync` per outage puts it back on the REST read, which still works.
        if (!toldThemItIsDown) {
          toldThemItIsDown = true;
          this.broadcast({ event: "resync", data: { reason: "stream-unavailable" } });
        }
        await new Promise((resolve) => setTimeout(resolve, BLOCK_MS));
        continue;
      }
      toldThemItIsDown = false;
      if (!batches) continue;

      for (const [key, entries] of batches) {
        const roomId = key.slice("room:".length, key.length - ":events".length);
        const subscribers = this.rooms.get(roomId);
        if (entries.length > 0) this.cursors.set(roomId, entries[entries.length - 1][0]);
        if (!subscribers) continue;
        for (const [id, fields] of entries) {
          const frame = toFrame(id, fields);
          let sent = 0;
          for (const subscriber of subscribers) if (subscriber.send(frame)) sent++;
          this.hooks.delivered(sent);
        }
      }
    }
  }
}
