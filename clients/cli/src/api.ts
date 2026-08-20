// Shared plumbing for every command: where the backend is, where the session lives, and the one
// request helper that carries a correlation id and surfaces conditional-request headers.
//
// One base URL, because there is one way in: the gateway (P4). It validates the session, routes
// `/auth/**` to identity and `/rooms/**` to room-gameplay, and serves the room stream itself.

import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { randomUUID } from "node:crypto";

export const API = (process.env.UNOARENA_API_URL ?? "http://localhost:8080").replace(/\/$/, "");
export const SESSION = process.env.UNOARENA_SESSION ?? join(homedir() || tmpdir(), ".unoarena", "session.json");

export interface Session {
  token?: string;
  user?: string;
  userId?: string;
}

// §4: one process is one session is one player identity. `bot` takes its credentials on the command
// line and keeps the token here rather than in the session file, so N parallel bots do not race each
// other over one path on their way into the same game.
let held: Session | null = null;

export function useSession(s: Session): void {
  held = s;
}

export function saveSession(s: Session): void {
  mkdirSync(dirname(SESSION), { recursive: true });
  writeFileSync(SESSION, JSON.stringify(s));
}

export function loadSession(): Session {
  if (held) return held;
  return existsSync(SESSION) ? (JSON.parse(readFileSync(SESSION, "utf8")) as Session) : {};
}

/**
 * Who this process is, as the backend knows them: the player **id**, never the display name.
 * `userId` is what register/login returned; the JWT subject is the fallback, because identity puts
 * the id there and the CLI never has to ask for it.
 *
 * One place, because there is one answer. P6 briefly had a second one in `watch.ts` that returned
 * the *username* — so `rating` with no flags asked for a player id that does not exist and got a
 * confident "1000 after 0 games" back, which is the worst kind of wrong.
 */
export function playerId(): string {
  const session = loadSession();
  if (session.userId) return String(session.userId);
  const payload = session.token?.split(".")[1];
  if (!payload) return "";
  try {
    return String(JSON.parse(Buffer.from(payload, "base64url").toString()).sub ?? "");
  } catch {
    return "";
  }
}

export function parseFlags(argv: string[]): Record<string, string | boolean> {
  const out: Record<string, string | boolean> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith("--")) out[key] = true;
      else { out[key] = next; i++; }
    }
  }
  return out;
}

/**
 * The arguments that are not flags and were not consumed as a flag's value. `Client-Checkpoint.md`
 * §5 writes the canonical forms positionally (`spectate <roomId>`, `tournament status <id>`), so
 * these have to be read as well as the `--` forms — and filtering on `startsWith("--")` alone would
 * hand back `30` from `--timeout 30` as if the faculty had typed a room id.
 */
export function positionals(argv: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) { out.push(a); continue; }
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith("--")) i++;
  }
  return out;
}

// §6 requires every line to carry the same field set, so the faculty can parse one shape across
// every command. Fields that do not apply to an action are present and null, not missing.
export interface Line {
  ts: string;
  action: string;
  room: string | null;
  player: string | null;
  latency_ms: number;
  result: "ok" | "error";
  error_code: string | number | null;
  seq: number | null;
  correlationId: string;
  [k: string]: unknown;
}

export function line(fields: Partial<Line> & { action: string; result: "ok" | "error" }): Line {
  return {
    ts: new Date().toISOString(),
    room: null,
    player: null,
    latency_ms: 0,
    error_code: null,
    seq: null,
    correlationId: randomUUID(),
    ...fields,
  };
}

/**
 * What this process has emitted so far. `bot`'s closing summary line (§6 — total actions, error
 * counts, latency aggregates) is exactly this, so no command has to thread a counter through the
 * call chain to be counted in it.
 */
export const emitted = {
  actions: 0,
  errors: 0,
  latency_total_ms: 0,
  latency_max_ms: 0,
  codes: {} as Record<string, number>,
};

export function emit(l: Line, json: boolean): void {
  emitted.actions++;
  emitted.latency_total_ms += l.latency_ms;
  emitted.latency_max_ms = Math.max(emitted.latency_max_ms, l.latency_ms);
  if (l.result === "error") {
    emitted.errors++;
    const code = String(l.error_code ?? "error");
    emitted.codes[code] = (emitted.codes[code] ?? 0) + 1;
  }
  if (json) {
    process.stdout.write(JSON.stringify(l) + "\n");
  } else if (l.result === "ok") {
    process.stdout.write(`${l.action}: ok${l.player ? ` (user=${l.player})` : ""}\n`);
  } else {
    process.stderr.write(`${l.action}: error (${l.error_code})\n`);
  }
}

export interface Reply {
  status: number;
  payload: Record<string, unknown>;
  etag?: string;
  correlationId: string;
  latency_ms: number;
}

export async function request(
  base: string,
  method: string,
  path: string,
  options: { body?: unknown; token?: string; headers?: Record<string, string> } = {},
): Promise<Reply> {
  const correlationId = randomUUID();
  const started = Date.now();
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      "content-type": "application/json",
      "x-correlation-id": correlationId,
      ...(options.token ? { authorization: `Bearer ${options.token}` } : {}),
      ...(options.headers ?? {}),
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  // 304 and 204 carry no body by definition; asking for one would throw on an empty stream.
  const payload =
    res.status === 304 || res.status === 204
      ? {}
      : ((await res.json().catch(() => ({}))) as Record<string, unknown>);
  return {
    status: res.status,
    payload,
    etag: res.headers.get("etag") ?? undefined,
    correlationId,
    latency_ms: Date.now() - started,
  };
}

/** The §6 line for a reply: the status is the error code and the ETag carries the sequence number. */
export function resultLine(action: string, reply: Reply, extra: Partial<Line> = {}): Line {
  const ok = reply.status >= 200 && reply.status < 300;
  return line({
    action,
    result: ok ? "ok" : "error",
    error_code: ok ? null : reply.status,
    correlationId: reply.correlationId,
    latency_ms: reply.latency_ms,
    seq: seqOf(reply.etag),
    ...extra,
  });
}

/** The sequence number inside an `ETag: "42"`. */
export function seqOf(etag: string | undefined): number | null {
  if (!etag) return null;
  const n = Number(etag.replace(/^W\//, "").replace(/"/g, ""));
  return Number.isFinite(n) ? n : null;
}
