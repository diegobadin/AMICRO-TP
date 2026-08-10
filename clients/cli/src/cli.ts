#!/usr/bin/env node
// UnoArena Client CLI — the canonical command surface of Client-Checkpoint.md.
//
// §5.A authentication against identity, §5.B/§5.C rooms and interactive play against room-gameplay.
// Both targets come from the environment (UNOARENA_API_URL, UNOARENA_ROOMS_URL) and never from a
// hardcoded default; the token is held in a session file so one process is one player identity.
// This CLI absorbs the wire protocol — the faculty only ever sees this command surface.

import { existsSync, rmSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { API, SESSION, emit, line, loadSession, parseFlags, request, saveSession } from "./api.js";
import { play, roomCommand } from "./rooms.js";

// Re-exported so the unit tests exercise the same functions the commands use.
export { parseFlags, line };
export type { Line } from "./api.js";

const call = (method: string, path: string, body?: unknown, token?: string) =>
  request(API, method, path, { body, token });

async function authenticate(cmd: "register" | "login", user: string, pass: string) {
  const { status, payload, correlationId, latency_ms } = await call("POST", `/auth/${cmd}`, { user, pass });
  const ok = status === 200 || status === 201;
  if (ok && payload.token) {
    saveSession({ token: String(payload.token), user: String(payload.user), userId: payload.userId ? String(payload.userId) : undefined });
  }
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

const USAGE =
  "usage: unoarena <register|login|whoami|logout|seed|room|play> [--user U --pass P] " +
  "[--count N --prefix P] [--max N] [--room ID] [--casual] [--json]\n";

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

    if (cmd === "room") return roomCommand(rest.filter((a) => !a.startsWith("--")), f, json);

    // §5.B: `play --casual` is the abstract entry into a game. `--room <id>` is the explicit form.
    if (cmd === "play") return play(f, json);

    process.stderr.write(USAGE);
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
