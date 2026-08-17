import { afterEach, describe, expect, it, vi } from "vitest";
import { useSession } from "../src/api.js";
import { followTournament } from "../src/tournament.js";

/**
 * The tournament client (P7 F8). What is worth testing here is the loop that decides when this
 * player plays — the bracket itself is the service's business, and a client that re-derived it
 * would be the second-copy-of-a-rule defect P5 and P6 each caught once.
 */

const PLAYER = "84740d1d-9ed0-4799-9464-c42f57fec30c";

function session(): void {
  useSession({ token: "t", user: "p7alice", userId: PLAYER });
}

/** Answers `/tournaments/{id}/players/{me}` from a script, one reply per poll. */
function placements(replies: Record<string, unknown>[]): typeof fetch {
  let call = 0;
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const payload = replies[Math.min(call, replies.length - 1)];
    call += 1;
    return new Response(JSON.stringify(payload), {
      status: 200,
      headers: { "content-type": "application/json", "x-correlation-id": `c-${url.length}` },
    });
  }) as unknown as typeof fetch;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("following a tournament", () => {
  it("plays each round's room once and stops when the bracket does", async () => {
    session();
    const fetchMock = placements([
      { tournamentId: "t1", status: "IN_PROGRESS", roundNumber: 1, roomId: "room-1", eliminated: false, champion: false },
      // Still shows room-1 while the round is being scored: already played, so this is a wait.
      { tournamentId: "t1", status: "IN_PROGRESS", roundNumber: 1, roomId: "room-1", eliminated: false, champion: false },
      { tournamentId: "t1", status: "IN_PROGRESS", roundNumber: 2, roomId: "room-2", eliminated: false, champion: false },
      { tournamentId: "t1", status: "COMPLETED", roundNumber: 2, roomId: "room-2", eliminated: false, champion: true },
    ]);
    vi.stubGlobal("fetch", fetchMock);

    const played: string[] = [];
    const code = await followTournament("t1", true, async (roomId) => {
      played.push(roomId);
      return 0;
    });

    expect(code).toBe(0);
    expect(played).toEqual(["room-1", "room-2"]);
  });

  it("stops when this player is out, without playing anything else", async () => {
    session();
    vi.stubGlobal(
      "fetch",
      placements([
        { tournamentId: "t1", status: "IN_PROGRESS", roundNumber: 1, roomId: "room-1", eliminated: true, champion: false },
      ]),
    );

    const played: string[] = [];
    const code = await followTournament("t1", true, async (roomId) => {
      played.push(roomId);
      return 0;
    });

    expect(code).toBe(0);
    expect(played).toEqual([]);
  });

  /**
   * P6's only user-facing bug was a command asking about the session's display *name* where the
   * backend keys on the player **id**. The answer was wrong and confident. This is the same
   * question one service further out, so it gets the same test.
   */
  it("asks about the player id, never the display name", async () => {
    session();
    const seen: string[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      seen.push(String(input));
      return new Response(
        JSON.stringify({ tournamentId: "t1", status: "COMPLETED", eliminated: false, champion: false }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    }) as unknown as typeof fetch;
    vi.stubGlobal("fetch", fetchMock);

    await followTournament("t1", true, async () => 0);

    expect(seen[0]).toContain(`/tournaments/t1/players/${PLAYER}`);
    expect(seen[0]).not.toContain("p7alice");
  });

  it("gives up when the tournament cannot be reached, rather than looping for ever", async () => {
    session();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("nope", { status: 503 })) as unknown as typeof fetch,
    );

    expect(await followTournament("t1", true, async () => 0)).toBe(1);
  });

  it("honours a deadline while it is waiting to be drawn", async () => {
    session();
    vi.stubGlobal(
      "fetch",
      placements([
        { tournamentId: "t1", status: "REGISTRATION", roomId: null, eliminated: false, champion: false },
      ]),
    );

    const code = await followTournament("t1", true, async () => 0, Date.now() - 1);
    expect(code).toBe(1);
  });
});

describe("entering a tournament", () => {
  /**
   * The first P7 drill produced four tournaments with one player each: four bots started together,
   * all found nothing, all created, and none ever reached the threshold. Converging on the lowest
   * id is the same rule P3 used for rooms — every client applies it to the same list and lands in
   * the same place, with no coordinator.
   */
  it("converges on the lowest open tournament even when everyone created one", async () => {
    session();
    const registered: string[] = [];
    const created = ["ffff-4", "aaaa-1", "cccc-3"];
    let creates = 0;

    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url.endsWith("/tournaments") && method === "GET") {
          // Every racer's tournament is already listed by the time anyone re-reads.
          return new Response(
            JSON.stringify(created.map((id) => ({ tournamentId: id, status: "REGISTRATION" }))),
            { status: 200, headers: { "content-type": "application/json" } },
          );
        }
        if (url.endsWith("/tournaments") && method === "POST") {
          creates += 1;
          return new Response(JSON.stringify({ tournamentId: "zzzz-9" }), {
            status: 201,
            headers: { "content-type": "application/json" },
          });
        }
        registered.push(url);
        return new Response(JSON.stringify({ tournamentId: url }), {
          status: 201,
          headers: { "content-type": "application/json" },
        });
      }) as unknown as typeof fetch,
    );

    const { registerForTournament } = await import("../src/tournament.js");
    const chosen = await registerForTournament({}, true);

    expect(chosen).toBe("aaaa-1");
    expect(creates).toBe(0);
    expect(registered[0]).toContain("/tournaments/aaaa-1/register");
  });
});
