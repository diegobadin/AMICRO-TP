// Room commands (§5.B) and the interactive play view (§5.C), both against room-gameplay.
//
// `play --casual` is "put me into a game": the CLI absorbs the create/join dance (E3) — list the
// joinable rooms, take the first one, and only create if there is nothing to join. The backend
// starts the game itself once the room reaches ROOM_MIN_PLAYERS, so neither side waits on a host.

import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import { ROOMS, Line, Reply, emit, line, loadSession, request, seqOf } from "./api.js";
import { GameView, board, feed } from "./board.js";

const POLL_MS = Number(process.env.UNOARENA_POLL_MS ?? 1000);

interface RoomSummary {
  roomId: string;
  roomType: string;
  status: string;
  playerCount: number;
  maxPlayers: number;
}

function token(): string | undefined {
  return loadSession().token;
}

function me(): string {
  const session = loadSession();
  if (session.userId) return String(session.userId);
  // The player id is the JWT subject; identity puts it there and the CLI never needs to ask.
  const payload = session.token?.split(".")[1];
  if (!payload) return "";
  try {
    return String(JSON.parse(Buffer.from(payload, "base64url").toString()).sub ?? "");
  } catch {
    return "";
  }
}

const call = (method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Reply> =>
  request(ROOMS, method, path, { body, token: token(), headers });

function resultLine(action: string, reply: Reply, extra: Partial<Line> = {}): Line {
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

export async function roomCommand(argv: string[], flags: Record<string, string | boolean>, json: boolean): Promise<number> {
  const [sub, arg] = argv;
  const player = me();

  if (sub === "create") {
    const max = Number(flags.max ?? 10);
    // An Idempotency-Key means a retried create returns the first room instead of leaving an
    // abandoned one behind (§2.3.1).
    const reply = await call("POST", "/rooms", { maxPlayers: max }, { "idempotency-key": randomUUID() });
    const roomId = reply.payload.roomId ? String(reply.payload.roomId) : null;
    emit(resultLine("room_create", reply, { room: roomId, player }), json);
    if (!json && roomId) process.stdout.write(`room ${roomId}\n`);
    return reply.status < 300 ? 0 : 1;
  }

  if (sub === "join") {
    if (!arg) { process.stderr.write("usage: room join <roomId>\n"); return 2; }
    const reply = await call("POST", `/rooms/${arg}/players/${player}`);
    emit(resultLine("room_join", reply, { room: arg, player }), json);
    return reply.status < 300 ? 0 : 1;
  }

  if (sub === "leave") {
    const roomId = arg ?? String(flags.room ?? "");
    if (!roomId) { process.stderr.write("usage: room leave <roomId>\n"); return 2; }
    const reply = await call("DELETE", `/rooms/${roomId}/players/${player}`);
    emit(resultLine("room_leave", reply, { room: roomId, player }), json);
    return reply.status < 300 ? 0 : 1;
  }

  if (sub === "list") {
    const reply = await call("GET", "/rooms");
    const rooms = (Array.isArray(reply.payload) ? reply.payload : []) as unknown as RoomSummary[];
    emit(resultLine("room_list", reply, { player, rooms: rooms.length }), json);
    if (!json) {
      if (rooms.length === 0) process.stdout.write("no joinable rooms\n");
      for (const r of rooms) {
        process.stdout.write(`${r.roomId}  ${r.status}  ${r.playerCount}/${r.maxPlayers}\n`);
      }
    }
    return reply.status < 300 ? 0 : 1;
  }

  process.stderr.write("usage: room <create|join|list|leave> [roomId] [--max N]\n");
  return 2;
}

async function joinable(): Promise<RoomSummary[]> {
  const listed = await call("GET", "/rooms");
  return (Array.isArray(listed.payload) ? listed.payload : []) as unknown as RoomSummary[];
}

async function join(roomId: string, player: string, json: boolean): Promise<boolean> {
  const joined = await call("POST", `/rooms/${roomId}/players/${player}`);
  if (joined.status >= 300) return false;
  emit(resultLine("room_join", joined, { room: roomId, player }), json);
  return true;
}

/** List, take the first joinable room, and only create one if there is nothing to join. */
async function enterCasualRoom(player: string, maxPlayers: number, json: boolean): Promise<string | null> {
  for (const room of await joinable()) {
    // Someone may have filled or started it between the list and the join. Try the next one rather
    // than failing: a race here is ordinary, not an error the player should have to read about.
    if (await join(room.roomId, player, json)) return room.roomId;
  }

  const created = await call("POST", "/rooms", { maxPlayers }, { "idempotency-key": randomUUID() });
  const roomId = created.payload.roomId ? String(created.payload.roomId) : null;
  emit(resultLine("room_create", created, { room: roomId, player }), json);
  return roomId;
}

/**
 * Waits for the backend's auto-start (E3), and — for `--casual` only — keeps trying to converge on
 * one room while it waits.
 *
 * Two players running `play --casual` at the same moment both see an empty list and both create a
 * room, then sit in separate rooms waiting for each other forever. That is not hypothetical: it is
 * what happens every time the drill starts both processes together. Both clients apply the same
 * total order (lowest room id wins), so they pick the same room without a coordinator and without a
 * thundering herd.
 *
 * `converge` is false for `play --room <id>`: a player who named a room means that room, and
 * quietly moving them to another one would be a worse bug than the one this fixes.
 */
async function waitForGame(
  roomId: string,
  player: string,
  json: boolean,
  converge: boolean,
): Promise<{ roomId: string; view: GameView } | null> {
  let current = roomId;
  if (!json) process.stdout.write("waiting for another player...\n");

  for (let attempt = 0; attempt < 600; attempt++) {
    const game = await call("GET", `/rooms/${current}/games/1`);
    if (game.status === 200) return { roomId: current, view: game.payload as unknown as GameView };
    if (game.status === 401) { process.stderr.write("session rejected (401) - log in again\n"); return null; }

    if (converge) {
      const others = (await joinable()).map((r) => r.roomId).filter((id) => id !== current);
      const winner = [current, ...others].sort()[0];
      if (winner !== current && (await join(winner, player, json))) {
        await call("DELETE", `/rooms/${current}/players/${player}`);
        current = winner;
      }
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  process.stderr.write("no game started in time\n");
  return null;
}

export async function play(flags: Record<string, string | boolean>, json: boolean): Promise<number> {
  const player = me();
  if (!player) { process.stderr.write("no session - run `login` first\n"); return 1; }

  const named = flags.room ? String(flags.room) : null;
  const maxPlayers = Number(flags.max ?? 4);
  const entered = named ?? (await enterCasualRoom(player, maxPlayers, json));
  if (!entered) return 1;

  const started = await waitForGame(entered, player, json, named === null);
  if (!started) return 1;
  if (!json) process.stdout.write(board(started.view, player) + "\n");

  return interactive(started.roomId, player, started.view, json);
}

function interactive(roomId: string, player: string, initial: GameView, json: boolean): Promise<number> {
  return new Promise((resolve) => {
    let view = initial;
    let etag = `"${initial.sequenceNumber}"`;
    let busy = false;
    let closed = false;

    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: false });

    const finish = (code: number) => {
      if (closed) return;
      closed = true;
      clearInterval(timer);
      rl.close();
      resolve(code);
    };

    const adopt = (next: GameView, quiet = false) => {
      if (next.sequenceNumber === view.sequenceNumber) return;
      const events = feed(view, next, player);
      view = next;
      etag = `"${next.sequenceNumber}"`;
      if (json) {
        for (const e of events) emit(line({ action: "event", result: "ok", room: roomId, player, seq: next.sequenceNumber, detail: e }), true);
      } else {
        for (const e of events) process.stdout.write(`  ${e}\n`);
        if (!quiet && (next.yourTurn || next.status === "COMPLETED")) process.stdout.write(board(next, player) + "\n");
      }
      if (next.status === "COMPLETED") finish(0);
    };

    // Polling with If-None-Match (E4): while nothing has happened the answer is a 304 and costs
    // almost nothing. P4 replaces this loop with an SSE stream and keeps the same endpoint as the
    // reconnect read, so `play` is re-wired rather than rewritten.
    const poll = async () => {
      if (busy || closed) return;
      busy = true;
      try {
        const reply = await call("GET", `/rooms/${roomId}/games/1`, undefined, { "if-none-match": etag });
        if (reply.status === 200) adopt(reply.payload as unknown as GameView);
        else if (reply.status === 401) {
          // The only way a live session dies mid-game is a second login for the same player.
          emit(line({ action: "play", result: "error", error_code: "session_superseded", room: roomId, player }), json);
          finish(1);
        }
      } catch (e) {
        if (!json) process.stderr.write(`  poll failed: ${String(e)}\n`);
      } finally {
        busy = false;
      }
    };
    const timer = setInterval(poll, POLL_MS);

    const move = async (body: Record<string, unknown>, action: string) => {
      busy = true;
      try {
        const reply = await call("POST", `/rooms/${roomId}/games/1/moves`, body, { "if-match": etag });
        emit(resultLine(action, reply, { room: roomId, player }), json);

        if (reply.status === 201) {
          adopt(reply.payload as unknown as GameView, true);
          if (!json && view.status !== "COMPLETED") process.stdout.write(board(view, player) + "\n");
        } else if (reply.status === 412) {
          // Someone moved between the render and the command. The 412 body IS the authoritative
          // state, so reconcile from it instead of guessing — and say so rather than hiding it.
          if (!json) process.stdout.write("  your view was stale; reconciled to the current state\n");
          adopt(reply.payload as unknown as GameView);
          if (!json) process.stdout.write(board(view, player) + "\n");
        } else if (!json) {
          process.stdout.write(`  refused: ${String(reply.payload.error ?? reply.status)}\n`);
        }
      } finally {
        busy = false;
      }
    };

    rl.on("line", async (input) => {
      const [cmd, a, b, rest] = input.trim().split(/\s+/);
      if (closed) return;
      switch (cmd) {
        case "play": {
          const index = Number(a) - 1;
          const card = view.hand[index];
          if (!card) { process.stdout.write("  no such card\n"); break; }
          // `play <n> uno` and `play <n> <colour> uno` are the combined form §4.1 provides. It is
          // the only way to be safe: hasCalledUno resets whenever the hand size changes, so calling
          // before the play is wiped by the play itself, and after it the turn has already moved on.
          const words = [a, b, rest].filter(Boolean).map((w) => w!.toUpperCase());
          const callingUno = words.includes("UNO");
          const colour = words.find((w) => ["R", "G", "B", "Y", "RED", "GREEN", "BLUE", "YELLOW"].includes(w));
          if (card.startsWith("WILD") && !colour) {
            process.stdout.write("  a wild needs a colour: play <n> <R|G|B|Y> [uno]\n");
            break;
          }
          // Deliberately NOT calling Uno on the player's behalf. Doing so would make every player
          // permanently safe and delete the whole call-and-challenge mechanic from the game — the
          // player types `uno`, as §5.C's command list implies, and pays for forgetting.
          await move(
            {
              type: "play_card",
              card,
              ...(colour ? { chosenColor: { R: "RED", G: "GREEN", B: "BLUE", Y: "YELLOW" }[colour[0]] ?? colour } : {}),
              callingUno,
            },
            "play_card",
          );
          break;
        }
        case "draw": await move({ type: "draw_card" }, "draw_card"); break;
        case "pass": await move({ type: "pass" }, "pass"); break;
        case "uno": await move({ type: "call_uno" }, "call_uno"); break;
        case "challenge": {
          const target = view.challengeWindow?.targetPlayerId;
          if (!target) { process.stdout.write("  no open challenge\n"); break; }
          await move({ type: "challenge_uno", targetPlayerId: target }, "challenge_uno");
          break;
        }
        case "state": process.stdout.write(board(view, player) + "\n"); break;
        case "quit": finish(0); break;
        case "": break;
        default: process.stdout.write("  play <n> [R|G|B|Y] | draw | uno | challenge | pass | state | quit\n");
      }
    });

    rl.on("close", () => finish(0));
  });
}
