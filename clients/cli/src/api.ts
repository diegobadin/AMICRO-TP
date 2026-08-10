// Shared plumbing for every command: where the backend is, where the session lives, and the one
// request helper that carries a correlation id and surfaces conditional-request headers.
//
// Two base URLs is a P3-only shape. identity owns one NodePort and room-gameplay the other because
// there is no gateway yet; P4 puts one in front and UNOARENA_ROOMS_URL goes away. Documented in the
// README rather than left for the faculty to discover.

import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { randomUUID } from "node:crypto";

export const API = (process.env.UNOARENA_API_URL ?? "http://localhost:8085").replace(/\/$/, "");
export const ROOMS = (process.env.UNOARENA_ROOMS_URL ?? "http://localhost:8081").replace(/\/$/, "");
export const SESSION = process.env.UNOARENA_SESSION ?? join(homedir() || tmpdir(), ".unoarena", "session.json");

export interface Session {
  token?: string;
  user?: string;
  userId?: string;
}

export function saveSession(s: Session): void {
  mkdirSync(dirname(SESSION), { recursive: true });
  writeFileSync(SESSION, JSON.stringify(s));
}

export function loadSession(): Session {
  return existsSync(SESSION) ? (JSON.parse(readFileSync(SESSION, "utf8")) as Session) : {};
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

export function emit(l: Line, json: boolean): void {
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

/** The sequence number inside an `ETag: "42"`. */
export function seqOf(etag: string | undefined): number | null {
  if (!etag) return null;
  const n = Number(etag.replace(/^W\//, "").replace(/"/g, ""));
  return Number.isFinite(n) ? n : null;
}
