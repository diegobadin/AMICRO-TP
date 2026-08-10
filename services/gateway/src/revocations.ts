// A session can die before its token expires: a second login supersedes it, or the player logs
// out. identity publishes both on Redis pub/sub `session:invalidated:{playerId}` — the channel it
// has been writing to since P2 with nobody listening (Architecture §5.5). The gateway is the
// listener it was written for.
//
// The set is in memory and per pod, so a restart accepts a superseded token again until it
// expires. That is the accepted limit of P4 (requirements R4): room-gameplay still disconnects the
// player through `identity.session-events`, and the authoritative alternative — asking identity on
// every request — buys a hop per request for a hole no demo can reach.

import type Redis from "ioredis";

export const CHANNEL_PATTERN = "session:invalidated:*";

export interface Invalidation {
  playerId: string;
  oldSessionId: string;
  newSessionId: string | null;
}

export class Revocations {
  private readonly dead = new Map<string, number>();

  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Entries die with the token they describe, so the map cannot grow without bound. */
  revoke(sessionId: string): void {
    this.dead.set(sessionId, this.now() + this.ttlMs);
  }

  has(sessionId: string): boolean {
    const expiry = this.dead.get(sessionId);
    if (expiry === undefined) return false;
    if (expiry > this.now()) return true;
    this.dead.delete(sessionId);
    return false;
  }

  get size(): number {
    return this.dead.size;
  }
}

export function parse(channel: string, message: string): Invalidation | undefined {
  const playerId = channel.slice(channel.lastIndexOf(":") + 1);
  try {
    const body = JSON.parse(message) as { oldSessionId?: unknown; newSessionId?: unknown };
    if (typeof body.oldSessionId !== "string" || !playerId) return undefined;
    return {
      playerId,
      oldSessionId: body.oldSessionId,
      newSessionId: typeof body.newSessionId === "string" ? body.newSessionId : null,
    };
  } catch {
    return undefined;
  }
}

/**
 * Subscribes and hands each invalidation to `onInvalidation` — which revokes the session here and,
 * from F4 on, closes the streams that session is holding open.
 *
 * Subscribing happens on `ready`, never eagerly: at startup the socket is usually not open yet, and
 * a client that does not queue offline commands rejects the subscribe outright. That failure is
 * quiet — the process keeps serving, and every session kill from then on is simply never heard.
 * `ready` fires again after a reconnect, which is also when the subscription has to be re-armed.
 */
export function listen(redis: Redis, onInvalidation: (event: Invalidation) => void, onError: (e: unknown) => void): void {
  const subscribe = () => {
    redis.psubscribe(CHANNEL_PATTERN).catch(onError);
  };
  redis.on("ready", subscribe);
  if (redis.status === "ready") subscribe();

  redis.on("pmessage", (_pattern: string, channel: string, message: string) => {
    const event = parse(channel, message);
    if (event) onInvalidation(event);
    else onError(new Error(`unreadable invalidation on ${channel}`));
  });
}
