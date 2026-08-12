// P6's four read surfaces, all behind the one door.

import { SignJWT } from "jose";
import { beforeAll, describe, expect, it } from "vitest";
import { PLAYER_HEADER, SESSION_HEADER, downstreamHeaders, resolve, route } from "../src/app.js";
import { Tokens } from "../src/auth.js";

const SECRET = "test-secret";
const tokens = new Tokens(SECRET);
const key = new TextEncoder().encode(SECRET);
const live = { tokens, revoked: () => false };

let bearer: string;

beforeAll(async () => {
  bearer = await new SignJWT({ sid: "session-1" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject("player-1")
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(key);
});

// A function, not a const: `bearer` is assigned in beforeAll, and a const here would capture
// `undefined` at module load and quietly send `Bearer undefined` to every assertion.
const authorized = () => ({ authorization: `Bearer ${bearer}` });

describe("the P6 route table", () => {
  it("routes each surface to the service that owns it", () => {
    expect(route("GET", "/rooms/room-1/spectate")?.target).toBe("spectator");
    expect(route("GET", "/players/alice/rating")?.target).toBe("ranking");
    expect(route("GET", "/players/alice/rating-history")?.target).toBe("ranking");
    expect(route("GET", "/leaderboard")?.target).toBe("ranking");
    expect(route("GET", "/stats/overview")?.target).toBe("analytics");
    expect(route("GET", "/stats/players/alice")?.target).toBe("analytics");
    expect(route("GET", "/stats/rooms/room-1")?.target).toBe("analytics");
  });

  it("labels them for metrics without the ids in them", () => {
    // The raw URL as a label would let anyone grow the cardinality by inventing paths.
    expect(route("GET", "/players/alice/rating")?.label).toBe("/players/:id/rating");
    expect(route("GET", "/stats/rooms/room-1")?.label).toBe("/stats/rooms/:id");
    expect(route("GET", "/rooms/room-1/spectate")?.label).toBe("/rooms/:id/spectate");
  });

  it("keeps the two streaming routes apart", () => {
    // Same shape, opposite rule: `/stream` is the player's and is served here from Redis;
    // `/spectate` is the spectator service's and must never require membership.
    expect(route("GET", "/rooms/room-1/stream")?.target).toBe("gateway");
    expect(route("GET", "/rooms/room-1/spectate")?.target).toBe("spectator");
    expect(route("GET", "/rooms/room-1/spectate")?.stream).toBe(true);
  });

  it("is read-only: no write verb reaches a read model", () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
      expect(route(method, "/leaderboard")).toBeUndefined();
      expect(route(method, "/stats/overview")).toBeUndefined();
      expect(route(method, "/rooms/room-1/spectate")).toBeUndefined();
      expect(route(method, "/players/alice/rating")).toBeUndefined();
    }
  });

  it("does not collide with the room routes it sits beside", () => {
    expect(route("GET", "/rooms/room-1")?.target).toBe("room-gameplay");
    expect(route("GET", "/stats/rooms/room-1")?.target).toBe("analytics");
  });
});

describe("every new surface needs a session (E4)", () => {
  it("401s without one", async () => {
    for (const path of [
      "/rooms/room-1/spectate",
      "/players/alice/rating",
      "/players/alice/rating-history",
      "/leaderboard",
      "/stats/overview",
      "/stats/players/alice",
      "/stats/rooms/room-1",
    ]) {
      const outcome = await resolve(live, "GET", path, {}, "c1");
      expect(outcome.kind, path).toBe("reply");
      expect((outcome as { reply: { status: number } }).reply.status, path).toBe(401);
    }
  });

  it("proxies the reads with one", async () => {
    for (const path of ["/players/alice/rating", "/leaderboard", "/stats/overview"]) {
      expect((await resolve(live, "GET", path, authorized(), "c1")).kind, path).toBe("proxy");
    }
  });

  it("streams spectate with one", async () => {
    const outcome = await resolve(live, "GET", "/rooms/room-1/spectate", authorized(), "c1");
    expect(outcome.kind).toBe("stream");
    expect((outcome as { route: { target: string } }).route.target).toBe("spectator");
  });

  it("refuses a superseded session on a read model too", async () => {
    const superseded = { tokens, revoked: (sid: string) => sid === "session-1" };
    const outcome = await resolve(superseded, "GET", "/leaderboard", authorized(), "c1");
    expect((outcome as { reply: { json: { error: string } } }).reply.json.error).toBe(
      "session_superseded",
    );
  });
});

describe("headers crossing to the read models", () => {
  const principal = { playerId: "player-1", sessionId: "session-1" };

  it("tells every backend who the caller is", () => {
    for (const target of ["spectator", "ranking", "analytics"] as const) {
      const headers = downstreamHeaders(
        { target, label: "/x", auth: true, stream: false },
        {},
        "c1",
        principal,
      );
      expect(headers[PLAYER_HEADER], target).toBe("player-1");
      expect(headers[SESSION_HEADER], target).toBe("session-1");
    }
  });

  it("overwrites what the client tried to claim", () => {
    // The security control the whole trust boundary rests on: the map is built from scratch, so a
    // client-supplied X-Player-Id can never reach a service that trusts it.
    const headers = downstreamHeaders(
      { target: "spectator", label: "/x", auth: true, stream: true },
      { [PLAYER_HEADER]: "somebody-else", [SESSION_HEADER]: "forged" },
      "c1",
      principal,
    );
    expect(headers[PLAYER_HEADER]).toBe("player-1");
    expect(headers[SESSION_HEADER]).toBe("session-1");
  });

  it("carries the trust headers on the spectate stream", async () => {
    const outcome = await resolve(live, "GET", "/rooms/room-1/spectate", authorized(), "c1");
    const headers = (outcome as { headers: Record<string, string> }).headers;
    expect(headers[PLAYER_HEADER]).toBe("player-1");
    expect(headers[SESSION_HEADER]).toBe("session-1");
  });

  it("still sends no bearer to a backend that does not verify one", () => {
    const headers = downstreamHeaders(
      { target: "ranking", label: "/leaderboard", auth: true, stream: false },
      { authorization: "Bearer something" },
      "c1",
      principal,
    );
    expect(headers["authorization"]).toBeUndefined();
  });
});
