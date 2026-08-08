// Redis adapter for the session cache. Key prefix `session:*` is the one the architecture
// allocates to this context (docs/architecture/03-persistence-layer.md §7.1) — no other prefix is
// touched here.
//
// Every operation swallows its error and reports it: Redis is a cache, and losing it must slow
// `whoami` down (falling back to Postgres) rather than break authentication.

import Redis from "ioredis";
import type { SessionCache } from "./sessions.js";

const key = (sessionId: string) => `session:${sessionId}`;

export function createRedis(url: string): Redis {
  const redis = new Redis(url, {
    maxRetriesPerRequest: 1,
    // Fail the command instead of queueing it while the connection is down — a queued command
    // would turn a Redis outage into hanging requests.
    enableOfflineQueue: false,
  });
  // Without a listener ioredis escalates connection errors to an uncaught exception.
  redis.on("error", () => undefined);
  return redis;
}

export class RedisCache implements SessionCache {
  constructor(
    private readonly redis: Redis,
    private readonly onFailure: () => void,
  ) {}

  async put(sessionId: string, playerId: string, ttlSeconds: number): Promise<void> {
    try {
      await this.redis.set(key(sessionId), playerId, "EX", ttlSeconds);
    } catch {
      this.onFailure();
    }
  }

  async get(sessionId: string): Promise<string | undefined> {
    try {
      return (await this.redis.get(key(sessionId))) ?? undefined;
    } catch {
      this.onFailure();
      return undefined;
    }
  }

  async drop(sessionId: string): Promise<void> {
    try {
      await this.redis.del(key(sessionId));
    } catch {
      this.onFailure();
    }
  }
}
