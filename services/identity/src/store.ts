// Persistence port plus its two adapters: Postgres for the running service, memory for the unit
// tests. Keeping the port here is what lets `npm test` stay a fast, database-free stage while the
// real wiring is proven by the CI smoke and the cluster drills.

import type pg from "pg";

export interface Player {
  playerId: string;
  username: string;
  passwordHash: string;
}

export interface Store {
  /** Returns null when the username is taken — the unique index decides, never a read-then-write. */
  createPlayer(playerId: string, username: string, passwordHash: string): Promise<Player | null>;
  findByUsername(username: string): Promise<Player | undefined>;
  findById(playerId: string): Promise<Player | undefined>;
  /** Replaces the player's active session atomically; returns the session it superseded, if any. */
  openSession(playerId: string, sessionId: string, expiresAt: Date): Promise<string | undefined>;
  /** Returns false when the session was already closed (or never existed). */
  closeSession(sessionId: string, reason: string): Promise<boolean>;
  isSessionActive(sessionId: string): Promise<boolean>;
}

const COLUMNS = 'player_id as "playerId", username, password_hash as "passwordHash"';
const UNIQUE_VIOLATION = "23505";

export class PgStore implements Store {
  constructor(private readonly pool: pg.Pool) {}

  async createPlayer(playerId: string, username: string, passwordHash: string): Promise<Player | null> {
    try {
      const { rows } = await this.pool.query<Player>(
        `insert into players (player_id, username, password_hash) values ($1, $2, $3) returning ${COLUMNS}`,
        [playerId, username, passwordHash],
      );
      return rows[0];
    } catch (e) {
      if ((e as { code?: string }).code === UNIQUE_VIOLATION) return null;
      throw e;
    }
  }

  async findByUsername(username: string): Promise<Player | undefined> {
    const { rows } = await this.pool.query<Player>(
      `select ${COLUMNS} from players where lower(username) = lower($1)`,
      [username],
    );
    return rows[0];
  }

  async findById(playerId: string): Promise<Player | undefined> {
    const { rows } = await this.pool.query<Player>(`select ${COLUMNS} from players where player_id = $1`, [playerId]);
    return rows[0];
  }

  async openSession(playerId: string, sessionId: string, expiresAt: Date): Promise<string | undefined> {
    const client = await this.pool.connect();
    try {
      await client.query("begin");
      // Lock the player first: concurrent logins then queue instead of racing the partial unique
      // index, so the last one committed is the active session rather than an arbitrary error.
      await client.query("select 1 from players where player_id = $1 for update", [playerId]);
      const superseded = await client.query<{ session_id: string }>(
        `update sessions set is_active = false, invalidated_at = now(), invalidation_reason = 'superseded'
         where player_id = $1 and is_active returning session_id`,
        [playerId],
      );
      await client.query(
        "insert into sessions (session_id, player_id, expires_at) values ($1, $2, $3)",
        [sessionId, playerId, expiresAt],
      );
      await client.query("commit");
      return superseded.rows[0]?.session_id;
    } catch (e) {
      await client.query("rollback").catch(() => undefined);
      throw e;
    } finally {
      client.release();
    }
  }

  async closeSession(sessionId: string, reason: string): Promise<boolean> {
    const { rowCount } = await this.pool.query(
      `update sessions set is_active = false, invalidated_at = now(), invalidation_reason = $2
       where session_id = $1 and is_active`,
      [sessionId, reason],
    );
    return (rowCount ?? 0) > 0;
  }

  async isSessionActive(sessionId: string): Promise<boolean> {
    const { rows } = await this.pool.query<{ ok: boolean }>(
      "select (is_active and expires_at > now()) as ok from sessions where session_id = $1",
      [sessionId],
    );
    return rows[0]?.ok === true;
  }
}

interface MemorySession {
  playerId: string;
  isActive: boolean;
  expiresAt: Date;
}

export class MemoryStore implements Store {
  private readonly byName = new Map<string, Player>();
  private readonly byId = new Map<string, Player>();
  private readonly sessions = new Map<string, MemorySession>();

  async createPlayer(playerId: string, username: string, passwordHash: string): Promise<Player | null> {
    if (this.byName.has(username.toLowerCase())) return null;
    const player: Player = { playerId, username, passwordHash };
    this.byName.set(username.toLowerCase(), player);
    this.byId.set(playerId, player);
    return player;
  }

  async findByUsername(username: string): Promise<Player | undefined> {
    return this.byName.get(username.toLowerCase());
  }

  async findById(playerId: string): Promise<Player | undefined> {
    return this.byId.get(playerId);
  }

  async openSession(playerId: string, sessionId: string, expiresAt: Date): Promise<string | undefined> {
    let superseded: string | undefined;
    for (const [id, s] of this.sessions) {
      if (s.playerId === playerId && s.isActive) {
        s.isActive = false;
        superseded = id;
      }
    }
    this.sessions.set(sessionId, { playerId, isActive: true, expiresAt });
    return superseded;
  }

  async closeSession(sessionId: string, _reason: string): Promise<boolean> {
    const s = this.sessions.get(sessionId);
    if (!s?.isActive) return false;
    s.isActive = false;
    return true;
  }

  async isSessionActive(sessionId: string): Promise<boolean> {
    const s = this.sessions.get(sessionId);
    return s !== undefined && s.isActive && s.expiresAt > new Date();
  }
}
