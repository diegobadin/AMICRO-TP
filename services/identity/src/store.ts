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
}

export class MemoryStore implements Store {
  private readonly byName = new Map<string, Player>();
  private readonly byId = new Map<string, Player>();

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
}
