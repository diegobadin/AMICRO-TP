// Tournament commands (§5.D). One command per player for the whole event: `tournament register`
// registers, then waits to be given a room, plays it, and comes back for the next round until it
// is eliminated or holding the trophy.
//
// The client never decides anything about the bracket. It asks "where am I", and the answer is the
// tournament's — which is why the wait loop is a poll against one endpoint rather than a copy of
// the advancement rules living out here.

import { API, Reply, emit, line, loadSession, playerId, request, resultLine } from "./api.js";
import { playAssignedRoom } from "./rooms.js";

const POLL_MS = Number(process.env.UNOARENA_POLL_MS ?? 1000);

export interface Placement {
  tournamentId: string;
  status: string;
  roundNumber?: number | null;
  roomId?: string | null;
  eliminated: boolean;
  champion: boolean;
}

const call = (method: string, path: string, body?: unknown): Promise<Reply> =>
  request(API, method, path, { body, token: loadSession().token });

/**
 * The open tournament to join, or null when there is none. The **lowest id**, not the first the
 * service happened to list: every client applies the same rule to the same list and picks the same
 * tournament, which is what makes four processes starting together converge without a coordinator.
 */
async function openTournament(): Promise<string | null> {
  const reply = await call("GET", "/tournaments");
  if (reply.status !== 200) return null;
  const list = (reply.payload as unknown as { tournamentId: string; status: string }[]) ?? [];
  if (!Array.isArray(list)) return null;
  const open = list.filter((t) => t.status === "REGISTRATION").map((t) => t.tournamentId).sort();
  return open[0] ?? null;
}

/**
 * Join an existing tournament or open one.
 *
 * Four clients started together all find nothing and all create — the P3 two-process lesson, which
 * P7 walked into again: the first drill produced four tournaments holding one player each, none of
 * them ever reaching the threshold. Creating is therefore not the end of the decision. Every client
 * re-reads the list afterwards and converges on the lowest id, which is the same rule on every side
 * and needs no coordinator; the tournaments the losers opened stay empty and are joinable by
 * whoever comes next.
 */
async function enterTournament(flags: Record<string, string | boolean>, json: boolean): Promise<string | null> {
  if (flags.id) return String(flags.id);

  const existing = await openTournament();
  if (existing) return existing;

  const created = await call("POST", "/tournaments");
  if (created.status >= 300) {
    emit(resultLine("tournament_create", created), json);
    return null;
  }
  return (await settledOpenTournament()) ?? String(created.payload.tournamentId);
}

/**
 * The lowest open tournament, once the list has stopped moving.
 *
 * A single re-read after creating converges everyone *except* the client that won the create race:
 * it finishes first, so the only tournament in existence at that instant is its own, and it
 * registers there while everybody else converges elsewhere. P9's R0 drill produced exactly that —
 * four bots, four tournaments, three registrations on one and one on another, threshold of four
 * never reached and every bot timing out with nothing logging a problem.
 *
 * Two consecutive reads agreeing is what "settled" means here, and the value returned was open on
 * the most recent one. The creates land within tens of milliseconds, so this normally costs one gap.
 */
async function settledOpenTournament(attempts = 5, gapMs = 300): Promise<string | null> {
  let previous = await openTournament();
  for (let i = 1; i < attempts; i++) {
    await new Promise((resolve) => setTimeout(resolve, gapMs));
    const current = await openTournament();
    if (current && current === previous) return current;
    previous = current;
  }
  return previous;
}

/** Where this player stands right now, from the service that decides it. */
async function placement(tournamentId: string): Promise<Placement | null> {
  const me = playerId();
  const reply = await call("GET", `/tournaments/${tournamentId}/players/${me}`);
  if (reply.status !== 200) return null;
  return reply.payload as unknown as Placement;
}

/**
 * Follow one player through a whole tournament: wait for a room, play it, repeat. `play` is a
 * parameter so the human command and the bot share this loop rather than each growing a version of
 * it — the bot's only difference is who decides the moves.
 */
export async function followTournament(
  tournamentId: string,
  json: boolean,
  play: (roomId: string) => Promise<number>,
  deadline?: number,
): Promise<number> {
  const played = new Set<string>();

  for (;;) {
    if (deadline !== undefined && Date.now() >= deadline) {
      emit(line({ action: "tournament_follow", result: "error", error_code: "timeout" }), json);
      return 1;
    }

    const where = await placement(tournamentId);
    if (!where) {
      emit(line({ action: "tournament_follow", result: "error", error_code: "unavailable" }), json);
      return 1;
    }

    if (where.status === "COMPLETED" || where.eliminated) {
      emit(
        line({
          action: "tournament_result",
          result: "ok",
          tournament: tournamentId,
          champion: where.champion,
          eliminated: where.eliminated,
        }),
        json,
      );
      if (!json) {
        process.stdout.write(where.champion ? "you won the tournament\n" : "you are out — thanks for playing\n");
      }
      return 0;
    }

    // A room this player has already finished still shows as theirs until the round advances, so
    // the id it has played is what says "wait" rather than the presence of a room.
    if (where.roomId && !played.has(where.roomId)) {
      played.add(where.roomId);
      if (!json) process.stdout.write(`round ${where.roundNumber}: room ${where.roomId}\n`);
      emit(
        line({
          action: "tournament_room",
          result: "ok",
          tournament: tournamentId,
          room: where.roomId,
          round: where.roundNumber ?? undefined,
        }),
        json,
      );
      await play(where.roomId);
      continue;
    }

    await new Promise((r) => setTimeout(r, POLL_MS));
  }
}

