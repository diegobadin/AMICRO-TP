import { SignJWT } from "jose";
import { beforeAll, describe, expect, it } from "vitest";
import { downstreamHeaders, resolve, route } from "../src/app.js";
import { Tokens } from "../src/auth.js";
import { registry } from "../src/metrics.js";

const SECRET = "test-secret";
const tokens = new Tokens(SECRET);
const key = new TextEncoder().encode(SECRET);
const live = { tokens, revoked: () => false };

let bearer: string;
let signedByAnotherCluster: string;

const mint = (secret: Uint8Array, sub = "player-1", sid = "session-1"): Promise<string> =>
  new SignJWT({ sid })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(sub)
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(secret);

beforeAll(async () => {
  bearer = await mint(key);
  signedByAnotherCluster = await mint(new TextEncoder().encode("some-other-secret"));
});

describe("route table", () => {
  it("sends auth to identity and rooms to room-gameplay", () => {
    expect(route("POST", "/auth/login")?.target).toBe("identity");
    expect(route("GET", "/rooms")?.target).toBe("room-gameplay");
    expect(route("POST", "/rooms/r1/games/1/moves")?.target).toBe("room-gameplay");
  });

  it("keeps the stream on the gateway itself", () => {
    const matched = route("GET", "/rooms/r1/stream");
    expect(matched?.target).toBe("gateway");
    expect(matched?.stream).toBe(true);
  });

  it("labels are templates, so the metric cardinality is bounded", () => {
    expect(route("GET", "/rooms/any-id-at-all/games/7")?.label).toBe("/rooms/:id/games/:n");
  });

  it("does not match an unknown path or the wrong method", () => {
    expect(route("GET", "/nope")).toBeUndefined();
    expect(route("DELETE", "/rooms")).toBeUndefined();
  });

  it("routes all three membership verbs, reconnect included", () => {
    for (const method of ["POST", "DELETE", "PATCH"]) {
      expect(route(method, "/rooms/r1/players/p1")?.target).toBe("room-gameplay");
    }
  });

  // The timer worker reaches room-gameplay from inside the cluster, and nothing here may open a
  // path to it: a client that could tick a room could resolve its own turn timer early.
  it("has no route to anything internal", () => {
    for (const method of ["GET", "POST", "PATCH", "DELETE"]) {
      expect(route(method, "/internal/rooms/r1/tick")).toBeUndefined();
      expect(route(method, "/internal/anything")).toBeUndefined();
    }
  });

  it("only register and login are reachable without a session", () => {
    expect(route("POST", "/auth/register")?.auth).toBe(false);
    expect(route("POST", "/auth/login")?.auth).toBe(false);
    expect(route("GET", "/auth/whoami")?.auth).toBe(true);
    expect(route("POST", "/auth/logout")?.auth).toBe(true);
  });
});

describe("authentication", () => {
  it("rejects a missing, malformed or foreign token with 401", async () => {
    const rejected = [{}, { authorization: "Bearer nonsense" }, { authorization: `Bearer ${signedByAnotherCluster}` }];
    for (const headers of rejected) {
      const outcome = await resolve(live, "GET", "/rooms", headers, "c1");
      expect(outcome.kind).toBe("reply");
      expect(outcome.kind === "reply" && outcome.reply.status).toBe(401);
    }
  });

  it("passes a valid token through as a principal", async () => {
    const outcome = await resolve(live, "GET", "/rooms", { authorization: `Bearer ${bearer}` }, "c1");
    expect(outcome.kind).toBe("proxy");
    expect(outcome.kind === "proxy" && outcome.principal?.playerId).toBe("player-1");
  });

  it("lets an anonymous request reach register", async () => {
    expect((await resolve(live, "POST", "/auth/register", {}, "c1")).kind).toBe("proxy");
  });

  it("answers /health without a session", async () => {
    const outcome = await resolve(live, "GET", "/health", {}, "c1");
    expect(outcome.kind === "reply" && outcome.reply.status).toBe(200);
  });

  it("is a 404 before it is a 401 — an unknown path leaks nothing about auth", async () => {
    const outcome = await resolve(live, "GET", "/nope", {}, "c1");
    expect(outcome.kind === "reply" && outcome.reply.status).toBe(404);
  });

  it("refuses a still-valid token whose session identity killed, and says which it is", async () => {
    const superseded = { tokens, revoked: (sid: string) => sid === "session-1" };
    const outcome = await resolve(superseded, "GET", "/rooms", { authorization: `Bearer ${bearer}` }, "c1");
    expect(outcome.kind === "reply" && outcome.reply.status).toBe(401);
    expect(outcome.kind === "reply" && (outcome.reply.json as { error: string }).error).toBe("session_superseded");
  });
});

describe("headers crossing the trust boundary", () => {
  const rooms = route("GET", "/rooms")!;
  const whoami = route("GET", "/auth/whoami")!;
  const principal = { playerId: "player-1", sessionId: "session-1" };

  it("injects the identity of the verified token for room-gameplay", () => {
    const out = downstreamHeaders(rooms, {}, "c1", principal);
    expect(out["x-player-id"]).toBe("player-1");
    expect(out["x-session-id"]).toBe("session-1");
  });

  it("discards a client-supplied player id — the bypass the trust flip stands on", () => {
    const out = downstreamHeaders(rooms, { "x-player-id": "somebody-else" }, "c1", principal);
    expect(out["x-player-id"]).toBe("player-1");
  });

  it("never forwards the token to room-gameplay, which no longer validates one", () => {
    const out = downstreamHeaders(rooms, { authorization: `Bearer ${bearer}` }, "c1", principal);
    expect(out["authorization"]).toBeUndefined();
  });

  it("does forward it to identity, which owns sessions", () => {
    const out = downstreamHeaders(whoami, { authorization: `Bearer ${bearer}` }, "c1", principal);
    expect(out["authorization"]).toBe(`Bearer ${bearer}`);
  });

  it("carries the conditional-request headers P3's concurrency contract depends on", () => {
    const incoming = { "if-match": '"12"', "idempotency-key": "k1", cookie: "sneaky=1" };
    const out = downstreamHeaders(rooms, incoming, "c1", principal);
    expect(out["if-match"]).toBe('"12"');
    expect(out["idempotency-key"]).toBe("k1");
    expect(out["cookie"]).toBeUndefined();
    expect(out["x-correlation-id"]).toBe("c1");
  });
});

describe("metrics", () => {
  it("exposes the exact names P8 will build on", async () => {
    const exposed = await registry.metrics();
    expect(exposed).toContain("gateway_requests_total");
    expect(exposed).toContain("gateway_http_request_duration_seconds");
  });
});
