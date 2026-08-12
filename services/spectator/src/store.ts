// The Redis read model of persistence-layer §5. Spectator has no database and does not want one:
// the whole context is a projection, rebuildable from Kafka, and its keys expire.
//
// The dedup is a SET of applied sequence numbers, not the "last processed sequence number"
// high-water mark §5 specifies. That is a deliberate correction, and the reason is the two topics:
// a room's log is split across `room.public.events` and `room.lifecycle.events`, per-room ordering
// holds WITHIN a topic, and there is no ordering between them. A mark pushed to 90 by a lifecycle
// event would drop the public event at 88 that had not arrived yet — silently, forever. A set
// cannot make that mistake.

import type Redis from "ioredis";
import type { SpectatorView } from "./view.js";

/** 24h after the last update, per persistence-layer §5.2. */
export const TTL_SECONDS = 24 * 60 * 60;

const viewKey = (roomId: string) => `spectator:room:${roomId}`;
const seenKey = (roomId: string) => `spectator:room:${roomId}:seen`;
const subsKey = (roomId: string) => `spectator:room:${roomId}:subs`;

export class Store {
  constructor(private readonly redis: Redis) {}

  /**
   * Claim a sequence number for this room. `true` means it is ours to apply; `false` means a
   * redelivery we have already accounted for. `SADD` is the whole mechanism — one round trip, and
   * the answer is atomic even if a second consumer ever exists.
   */
  async claim(roomId: string, sequenceNumber: number): Promise<boolean> {
    const added = await this.redis.sadd(seenKey(roomId), String(sequenceNumber));
    return added === 1;
  }

  /** Undo a claim whose apply did not land, so the redelivery is not mistaken for a duplicate. */
  async release(roomId: string, sequenceNumber: number): Promise<void> {
    await this.redis.srem(seenKey(roomId), String(sequenceNumber));
  }

  async read(roomId: string): Promise<SpectatorView | null> {
    const raw = await this.redis.hget(viewKey(roomId), "view");
    return raw ? (JSON.parse(raw) as SpectatorView) : null;
  }

  /**
   * Write the view and push every one of this room's keys forward.
   *
   * The TTL is refreshed on every write, never keyed to the tidy ending. P4 expired the room stream
   * on `RoomCompleted` and abandoned rooms lived in Redis forever — a TTL that only fires when the
   * room ends reclaims exactly the rooms that would have been cleaned up anyway.
   */
  async write(view: SpectatorView): Promise<void> {
    const pipeline = this.redis.multi();
    pipeline.hset(viewKey(view.roomId), "view", JSON.stringify(view), "seq", view.lastSequence);
    pipeline.expire(viewKey(view.roomId), TTL_SECONDS);
    pipeline.expire(seenKey(view.roomId), TTL_SECONDS);
    pipeline.expire(subsKey(view.roomId), TTL_SECONDS);
    await pipeline.exec();
  }

  async addSpectator(roomId: string, spectatorId: string): Promise<number> {
    const pipeline = this.redis.multi();
    pipeline.sadd(subsKey(roomId), spectatorId);
    pipeline.expire(subsKey(roomId), TTL_SECONDS);
    pipeline.scard(subsKey(roomId));
    const results = await pipeline.exec();
    return Number(results?.[2]?.[1] ?? 0);
  }

  async removeSpectator(roomId: string, spectatorId: string): Promise<number> {
    const pipeline = this.redis.multi();
    pipeline.srem(subsKey(roomId), spectatorId);
    pipeline.scard(subsKey(roomId));
    const results = await pipeline.exec();
    return Number(results?.[1]?.[1] ?? 0);
  }

  async spectatorCount(roomId: string): Promise<number> {
    return this.redis.scard(subsKey(roomId));
  }
}
