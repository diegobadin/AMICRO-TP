#!/usr/bin/env node
// UnoArena Client CLI — subset used by the DevOps staging smoke test.
//
// Canonical surface (Client-Checkpoint.md §5.A): register / login / whoami / logout, all with a
// global --json flag that emits one machine-readable line (§6 output contract). The backend target
// comes from UNOARENA_API_URL (never hardcoded). The auth token is held in a session file inside
// the process's environment so `whoami` can reuse it. This CLI absorbs the wire protocol; the
// faculty only interacts with this command surface.

import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
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

interface Line {
  ts: string;
  action: string;
  result: "ok" | "error";
  error_code: string | number | null;
  correlationId: string;
  [k: string]: unknown;
}

function emit(line: Line, json: boolean): void {
  if (json) {
    process.stdout.write(JSON.stringify(line) + "\n");
  } else if (line.result === "ok") {
    process.stdout.write(`${line.action}: ok${line.user ? ` (user=${line.user})` : ""}\n`);
  } else {
    process.stderr.write(`${line.action}: error (${line.error_code})\n`);
  }
}

async function call(method: string, path: string, body?: unknown, token?: string) {
  const correlationId = randomUUID();
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
  return { status: res.status, payload, correlationId };
}

async function main(): Promise<number> {
  const [, , cmd, ...rest] = process.argv;
  const f = parseFlags(rest);
  const json = Boolean(f.json);

  try {
    if (cmd === "register" || cmd === "login") {
      const user = String(f.user ?? "");
      const pass = String(f.pass ?? "");
      const { status, payload, correlationId } = await call("POST", `/${cmd}`, { user, pass });
      const ok = status === 200 || status === 201;
      if (ok && payload.token) saveSession({ token: String(payload.token), user: String(payload.user) });
      emit({ ts: new Date().toISOString(), action: cmd, result: ok ? "ok" : "error", error_code: ok ? null : status, correlationId, user: payload.user, userId: payload.userId }, json);
      return ok ? 0 : 1;
    }

    if (cmd === "whoami") {
      const s = loadSession();
      const { status, payload, correlationId } = await call("GET", "/whoami", undefined, s.token);
      const ok = status === 200;
      emit({ ts: new Date().toISOString(), action: "whoami", result: ok ? "ok" : "error", error_code: ok ? null : status, correlationId, user: payload.user, userId: payload.userId }, json);
      return ok ? 0 : 1;
    }

    if (cmd === "logout") {
      if (existsSync(SESSION)) rmSync(SESSION);
      emit({ ts: new Date().toISOString(), action: "logout", result: "ok", error_code: null, correlationId: randomUUID() }, json);
      return 0;
    }

    process.stderr.write("usage: unoarena <register|login|whoami|logout> [--user U --pass P] [--json]\n");
    return 2;
  } catch (e) {
    emit({ ts: new Date().toISOString(), action: cmd ?? "unknown", result: "error", error_code: "unreachable", correlationId: randomUUID(), detail: String(e) }, json);
    return 1;
  }
}

main().then((code) => process.exit(code));
