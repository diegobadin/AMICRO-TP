#!/usr/bin/env node
// Smoke assertion for integration-staging: register output ok AND whoami returns the registered
// user. Consumes the CLI --json lines (Client-Checkpoint.md §6). Exits non-zero on any mismatch so
// the pipeline fails when identity is unreachable or returns the wrong canned/real response.
import { readFileSync } from "node:fs";

const [, , regPath, whoPath, expectedUser] = process.argv;

function lastJsonLine(path) {
  const lines = readFileSync(path, "utf8").trim().split("\n").filter(Boolean);
  return JSON.parse(lines[lines.length - 1]);
}

let ok = true;
const reg = lastJsonLine(regPath);
const who = lastJsonLine(whoPath);

if (reg.result !== "ok") {
  console.error(`FAIL register: result=${reg.result} error_code=${reg.error_code}`);
  ok = false;
}
if (who.result !== "ok" || who.user !== expectedUser) {
  console.error(`FAIL whoami: result=${who.result} user=${who.user} expected=${expectedUser}`);
  ok = false;
}

console.log(ok ? `SMOKE OK (user=${who.user})` : "SMOKE FAIL");
process.exit(ok ? 0 : 1);
