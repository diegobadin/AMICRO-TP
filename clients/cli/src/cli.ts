#!/usr/bin/env node
// UnoArena Client CLI — the authentication subset of the canonical surface.
//
// Client-Checkpoint.md §5.A: register / login / whoami / logout / seed, all with a global --json
// flag emitting one machine-readable line per action (§6 output contract). The backend target
// comes from UNOARENA_API_URL (never hardcoded). The auth token is held in a session file so the
// one-shot utilities can chain. This CLI absorbs the wire protocol; the faculty only interacts
// with this command surface.

import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";

const API = (process.env.UNOARENA_API_URL ?? "http://localhost:8085").replace(/\/$/, "");
const SESSION = process.env.UNOARENA_SESSION ?? join(homedir() || tmpdir(), ".unoarena", "session.json");

interface Session {
  token?: string;
  user?: string;
}

function saveSession(s: Session): void {
  mkdirSync(dirname(SESSION), { recursive: true });
  writeFileSync(SESSION, JSON.stringify(s));
}
function loadSession(): Session {
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
// every command. Fields that do not apply to an auth action are present and null, not missing.
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

function emit(l: Line, json: boolean): void {
  if (json) {
    process.stdout.write(JSON.stringify(l) + "\n");
  } else if (l.result === "ok") {
    process.stdout.write(`${l.action}: ok${l.player ? ` (user=${l.player})` : ""}\n`);
  } else {
    process.stderr.write(`${l.action}: error (${l.error_code})\n`);
  }
}

async function call(method: string, path: string, body?: unknown, token?: string) {
  const correlationId = randomUUID();
  const started = Date.now();
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      "content-type": "application/json",
      "x-correlation-id": correlationId,
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { status: res.status, payload, correlationId, latency_ms: Date.now() - started };
}

async function authenticate(cmd: "register" | "login", user: string, pass: string) {
  const { status, payload, correlationId, latency_ms } = await call("POST", `/auth/${cmd}`, { user, pass });
  const ok = status === 200 || status === 201;
  if (ok && payload.token) saveSession({ token: String(payload.token), user: String(payload.user) });
  return {
    ok,
    token: ok ? String(payload.token) : undefined,
    line: line({
      action: cmd,
      result: ok ? "ok" : "error",
      error_code: ok ? null : status,
      correlationId,
      latency_ms,
      player: ok ? String(payload.user) : user,
      userId: payload.userId,
    }),
  };
}

// `seed` is what makes load testing possible (§5.A): ensure N accounts exist and print their
// credentials. An account that already exists is logged in rather than reported as a failure, so
// re-running the same seed always yields N usable identities.
async function seed(count: number, prefix: string, json: boolean): Promise<number> {
  let failures = 0;
  for (let i = 1; i <= count; i++) {
    const user = `${prefix}-${i}`;
    const pass = `${prefix}-pw`;
    let attempt = await authenticate("register", user, pass);
    if (!attempt.ok && attempt.line.error_code === 409) attempt = await authenticate("login", user, pass);
    if (!attempt.ok) failures++;
    emit(line({ ...attempt.line, action: "seed", pass, token: attempt.token }), json);
  }
  return failures === 0 ? 0 : 1;
}

async function main(): Promise<number> {
  const [, , cmd, ...rest] = process.argv;
  const f = parseFlags(rest);
  const json = Boolean(f.json);

  try {
    if (cmd === "register" || cmd === "login") {
      const attempt = await authenticate(cmd, String(f.user ?? ""), String(f.pass ?? ""));
      emit(attempt.line, json);
      return attempt.ok ? 0 : 1;
    }

    if (cmd === "seed") {
      const count = Number(f.count ?? 1);
      if (!Number.isInteger(count) || count < 1) {
        process.stderr.write("seed: --count must be a positive integer\n");
        return 2;
      }
      return seed(count, String(f.prefix ?? "bot"), json);
    }

    if (cmd === "whoami") {
      const s = loadSession();
      const { status, payload, correlationId, latency_ms } = await call("GET", "/auth/whoami", undefined, s.token);
      const ok = status === 200;
      emit(line({ action: "whoami", result: ok ? "ok" : "error", error_code: ok ? null : status, correlationId, latency_ms, player: payload.user ? String(payload.user) : null, userId: payload.userId }), json);
      return ok ? 0 : 1;
    }

    if (cmd === "logout") {
      // Tell the backend first: the session has to die server-side, not just locally, or the
      // token keeps working from anywhere else it was copied to.
      const s = loadSession();
      const { status, correlationId, latency_ms } = await call("POST", "/auth/logout", {}, s.token);
      if (existsSync(SESSION)) rmSync(SESSION);
      const ok = status === 200;
      emit(line({ action: "logout", result: ok ? "ok" : "error", error_code: ok ? null : status, correlationId, latency_ms, player: s.user ?? null }), json);
      return ok ? 0 : 1;
    }

    process.stderr.write("usage: unoarena <register|login|whoami|logout|seed> [--user U --pass P] [--count N --prefix P] [--json]\n");
    return 2;
  } catch (e) {
    emit(line({ action: cmd ?? "unknown", result: "error", error_code: "unreachable", detail: String(e) }), json);
    return 1;
  }
}

// Only when run as a command. Importing this module (the unit tests do) must not execute a
// command and take the test runner down with process.exit.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then((code) => process.exit(code));
}
