#!/usr/bin/env node
// Smoke assertion for integration-staging. Consumes the CLI --json lines (Client-Checkpoint.md §6)
// and checks the whole auth slice, not just that something answered: the account is usable, the
// session survives a re-login as the *new* session, the old token is dead (single-active-session),
// and logout kills the live one. Exits non-zero on any mismatch so the pipeline fails loudly.
import { readFileSync } from "node:fs";

const [, , dir, expectedUser] = process.argv;

function jsonLines(name) {
  return readFileSync(`${dir}/${name}.json`, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l));
}
function lastJsonLine(name) {
  const lines = jsonLines(name);
  return lines[lines.length - 1];
}

const REQUIRED = ["ts", "action", "room", "player", "latency_ms", "result", "error_code", "seq", "correlationId"];

let ok = true;
function check(condition, message) {
  if (!condition) {
    console.error(`FAIL ${message}`);
    ok = false;
  }
}

function shapeOf(name, l) {
  for (const field of REQUIRED) check(field in l, `${name}: missing §6 field '${field}'`);
}

const register = lastJsonLine("register");
const whoami = lastJsonLine("whoami");
const relogin = lastJsonLine("relogin");
const stale = lastJsonLine("stale");
const logout = lastJsonLine("logout");
const afterLogout = lastJsonLine("after-logout");

for (const [name, l] of Object.entries({ register, whoami, relogin, stale, logout, afterLogout })) shapeOf(name, l);

check(register.result === "ok", `register: result=${register.result} error_code=${register.error_code}`);
check(whoami.result === "ok" && whoami.player === expectedUser, `whoami: player=${whoami.player} expected=${expectedUser}`);
check(relogin.result === "ok", `relogin: result=${relogin.result} error_code=${relogin.error_code}`);
// The first session's token must be dead the moment the second login lands — the domain's
// single-active-session non-negotiable, asserted end to end rather than trusted.
check(stale.result === "error" && stale.error_code === 401, `stale token: expected 401, got ${stale.error_code}`);
check(logout.result === "ok", `logout: result=${logout.result} error_code=${logout.error_code}`);
check(afterLogout.result === "error" && afterLogout.error_code === 401, `after logout: expected 401, got ${afterLogout.error_code}`);

// seed is what makes the faculty's load test possible: N accounts, N lines, each with a token,
// and re-running it must not turn "already exists" into a failure.
const seeded = jsonLines("seed");
const reseeded = jsonLines("reseed");
check(seeded.length === 3, `seed: expected 3 lines, got ${seeded.length}`);
check(seeded.every((l) => l.result === "ok" && l.token), "seed: every line must be ok and carry a token");
check(reseeded.length === 3 && reseeded.every((l) => l.result === "ok"), "reseed: re-running seed must be idempotent");
for (const l of seeded) shapeOf("seed", l);

console.log(ok ? `SMOKE OK (user=${expectedUser})` : "SMOKE FAIL");
process.exit(ok ? 0 : 1);
