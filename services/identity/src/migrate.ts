// Schema creation runs at startup, under an advisory lock so several replicas cannot race each
// other, and before the server listens — serving requests against a missing schema is worse than
// a restart. Every statement is idempotent, so later phases append rather than rewrite.

import type pg from "pg";

// Arbitrary, identity-specific: any other service taking a lock picks a different number.
const LOCK_ID = 81420261;

const STATEMENTS = [
  `create table if not exists players (
     player_id     uuid primary key,
     username      text not null,
     password_hash text not null,
     created_at    timestamptz not null default now()
   )`,
  // Case-insensitive uniqueness without the citext extension: creating extensions needs
  // privileges a per-service role has no business holding.
  `create unique index if not exists players_username_lower_idx on players (lower(username))`,
];

export async function migrate(pool: pg.Pool): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query("select pg_advisory_lock($1)", [LOCK_ID]);
    for (const statement of STATEMENTS) await client.query(statement);
    await client.query("select pg_advisory_unlock($1)", [LOCK_ID]);
  } finally {
    client.release();
  }
}
