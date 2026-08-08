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
  `create table if not exists sessions (
     session_id          uuid primary key,
     player_id           uuid not null references players (player_id) on delete cascade,
     is_active           boolean not null default true,
     created_at          timestamptz not null default now(),
     expires_at          timestamptz not null,
     invalidated_at      timestamptz,
     invalidation_reason text
   )`,
  // Single-active-session is a domain non-negotiable, so it is a constraint rather than a hope:
  // even if the login path were ever reordered, two live sessions for one player cannot exist.
  `create unique index if not exists sessions_one_active_per_player on sessions (player_id) where is_active`,
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
