// P6's four read surfaces: `spectate`, `rating`, `leaderboard`, `stats`.
//
// `spectate` renders ONLY what the server sent. The spectator service's frames each carry the whole
// view alongside the event, so there is nothing for the client to accumulate — which is the point:
// P4's drill walked into 22 `409 not_your_turn` in one game because the CLI applied events to a
// board of its own and briefly believed something the server never said. A spectator has even less
// business guessing than a player does.

import { API, emit, line, loadSession, playerId, request } from "./api.js";
import { follow, type StreamEvent } from "./stream.js";

const short = (id: string) => (id.length > 8 ? `${id.slice(0, 8)}…` : id);

export interface SpectatorPlayer {
  id: string;
  cardCount: number | null;
  isConnected: boolean;
  calledUno: boolean;
  forfeited: boolean;
}

export interface SpectatorView {
  roomId: string;
  roomType: string | null;
  status: string;
  gameNumber: number | null;
  players: SpectatorPlayer[];
  topCard: string | null;
  color: string | null;
  direction: string;
  currentTurn: string | null;
  deckSize: number | null;
  finishingOrder: string[];
  lastSequence: number;
  spectatorCount: number;
}

/**
 * The spectator's board. No hand, anywhere — not hidden, absent: the view this renders has no field
 * that could hold one, so there is nothing here to accidentally print.
 *
 * A `?` card count is honest rather than broken. `GameStarted` does not say how many cards were
 * dealt, and the spectator service refuses to guess a number that belongs to room-gameplay's rules;
 * the counts fill in as each player acts.
 */
export function watchBoard(view: SpectatorView): string {
  const arrow = view.direction === "CLOCKWISE" ? "▸" : "◂";
  const players = view.players
    .map((p) => {
      const turn = p.id === view.currentTurn ? "*" : " ";
      const count = p.cardCount === null ? "?" : String(p.cardCount);
      const uno = p.calledUno ? " UNO!" : "";
      const gone = p.isConnected ? "" : " (away)";
      const out = p.forfeited ? " (forfeited)" : "";
      return `${turn}${short(p.id)} ${count}${uno}${gone}${out}`;
    })
    .join("   ");

  const lines = [
    `-- watching ${short(view.roomId)} - ${view.status.toLowerCase()}` +
      (view.gameNumber ? ` - game ${view.gameNumber}` : "") +
      ` - seq ${view.lastSequence}`,
    `   discard ${view.topCard ?? "-"}  color ${view.color ?? "-"}  ${arrow}` +
      (view.deckSize === null ? "" : `  deck ${view.deckSize}`),
    `   players  ${players || "(nobody yet)"}`,
  ];
  if (view.finishingOrder.length > 0) {
    lines.push(`   finished: ${view.finishingOrder.map(short).join(" > ")}`);
  }
  lines.push(`   ${view.spectatorCount} watching`);
  return lines.join("\n");
}

/** True once the room can produce nothing further, so the command can exit instead of hanging. */
export function isOver(view: SpectatorView): boolean {
  return view.status === "COMPLETED" || view.status === "EXPIRED";
}

export async function spectate(
  flags: Record<string, string | boolean>,
  json: boolean,
): Promise<number> {
  const roomId = String(flags.room ?? "");
  if (!roomId) {
    process.stderr.write("spectate: --room <id> is required\n");
    return 2;
  }
  const session = loadSession();
  if (!session.token) {
    process.stderr.write("spectate: log in first\n");
    return 2;
  }

  let open = true;
  let last: SpectatorView | undefined;
  // A deadline over the whole run, not just the interesting part: without one, watching a room
  // nobody is playing waits for ever (P4's lesson about a headless client with no bound).
  const timeoutSeconds = Number(flags.timeout ?? 600);
  const timer = setTimeout(() => {
    open = false;
  }, timeoutSeconds * 1000);

  const onEvent = (frame: StreamEvent) => {
    if (frame.event === "heartbeat") return;
    const payload = frame.data as { view?: SpectatorView } & Partial<SpectatorView>;
    // `snapshot` carries the view directly; every other frame nests it beside the event it came
    // from, so the client can narrate and render without deriving either.
    const view = (payload.view ?? (payload as SpectatorView)) as SpectatorView;
    if (!view || typeof view.lastSequence !== "number") return;
    last = view;
    if (!json) process.stdout.write(`${watchBoard(view)}\n`);
    if (isOver(view)) open = false;
  };

  try {
    await follow({
      url: `${API}/rooms/${roomId}/spectate`,
      token: session.token,
      from: 0,
      onEvent,
      onNotice: (text) => {
        if (!json) process.stdout.write(`   ${text}\n`);
      },
      isOpen: () => open,
    });
  } finally {
    clearTimeout(timer);
  }

  emit(
    line({
      action: "spectate",
      result: last ? "ok" : "error",
      error_code: last ? null : "no_frames",
      room: roomId,
      seq: last?.lastSequence ?? null,
      status: last?.status ?? null,
    }),
    json,
  );
  return last ? 0 : 1;
}

