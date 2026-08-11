// Headless play (Client-Checkpoint §5.E). The bot is a player, not a cheat: it chooses among the
// moves the server itself marked legal, declares a colour for a wild, takes an open challenge
// window on someone else — and forgets to call Uno! a quarter of the time, on purpose. A bot that
// never forgets is permanently safe, and a rule nobody can ever break is a rule nothing tests.
//
// One process is one identity (§4): credentials come from the command line and the token is held in
// memory, so N parallel bots are N containers with different args. Output is §6 and only §6 — one
// JSON line per action on stdout, a closing summary line, an exit code. Notices go to stderr.

import { API, Line, Reply, emit, emitted, line, loadSession, request, resultLine, useSession } from "./api.js";
import { GameView, mustRefresh } from "./board.js";
import { enterGame } from "./rooms.js";
import { StreamEvent, follow } from "./stream.js";

const COLOURS = ["RED", "GREEN", "BLUE", "YELLOW"];
/** Long enough to sit through a slow game, short enough that a stuck bot fails a drill by itself. */
const DEFAULT_TIMEOUT_S = 300;
/** How many times a state the server refused is worth re-answering before waiting for a frame. */
const RETRY_LIMIT = 3;

const call = (method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Reply> =>
  request(API, method, path, { body, token: loadSession().token, headers });

/** mulberry32 — eight lines of arithmetic, so `--seed` reproduces a run without a dependency. */
export function rng(seed: number): () => number {
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

/** A window is the player it names plus the moment it closes — the same one, read twice, is one. */
export const windowKey = (w: { targetPlayerId: string; expiresAt: string }): string =>
  `${w.targetPlayerId}@${w.expiresAt}`;

/**
 * The whole of the bot's game sense, kept pure so the tests can play it without a server.
 *
 * `challenged` holds the windows it has already answered: the view keeps showing an open window
 * until the next read, and a second challenge on the same one is a refusal rather than a move.
 */
export function decide(
  view: GameView,
  me: string,
  random: () => number,
  forgetUno: number,
  challenged: Set<string>,
): Move | null {
  const window = view.challengeWindow;
  if (window && window.targetPlayerId !== me && !challenged.has(windowKey(window))) {
    // Only against someone the board shows has *not* called: the engine refuses the rest, and a
    // client that offers a move it can already tell is illegal is teaching itself to expect a 409.
    const target = view.opponents.find((o) => o.playerId === window.targetPlayerId);
    if (target && !target.calledUno) {
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
  // Calling Uno! is part of playing the card — `hasCalledUno` resets whenever the hand size changes,
  // so a call before the play is wiped by the play and after it the turn has already moved on.
  const callingUno = view.hand.length === 2 && random() >= forgetUno;
  return {
    action: "play_card",
    body: { type: "play_card", card, ...(colour ? { chosenColor: colour } : {}), callingUno },
  };
}

interface Table {
  random: () => number;
  forgetUno: number;
  deadline: number;
}

interface Ending {
  reason: string | null;
  view: GameView;
}

/**
 * Plays the room out over the same stream `play` uses: the frames say when something happened, the
 * reads say what the state is, and the bot never keeps a board of its own.
 */
function autoplay(roomId: string, player: string, initial: GameView, table: Table): Promise<Ending> {
  return new Promise((resolve) => {
    let view = initial;
    let etag = `"${initial.sequenceNumber}"`;
    let lastSeen = initial.sequenceNumber;
    let reading = false;
    let acting = false;
    let closed = false;
    // The state this bot has already answered, and how many refusals it has answered from it. A
    // move the server keeps rejecting must cost a few retries, not a hot loop.
    let answered = -1;
    let retries = 0;
    const challenged = new Set<string>();

    const finish = (reason: string | null) => {
      if (closed) return;
      closed = true;
      clearTimeout(timer);
      resolve({ reason, view });
    };

    const timer = setTimeout(() => finish("timeout"), Math.max(0, table.deadline - Date.now()));

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
        const reply = await call("GET", `/rooms/${roomId}/games/1`);
        if (reply.status === 401) return dead();
        if (reply.status === 200) adopt(reply.payload as unknown as GameView);
      } finally {
        reading = false;
      }
    };

    const step = async () => {
      if (closed || acting) return;
      acting = true;
      try {
        while (!closed) {
          const move = decide(view, player, table.random, table.forgetUno, challenged);
          if (!move) break;
          if (view.sequenceNumber === answered) {
            if (retries >= RETRY_LIMIT) break; // wait for a frame to change something
            retries++;
          } else {
            answered = view.sequenceNumber;
            retries = 0;
          }
          if (view.challengeWindow && move.action === "challenge_uno") challenged.add(windowKey(view.challengeWindow));

          const reply = await call("POST", `/rooms/${roomId}/games/1/moves`, move.body, { "if-match": etag });
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
        // Time has passed: a state the server refused a moment ago is worth one more answer, and a
        // heartbeat ahead of us is the one hole the gap check cannot see — a lost *last* frame.
        retries = 0;
        if (Number(event.data.seq ?? 0) > lastSeen) attempt(refresh());
        else attempt(step());
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
    const started = await enterGame(flags, true, deadline);
    if (!started) return summarise({ result: "error", error_code: "no_game" });

    const end = await autoplay(started.roomId, started.player, started.view, { random: rng(seed), forgetUno, deadline });
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
