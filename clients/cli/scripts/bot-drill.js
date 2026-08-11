#!/usr/bin/env node
// AC-P4.8: N headless bots play a casual game through the gateway with nobody typing.
//
// It seeds the accounts, starts every bot at the same moment (started one after another they each
// create a room and then wait for each other), and judges the run the way the faculty would: every
// stdout line must parse as a §6 object with the full field set, every bot must close with a
// summary line, and every process must exit 0.
//
//   UNOARENA_API_URL=http://localhost:30080 node scripts/bot-drill.js 2
//
// The count must fill whole tables: the backend starts a game at ROOM_MIN_PLAYERS, so a bot left
// over has nobody to play and would sit there until its own timeout. `DRILL_TABLE` follows that
// setting when a cluster is tuned away from the default 2.

import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const CLI = join(dirname(fileURLToPath(import.meta.url)), "..", "dist", "cli.js");
// Spelled out here rather than imported: a check that asks the code for the contract only proves
// the code agrees with itself.
const REQUIRED = ["ts", "action", "room", "player", "latency_ms", "result", "error_code", "seq", "correlationId"];

const count = Number(process.argv[2] ?? 2);
const prefix = process.argv[3] ?? "drillbot";
const table = Number(process.env.DRILL_TABLE ?? 2);
// Each bot polices its own run, so a game that stalls fails the drill instead of holding it open.
const timeout = Number(process.env.DRILL_TIMEOUT_S ?? 180);
if (!Number.isInteger(count) || count < table || count % table !== 0) {
  process.stderr.write(`usage: bot-drill.js [count, a multiple of ${table}] [prefix]\n`);
  process.exit(2);
}

const report = [];
let ok = true;
const check = (condition, message) => {
  if (!condition) {
    ok = false;
    report.push(`FAIL ${message}`);
  }
};

const children = [];
const run = (args, env) =>
  new Promise((resolve) => {
    const child = spawn(process.execPath, [CLI, ...args], { env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] });
    children.push(child);
    let out = "";
    let err = "";
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (c) => (out += c));
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (c) => (err += c));
    child.on("exit", (code) => resolve({ code, out, err }));
  });

let done = false;
const finish = (message) => {
  // The guard can fire while the bots are still running; killing them resolves their promises and
  // the run below would report a second time.
  if (done) return;
  done = true;
  report.push(`\n== ${message}`);
  process.stdout.write(report.join("\n") + "\n");
  for (const child of children) child.kill();
  process.exitCode = ok ? 0 : 1;
};

// Belt and braces around the bots' own `--timeout`: a harness that can hang forever is a harness
// that has to be killed by hand, and then it has judged nothing.
const guard = setTimeout(() => {
  ok = false;
  finish(`BOT DRILL TIMED OUT after ${timeout + 30}s`);
}, (timeout + 30) * 1000);

// Killing the drill must kill its bots. An orphan that keeps polling joins the *next* run's room,
// starts a game there and strands somebody — a contaminated cluster that reads like a code defect.
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    ok = false;
    finish(`interrupted by ${signal}`);
  });
}

async function main() {
  // The accounts, in one call — `seed` is idempotent, so re-running the drill is free. Its own
  // session file goes to a scratch path: a drill must not evict whoever is logged in at this
  // terminal.
  const seeded = await run(["seed", "--count", String(count), "--prefix", prefix, "--json"], {
    UNOARENA_SESSION: join(tmpdir(), `${prefix}-seed.json`),
  });
  check(seeded.code === 0, `seed exited ${seeded.code}: ${seeded.err.trim()}`);
  report.push(`== seeded ${count} accounts (${prefix}-1 .. ${prefix}-${count})`);
  if (!ok) return "BOT DRILL FAILED";

  // Simultaneously, never one after the other.
  const bots = await Promise.all(
    Array.from({ length: count }, (_, i) =>
      run(["bot", "--casual", "--user", `${prefix}-${i + 1}`, "--pass", `${prefix}-pw`, "--seed", String(1000 + i), "--timeout", String(timeout)], {
        // No session file at all: `bot` takes its credentials as arguments and holds the token in
        // memory, which is what makes N of them one image with different args (§5.E).
        UNOARENA_SESSION: join(tmpdir(), `${prefix}-unused-${i + 1}.json`),
      }),
    ),
  );

  const emitted = [];
  for (const [i, bot] of bots.entries()) {
    const name = `${prefix}-${i + 1}`;
    report.push(`\n== ${name} (exit ${bot.code})`, bot.out.trimEnd());
    if (bot.err.trim()) report.push(`   stderr: ${bot.err.trim()}`);

    const lines = [];
    for (const raw of bot.out.split("\n").filter((l) => l.trim())) {
      try {
        lines.push(JSON.parse(raw));
      } catch {
        check(false, `${name}: stdout line is not JSON: ${raw}`);
      }
    }
    for (const line of lines) for (const field of REQUIRED) check(field in line, `${name}: line '${line.action}' is missing §6 field '${field}'`);
    emitted.push(...lines);

    const summary = lines[lines.length - 1];
    check(bot.code === 0, `${name}: exited ${bot.code} (${summary?.error_code ?? "no summary"})`);
    check(summary?.action === "summary", `${name}: the last line must be the summary, got '${summary?.action}'`);
    check(summary?.result === "ok", `${name}: summary result=${summary?.result} error_code=${summary?.error_code}`);
    check(summary?.actions > 1, `${name}: summary counts ${summary?.actions} actions`);
  }

  // A game that "completed" with nobody winning is a game nobody finished.
  check(emitted.some((l) => l.outcome === "won"), "no bot reports having won the game");
  check(emitted.some((l) => l.action === "play_card" && l.result === "ok"), "no card was played in the whole run");

  // A challenge only happens when somebody forgets to call (`--forget-uno`, a quarter of the time
  // by default), so it is reported rather than required: a run without one is luck, not a defect.
  const observed = [...new Set(emitted.filter((l) => l.result === "ok").map((l) => l.action))].join(", ");
  return ok ? `${count} BOTS OK - observed: ${observed}` : "BOT DRILL FAILED";
}

const outcome = await main();
clearTimeout(guard);
finish(outcome);