/** The three plain reads. Each is one GET through the gateway and one output line. */
export async function read(
  action: "rating" | "leaderboard" | "stats",
  flags: Record<string, string | boolean>,
  json: boolean,
): Promise<number> {
  const session = loadSession();
  const surface = surfaceFor(action, flags);
  const path = readPath(action, flags);
  if (!path) {
    process.stderr.write(`${action}: nothing to read — check the flags\n`);
    return 2;
  }
  const reply = await request(API, "GET", path, { token: session.token });
  const ok = reply.status === 200;
  if (ok && !json) process.stdout.write(`${render(surface, reply.payload)}\n`);
  emit(
    line({
      action,
      result: ok ? "ok" : "error",
      error_code: ok ? null : reply.status,
      correlationId: reply.correlationId,
      latency_ms: reply.latency_ms,
      ...(json ? { data: reply.payload } : {}),
    }),
    json,
  );
  return ok ? 0 : 1;
}

/** Which of the five read surfaces a command and its flags mean. Decided once, used twice. */
export type Surface = "rating" | "leaderboard" | "player-stats" | "room-stats" | "overview";

export function surfaceFor(
  action: "rating" | "leaderboard" | "stats",
  flags: Record<string, string | boolean>,
): Surface {
  if (action === "rating") return "rating";
  if (action === "leaderboard") return "leaderboard";
  if (flags.player) return "player-stats";
  if (flags.room) return "room-stats";
  return "overview";
}

/**
 * The path this session would ask for. Separate from `pathFor` so the *wiring* is testable, not just
 * the mapping: the P6 bug was not that `pathFor` mishandled its argument, it was that the caller
 * handed it the display name instead of the player id — and a test of `pathFor` alone stays green
 * through exactly that mistake.
 */
export function readPath(
  action: "rating" | "leaderboard" | "stats",
  flags: Record<string, string | boolean>,
): string | undefined {
  return pathFor(action, flags, playerId());
}

export function pathFor(
  action: "rating" | "leaderboard" | "stats",
  flags: Record<string, string | boolean>,
  me: string,
): string | undefined {
  const surface = surfaceFor(action, flags);
  const limit = flags.limit ? `?limit=${Number(flags.limit)}` : "";
  switch (surface) {
    case "rating": {
      // Defaults to this session's own player *id* — "what is my rating" is the question people
      // actually ask, and the id is what ranking keys on. A username here asks about somebody who
      // does not exist and gets a confident default back.
      const player = String(flags.player ?? me);
      return player ? `/players/${encodeURIComponent(player)}/rating` : undefined;
    }
    case "leaderboard":
      return `/leaderboard${limit}`;
    case "player-stats":
      return `/stats/players/${encodeURIComponent(String(flags.player))}`;
    case "room-stats":
      return `/stats/rooms/${encodeURIComponent(String(flags.room))}`;
    case "overview":
      return "/stats/overview";
  }
}

/**
 * Render by the surface that was ASKED for, not by guessing from the shape that came back. The
 * caller already decided which of the five reads it wanted; sniffing `payload.overview` to find out
 * again means a response that changes shape silently picks a different branch.
 */
function render(surface: Surface, payload: Record<string, unknown>): string {
  switch (surface) {
    case "rating":
      return `-- ${payload.playerId} rating ${payload.rating} after ${payload.games} game(s)`;

    case "leaderboard": {
      const rows = (payload.leaderboard as Record<string, unknown>[]) ?? [];
      if (rows.length === 0) return "-- leaderboard is empty";
      return [
        "-- leaderboard",
        ...rows.map(
          (r) =>
            `   ${String(r.rank).padStart(2)}. ${String(r.playerId).padEnd(16)} ${r.rating}  (${r.games} games)`,
        ),
      ].join("\n");
    }

    case "overview": {
      const counts = (payload.overview as Record<string, number>) ?? {};
      return [
        "-- overview",
        ...Object.entries(counts).map(([metric, value]) => `   ${metric.padEnd(20)} ${value}`),
      ].join("\n");
    }

    case "room-stats": {
      const activity = payload.activity as Record<string, unknown> | null;
      const games = (payload.games as Record<string, unknown>[]) ?? [];
      if (!activity) return `-- room ${short(String(payload.roomId))} has no recorded activity`;
      return [
        `-- room ${short(String(payload.roomId))} ${activity.status} - ${activity.playersSeen} players, ${activity.eventsSeen} events`,
        `   ${activity.cardsPlayed} cards played, ${activity.cardsDrawn} drawn`,
        ...games.map(
          (g) =>
            `   game ${g.gameNumber}: ${(g.finishingOrder as string[]).map(short).join(" > ")}` +
            (g.isAbandoned ? " (abandoned)" : ""),
        ),
      ].join("\n");
    }

    case "player-stats":
      return `-- ${payload.playerId} played ${payload.gamesPlayed}, won ${payload.gamesWon}, abandoned ${payload.gamesAbandoned}`;
  }
}