/**
 * Enter a tournament and register for it. Shared by the human command and `bot --tournament`, so
 * "which tournament am I in" is decided once — a second answer here is how the two would drift.
 */
export async function registerForTournament(
  flags: Record<string, string | boolean>,
  json: boolean,
  attempts = 5,
): Promise<string | null> {
  const tournamentId = await enterTournament(flags, json);
  if (!tournamentId) return null;

  // A `409 contended` means the write lost every race for a sequence number and nothing was
  // recorded — so it is the one status worth retrying. Four players registering in the same second
  // is the normal case at the start of a tournament, and in P7's drill it cost a registration that
  // every side reported as successful.
  for (let attempt = 0; attempt < attempts; attempt++) {
    const reply = await call("POST", `/tournaments/${tournamentId}/register`);
    if (reply.status !== 409) {
      emit(resultLine("tournament_register", reply, { tournament: tournamentId }), json);
      return reply.status < 300 ? tournamentId : null;
    }
    await new Promise((r) => setTimeout(r, 200 * (attempt + 1)));
  }

  emit(line({ action: "tournament_register", result: "error", error_code: "contended", tournament: tournamentId }), json);
  return null;
}

export async function tournamentCommand(
  argv: string[],
  flags: Record<string, string | boolean>,
  json: boolean,
): Promise<number> {
  const [sub] = argv;

  if (sub === "register") {
    const tournamentId = await registerForTournament(flags, json);
    if (!tournamentId) return 1;
    if (!json) process.stdout.write(`registered for ${tournamentId} — waiting for the draw\n`);
    return followTournament(tournamentId, json, (roomId) => playAssignedRoom(roomId, json));
  }

  if (sub === "status") {
    const tournamentId = flags.id ? String(flags.id) : await openTournament();
    if (!tournamentId) {
      process.stderr.write("no open tournament — pass --id\n");
      return 2;
    }
    const reply = await call("GET", `/tournaments/${tournamentId}`);
    emit(resultLine("tournament_status", reply, { tournament: tournamentId }), json);
    if (!json && reply.status === 200) process.stdout.write(render(reply.payload));
    return reply.status < 300 ? 0 : 1;
  }

  if (sub === "bracket") {
    const tournamentId = flags.id ? String(flags.id) : await openTournament();
    if (!tournamentId) {
      process.stderr.write("no open tournament — pass --id\n");
      return 2;
    }
    const reply = await call("GET", `/tournaments/${tournamentId}/bracket`);
    emit(resultLine("tournament_bracket", reply, { tournament: tournamentId }), json);
    if (!json && reply.status === 200) process.stdout.write(renderBracket(reply.payload));
    return reply.status < 300 ? 0 : 1;
  }

  process.stderr.write("usage: tournament <register|status|bracket> [--id ID]\n");
  return 2;
}

interface RoundView {
  roundNumber: number;
  isFinal: boolean;
  complete: boolean;
  rooms: { roomId: string; players: string[]; advancing: string[] | null }[];
}

function render(payload: Record<string, unknown>): string {
  const status = String(payload.status ?? "?");
  const registered = (payload.registered as string[]) ?? [];
  const rounds = (payload.rounds as RoundView[]) ?? [];
  const lines = [`tournament ${payload.tournamentId} — ${status} (${registered.length}/${payload.minPlayers} players)`];
  for (const round of rounds) {
    lines.push(`  round ${round.roundNumber}${round.isFinal ? " (final)" : ""}${round.complete ? " complete" : ""}`);
    for (const room of round.rooms) {
      const advancing = room.advancing ? ` → ${room.advancing.join(", ")}` : " — playing";
      lines.push(`    ${room.players.join(" vs ")}${advancing}`);
    }
  }
  if (payload.champion) lines.push(`  champion: ${payload.champion}`);
  return lines.join("\n") + "\n";
}

function renderBracket(payload: Record<string, unknown>): string {
  const rounds = (payload.rounds as RoundView[]) ?? [];
  const placements = (payload.placements as { playerId: string; placement: number }[]) ?? [];
  const lines = [`bracket ${payload.tournamentId} — ${payload.status ?? "unknown"}`];
  for (const round of rounds) {
    lines.push(`  round ${round.roundNumber}${round.isFinal ? " (final)" : ""}`);
    for (const room of round.rooms) {
      lines.push(`    ${room.players.join(" vs ")}${room.advancing ? ` → ${room.advancing.join(", ")}` : ""}`);
    }
  }
  for (const entry of placements) lines.push(`  ${entry.placement}. ${entry.playerId}`);
  return lines.join("\n") + "\n";
}
