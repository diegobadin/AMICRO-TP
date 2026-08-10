#!/usr/bin/env node
// AC-P3.7: two CLI processes play a complete casual game of Uno against a deployed cluster.
//
// This is the automated form of two humans at two terminals. It spawns two real `play --casual`
// processes and types into them — it does NOT talk to the API itself, so anything the board fails
// to render is something this cannot play, which is the point. The transcript goes to stdout and
// gets pasted into validation.md.
//
//   UNOARENA_API_URL=http://localhost:30080 node scripts/casual-drill.js /tmp/a.json /tmp/b.json

import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const CLI = join(dirname(fileURLToPath(import.meta.url)), "..", "dist", "cli.js");
const sessions = process.argv.slice(2);
if (sessions.length !== 2) {
  process.stderr.write("usage: casual-drill.js <sessionA.json> <sessionB.json>\n");
  process.exit(2);
}

const TIMEOUT_MS = Number(process.env.DRILL_TIMEOUT_MS ?? 180000);
// alice always calls Uno, bob never does. That asymmetry is what makes a *successful* challenge
// reproducible: challenging someone who called is refused by design, so a drill where both players
// are careful can never show the path AC-P3.7 asks the transcript to contain.
const CAREFUL = "alice";
const seen = { wild: false, draw: false, uno: false, challenge: false, completed: false };
const transcript = [];

/** Reads a rendered board back out of the CLI's own output — nothing here knows the rules. */
function parseBoard(block) {
  const hand = [];
  const handLine = block.split("\n").find((l) => /^\s+\d+\)/.test(l));
  if (handLine) {
    for (const m of handLine.matchAll(/(\d+)\)\s(\S+?)(\*?)(?=\s|$)/g)) {
      hand.push({ index: Number(m[1]), card: m[2], playable: m[3] === "*" });
    }
  }
  return {
    hand,
    yourTurn: block.includes("YOUR TURN"),
    alreadyDrew: block.includes("already drew"),
    // Only worth challenging someone who has NOT called: the board shows UNO! next to those who did.
    challengeable: /is on one card/.test(block) && !/\d+ UNO!/.test(block),
  };
}

function start(name, sessionFile) {
  const child = spawn(process.execPath, [CLI, "play", "--casual"], {
    env: { ...process.env, UNOARENA_SESSION: sessionFile },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const player = { name, child, buffer: "", done: false };

  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    player.buffer += chunk;
    for (const l of chunk.split("\n")) if (l.trim()) transcript.push(`[${name}] ${l}`);
    if (/game over/.test(chunk)) seen.completed = true;
    if (/challenge_uno: ok/.test(chunk)) seen.challenge = true;
    act(player);
  });
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (c) => transcript.push(`[${name}!] ${c.trimEnd()}`));
  child.on("exit", () => { player.done = true; });
  return player;
}

function say(player, command) {
  transcript.push(`[${player.name}] > ${command}`);
  player.child.stdin.write(command + "\n");
}

function act(player) {
  if (seen.completed) return;
  // Only the last rendered board matters; earlier ones are already answered.
  const blocks = player.buffer.split("-- room ");
  const block = blocks[blocks.length - 1];
  const state = parseBoard(block);
  if (!state.yourTurn || player.acted === block) return;
  player.acted = block;

  // An open window on a careless opponent is worth taking: it is the one path AC-P3.7 asks the
  // transcript to show, and it is only open until the turn moves on.
  if (state.challengeable) {
    say(player, "challenge");
    return;
  }


  const playable = state.hand.filter((c) => c.playable);
  if (playable.length === 0) {
    // The board only lists the actions that are legal, so "did I already draw" is read off it
    // rather than guessed at from the help text.
    if (state.alreadyDrew) {
      say(player, "pass");
    } else {
      seen.draw = true;
      say(player, "draw");
    }
    return;
  }
  // Prefer a wild once, so the transcript shows a colour declaration.
  const wild = playable.find((c) => c.card.startsWith("WILD"));
  const pick = !seen.wild && wild ? wild : playable[0];
  if (pick.card.startsWith("WILD")) {
    seen.wild = true;
    say(player, `play ${pick.index} R`);
  } else if (state.hand.length === 2 && player.name === CAREFUL) {
    // The combined form: calling before the play is wiped by the play itself, so this is the only
    // way to be safe. bob never does it, which is what makes him challengeable.
    seen.uno = true;
    say(player, `play ${pick.index} uno`);
  } else {
    say(player, `play ${pick.index}`);
  }
}

const players = [start("alice", sessions[0]), start("bob", sessions[1])];

const finish = (code, message) => {
  transcript.push(`\n== ${message}`);
  process.stdout.write(transcript.join("\n") + "\n");
  for (const p of players) if (!p.done) p.child.kill();
  process.exit(code);
};

setTimeout(() => finish(1, "TIMED OUT before the game completed"), TIMEOUT_MS);

const watch = setInterval(() => {
  if (!seen.completed) return;
  clearInterval(watch);
  setTimeout(() => {
    const missing = Object.entries(seen).filter(([, v]) => !v).map(([k]) => k);
    finish(
      seen.completed ? 0 : 1,
      `game completed. observed: ${Object.entries(seen).filter(([, v]) => v).map(([k]) => k).join(", ")}` +
        (missing.length ? ` | not observed this run: ${missing.join(", ")}` : ""),
    );
  }, 1500);
}, 500);
