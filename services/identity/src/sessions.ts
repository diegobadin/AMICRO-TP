// Sessions: an HS256 JWT the gateway can validate locally, plus the revocation check the
// architecture calls for (§5.3) — Redis `session:{sessionId}` as the fast path, the `sessions`
// table as the introspection fallback. Never the other way round: a token revoked in the database
// must not become valid again just because Redis is down.

import { randomUUID } from "node:crypto";
import { SignJWT, jwtVerify } from "jose";
import { NoEvents, type SessionEvents } from "./events.js";
import type { Store } from "./store.js";

export interface SessionCache {
  put(sessionId: string, playerId: string, ttlSeconds: number): Promise<void>;
  /** playerId when the session is known-active, undefined when unknown — never "known-revoked". */
  get(sessionId: string): Promise<string | undefined>;
  drop(sessionId: string): Promise<void>;
}

export class MemoryCache implements SessionCache {
  private readonly entries = new Map<string, string>();
  async put(sessionId: string, playerId: string): Promise<void> {
    this.entries.set(sessionId, playerId);
  }
  async get(sessionId: string): Promise<string | undefined> {
    return this.entries.get(sessionId);
  }
  async drop(sessionId: string): Promise<void> {
    this.entries.delete(sessionId);
  }
}

export interface OpenedSession {
  token: string;
  sessionId: string;
  supersededSessionId?: string;
}

export interface ResolvedSession {
  playerId: string;
  sessionId: string;
}

export class Sessions {
  private readonly key: Uint8Array;

  constructor(
    private readonly store: Store,
    private readonly cache: SessionCache,
    secret: string,
    private readonly ttlSeconds: number,
    private readonly events: SessionEvents = new NoEvents(),
  ) {
    this.key = new TextEncoder().encode(secret);
  }

  async open(playerId: string): Promise<OpenedSession> {
    const sessionId = randomUUID();
    const expiresAt = new Date(Date.now() + this.ttlSeconds * 1000);
    const supersededSessionId = await this.store.openSession(playerId, sessionId, expiresAt);
    if (supersededSessionId) {
      await this.cache.drop(supersededSessionId);
      await this.events.invalidated(playerId, supersededSessionId, sessionId, "superseded");
    }
    await this.cache.put(sessionId, playerId, this.ttlSeconds);
    const token = await new SignJWT({ sid: sessionId })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(playerId)
      .setIssuedAt()
      .setExpirationTime(Math.floor(expiresAt.getTime() / 1000))
      .sign(this.key);
    return { token, sessionId, supersededSessionId };
  }

  async resolve(token: string | undefined): Promise<ResolvedSession | undefined> {
    if (!token) return undefined;
    let playerId: string;
    let sessionId: string;
    try {
      const { payload } = await jwtVerify(token, this.key);
      if (typeof payload.sub !== "string" || typeof payload.sid !== "string") return undefined;
      playerId = payload.sub;
      sessionId = payload.sid;
    } catch {
      return undefined; // bad signature, expired, or signed by another cluster's secret
    }
    const cached = await this.cache.get(sessionId);
    if (cached === playerId) return { playerId, sessionId };
    if (!(await this.store.isSessionActive(sessionId))) return undefined;
    await this.cache.put(sessionId, playerId, this.ttlSeconds);
    return { playerId, sessionId };
  }

  // Logout invalidates a session too, so the gateway must hear about it on the same channel it
  // already listens to for supersession — otherwise a logged-out client keeps its live stream.
  async close(playerId: string, sessionId: string, reason: string): Promise<boolean> {
    const closed = await this.store.closeSession(sessionId, reason);
    await this.cache.drop(sessionId);
    if (closed) await this.events.invalidated(playerId, sessionId, null, reason);
    return closed;
  }
}
