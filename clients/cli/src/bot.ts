// Headless play (Client-Checkpoint §5.E). The bot is a player, not a cheat: it chooses among the
// moves the server itself marked legal, declares a colour for a wild, takes an open challenge
// window on someone else — and forgets to call Uno! a quarter of the time, on purpose. A bot that
// never forgets is permanently safe, and a rule nobody can ever break is a rule nothing tests.
//
// One process is one identity (§4): credentials come from the command line and the token is held in
// memory, so N parallel bots are N containers with different args. Output is §6 and only §6 — one
// JSON line per action on stdout, a closing summary line, an exit code. Notices go to stderr.

import {
  API,
  Line,
  Reply,
  emit,
  emitted,
  line,
  loadSession,
  playerId,
  request,
  resultLine,
  useSession,
} from "./api.js";
import { GameView, mustRefresh } from "./board.js";
import { currentGame, enterGame } from "./rooms.js";
import { followTournament, registerForTournament } from "./tournament.js";
import { StreamEvent, follow } from "./stream.js";

const COLOURS = ["RED", "GREEN", "BLUE", "YELLOW"];
/** Long enough to sit through a slow game, short enough that a stuck bot fails a drill by itself. */
const DEFAULT_TIMEOUT_S = 300;

const call = (method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Reply> =>
  request(API, method, path, { body, token: loadSession().token, headers });

/** mulberry32 — eight lines of arithmetic, so `--seed` reproduces a run without a dependency. */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface Move {
  action: string;
  body: Record<string, unknown>;
}

/**
 * The whole of the bot's game sense: a closure over its dice and the challenge windows it has
 * already answered, so a caller hands it a state and gets back a move with nothing to remember on
 * its behalf. Deterministic per seed, and playable in a test without a server.
 */
export function brain(me: string, seed: number, forgetUno: number, idle = false): (view: GameView) => Move | null {
  const random = rng(seed);
  // A window is the player it names plus the moment it closes. The view keeps showing an open one
  // until the next read, and a second challenge on the same window is a refusal rather than a move.
  const challenged = new Set<string>();

  return (view) => {
    // `--idle` is a bot that has walked away from the table: it answers nothing, so its turns lapse
    // and the timer worker is what moves the game on. It is how a timeout gets demonstrated on
    // purpose instead of waited for, and how the idle-forfeit rule is exercised end to end.
    if (idle) return null;

    const window = view.challengeWindow;
    if (window && window.targetPlayerId !== me) {
      // Only against someone the board shows has *not* called: the engine refuses the rest, and a
      // client that sends a move it can already tell is illegal is teaching itself to expect a 409.
      const target = view.opponents.find((o) => o.playerId === window.targetPlayerId);
      const key = `${window.targetPlayerId}@${window.expiresAt}`;
      if (target && !target.calledUno && !challenged.has(key)) {
        challenged.add(key);
        return { action: "challenge_uno", body: { type: "challenge_uno", targetPlayerId: window.targetPlayerId } };
      }
    }

    if (!view.yourTurn) return null;

    // `playable` is the server's own legality check, so a random pick from it is a random *valid*
    // move (§5.E) rather than a guess the engine then has to reject.
    if (view.playable.length === 0) {
      return view.drewThisTurn
        ? { action: "pass", body: { type: "pass" } }
        : { action: "draw_card", body: { type: "draw_card" } };
    }

    const index = view.playable[Math.floor(random() * view.playable.length)];
    const card = view.hand[index];
    const colour = card.startsWith("WILD") ? COLOURS[Math.floor(random() * COLOURS.length)] : null;
    // Calling Uno! is part of playing the card — `hasCalledUno` resets whenever the hand size
    // changes, so a call before the play is wiped by the play and after it the turn has moved on.
    const callingUno = view.hand.length === 2 && random() >= forgetUno;
    return {
      action: "play_card",
      body: { type: "play_card", card, ...(colour ? { chosenColor: colour } : {}), callingUno },
    };
  };
}

interface Ending {
  reason: string | null;
  view: GameView;
}

/**
 * Plays the room out over the same stream `play` uses: the frames say when something happened, the
 * reads say what the state is, and the bot never keeps a board of its own.
 */
function autoplay(
  roomId: string,
  player: string,
  initial: GameView,
  think: (view: GameView) => Move | null,
  deadline: number,
): Promise<Ending> {
  return new Promise((resolve) => {
    let view = initial;
    let etag = `"${initial.sequenceNumber}"`;
    let lastSeen = initial.sequenceNumber;
    let reading = false;
    let acting = false;
    let closed = false;
    // The state this bot has already answered. One move per state: a state the server refused will
    // refuse the same move again, so re-answering it is a hot loop, not a retry.
    let answered = -1;

    const finish = (reason: string | null) => {
      if (closed) return;
      closed = true;
      clearTimeout(timer);
      resolve({ reason, view });
    };

    const timer = setTimeout(() => finish("timeout"), Math.max(0, deadline - Date.now()));

    // The loop is driven by callbacks, so a transport error inside it has nobody to propagate to:
    // unguarded it becomes an unhandled rejection, which kills the process before the summary line
    // the run is judged by. Ending the run *with* a summary is the honest failure.
    const attempt = (work: Promise<void>): void => {
      void work.catch((e) => {
        process.stderr.write(`bot: ${String(e)}\n`);
        finish("unreachable");
      });
    };

    const dead = () => {
      emit(line({ action: "bot", result: "error", error_code: "session_superseded", room: roomId, player }), true);
      finish("session_superseded");
    };

    const adopt = (next: GameView) => {
      view = next;
      etag = `"${next.sequenceNumber}"`;
      lastSeen = Math.max(lastSeen, next.sequenceNumber);
      if (next.status === "COMPLETED") return finish(null);
      attempt(step());
    };

    const refresh = async () => {
      if (closed || reading) return;
      reading = true;
      try {
        const reply = await call("GET", `/rooms/${roomId}/games/${view.gameNumber}`);
        if (reply.status === 401) return dead();
        // A tournament room starts its next game the instant the last one ends, and the finished
        // game stops being readable at that moment. So a 404 here is not an error: it is this game
        // being over, told from the outside. Without it the bot waits for a completion it can no
        // longer see — four of them sat there through a whole round in P7's first drill.
        if (reply.status === 404) return finish(null);
        if (reply.status === 200) adopt(reply.payload as unknown as GameView);
      } finally {
        reading = false;
      }
    };

    const step = async () => {
      if (closed || acting) return;
      acting = true;
      try {
        while (!closed && view.sequenceNumber !== answered) {
          const move = think(view);
          if (!move) break;
          answered = view.sequenceNumber;

          const reply = await call(
            "POST",
            `/rooms/${roomId}/games/${view.gameNumber}/moves`,
            move.body,
            { "if-match": etag },
          );
          emit(resultLine(move.action, reply, { room: roomId, player }), true);
          if (reply.status === 401) return dead();
          // A `201` is what the move produced and a `412` is what the room actually looks like:
          // both bodies are the authoritative state, so neither of them costs a read.
          if (reply.status === 201 || reply.status === 412) adopt(reply.payload as unknown as GameView);
          else await refresh();
        }
      } finally {
        acting = false;
      }
    };

    const onEvent = (event: StreamEvent) => {
      if (closed) return;
      if (event.event === "session-invalidated") return dead();
      if (event.event === "resync") return attempt(refresh());
      if (event.event === "heartbeat") {
        // A heartbeat ahead of us is the one hole the gap check cannot see — a lost *last* frame.
        // Level with us, it means time has passed with nothing happening, which is the shape of a
        // move that was refused or lost: answer the state once more rather than sit on it.
        if (Number(event.data.seq ?? 0) > lastSeen) return attempt(refresh());
        answered = -1;
        attempt(step());
        return;
      }
      if (event.id === undefined) return;

      const gap = event.id > lastSeen + 1;
      lastSeen = Math.max(lastSeen, event.id);
      // The frames of the bot's own command arrive after its response, which already carried a
      // newer state than they describe.
      if (event.id <= view.sequenceNumber) return;
      if (gap || mustRefresh(event, player)) attempt(refresh());
    };

    void follow({
      url: `${API}/rooms/${roomId}/stream`,
      token: loadSession().token ?? "",
      from: initial.sequenceNumber,
      onEvent,
      onNotice: (text) => {
        if (!closed) process.stderr.write(`bot: ${text}\n`);
      },
      isOpen: () => !closed,
    });

    attempt(step()); // the first state may already be this bot's turn
  });
}

/**
 * §5.E takes credentials on the command line so a container needs no session file. Falling back to
 * the stored session keeps `login && bot --casual` working the way `play` does.
 */
async function signIn(flags: Record<string, string | boolean>): Promise<boolean> {
  if (flags.token) {
    useSession({ token: String(flags.token) });
    return true;
  }
  if (!flags.user && !flags.pass) return Boolean(loadSession().token);
  if (!flags.user || !flags.pass) {
    // Half a credential must not quietly fall back to whoever is logged in at this terminal.
    process.stderr.write("bot: --user and --pass go together\n");
    return false;
  }

  const reply = await call("POST", "/auth/login", { user: String(flags.user), pass: String(flags.pass) });
  emit(resultLine("login", reply, { player: String(flags.user) }), true);
  if (reply.status !== 200 || !reply.payload.token) return false;
  useSession({
    token: String(reply.payload.token),
    user: String(reply.payload.user ?? flags.user),
    userId: reply.payload.userId ? String(reply.payload.userId) : undefined,
  });
  return true;
}

// stdout is a pipe under the drill, and writes to a pipe are asynchronous: exiting without waiting
// for the flush can cut off the very summary line the run is judged by.
const flushed = (): Promise<void> => new Promise((resolve) => process.stdout.write("", () => resolve()));

/**
 * Whether this run is a tournament, and which one. `Client-Checkpoint.md` §5.E writes the canonical
 * form as `bot --tournament <tournamentId>`, so parseFlags hands the flag that id as its VALUE —
 * and the `flags.tournament === true` test this replaced was false for exactly the faculty's own
 * invocation, which then fell through and played a casual game without saying so. Extracted from
 * the dispatch because a wrong answer here is silent: the bot plays, reports ok, and plays the
 * wrong thing.
 */
export function tournamentMode(flags: Record<string, string | boolean>): { on: boolean; id?: string } {
  if (!flags.tournament) return { on: false };
  if (flags.id) return { on: true, id: String(flags.id) };
  return { on: true, id: typeof flags.tournament === "string" ? flags.tournament : undefined };
}

export async function bot(flags: Record<string, string | boolean>): Promise<number> {
  const startedAt = Date.now();
  // A flag given without a value parses as `true`, and `Number(true)` is a perfectly plausible 1 —
  // so a mistyped `--forget-uno` would silently become "never call Uno!" rather than an error.
  const numeric = (name: string, fallback: number): number => {
    const raw = flags[name];
    if (raw === undefined) return fallback;
    return typeof raw === "boolean" ? NaN : Number(raw);
  };
  const seed = numeric("seed", Math.floor(Math.random() * 2 ** 32));
  const forgetUno = numeric("forget-uno", 0.25);
  const timeoutMs = numeric("timeout", DEFAULT_TIMEOUT_S) * 1000;
  if (!Number.isFinite(seed) || !(forgetUno >= 0 && forgetUno <= 1) || !Number.isFinite(timeoutMs)) {
    process.stderr.write("bot: --seed and --timeout must be numbers, --forget-uno a probability in [0,1]\n");
    return 2;
  }

  // §6: on termination, total actions, error counts and latency aggregates — over every line above
  // it, which is what `emitted` has been counting.
  const summarise = async (fields: Partial<Line> & { result: "ok" | "error" }): Promise<number> => {
    emit(
      line({
        action: "summary",
        latency_ms: emitted.actions === 0 ? 0 : Math.round(emitted.latency_total_ms / emitted.actions),
        actions: emitted.actions,
        errors: emitted.errors,
        error_codes: { ...emitted.codes },
        latency_max_ms: emitted.latency_max_ms,
        duration_ms: Date.now() - startedAt,
        seed,
        ...fields,
      }),
      true,
    );
    await flushed();
    return fields.result === "ok" ? 0 : 1;
  };

  try {
    if (!(await signIn(flags))) return summarise({ result: "error", error_code: "unauthorized" });

    const deadline = startedAt + timeoutMs;

    // `--tournament` plays a whole event rather than one game: register, then play whatever room
    // each round assigns, until eliminated or champion. The rooms arrive already dealing, so there
    // is no table to wait for — the bracket is what decides when this bot plays next.
    const mode = tournamentMode(flags);
    if (mode.on) {
      const player = playerId();
      const tournamentId = await registerForTournament(mode.id ? { ...flags, id: mode.id } : flags, true);
      if (!tournamentId) return summarise({ result: "error", error_code: "no_tournament", player });

      const code = await followTournament(
        tournamentId,
        true,
        async (roomId) => {
          const view = await currentGame(roomId, player);
          if (!view) return 1;
          const ending = await autoplay(roomId, player, view, brain(player, seed, forgetUno), deadline);
          return ending.reason ? 1 : 0;
        },
        deadline,
      );
      return summarise({
        result: code === 0 ? "ok" : "error",
        error_code: code === 0 ? null : "unfinished",
        player,
        tournament: tournamentId,
      });
    }

    const started = await enterGame(flags, true, deadline);
    // A table that never fills is the commonest way a headless run ends, and it is a timeout —
    // not the same thing as a session that died or a room that refused.
    if (!started) return summarise({ result: "error", error_code: Date.now() >= deadline ? "timeout" : "no_game" });

    const end = await autoplay(
      started.roomId,
      started.player,
      started.view,
      brain(started.player, seed, forgetUno, flags.idle === true),
      deadline,
    );
    return summarise({
      result: end.reason ? "error" : "ok",
      error_code: end.reason,
      room: started.roomId,
      player: started.player,
      seq: end.view.sequenceNumber,
      outcome: end.reason ? "unfinished" : end.view.finishingOrder[0] === started.player ? "won" : "lost",
    });
  } catch (e) {
    process.stderr.write(`bot: ${String(e)}\n`);
    return summarise({ result: "error", error_code: "unreachable", detail: String(e) });
  }
}
