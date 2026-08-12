// Routing and the trust boundary, as pure functions of (method, path, headers) so the tests
// exercise them without sockets. Only the stream needs the raw response, and it lives in server.ts
// for that reason — the same split the gateway uses.
//
// This service sits INSIDE the trust boundary: it is `ClusterIP`, unreachable from outside, and it
// trusts `X-Player-Id` and `X-Session-Id` because the gateway overwrites whatever a client sent.
// Both are required. Either one alone is a 401 — the same rule room-gameplay follows, and a probe
// that sets only one looks like a bug and is not.

export const SERVICE = "spectator";

const SPECTATE = /^\/rooms\/([^/]+)\/spectate$/;

export interface Reply {
  status: number;
  json: unknown;
}

export type Action =
  | { kind: "reply"; reply: Reply }
  | { kind: "metrics" }
  | { kind: "stream"; roomId: string; spectatorId: string };

export interface Principal {
  playerId: string;
  sessionId: string;
}

export function principalFrom(headers: Record<string, string | undefined>): Principal | undefined {
  const playerId = headers["x-player-id"];
  const sessionId = headers["x-session-id"];
  if (!playerId || !sessionId) return undefined;
  return { playerId, sessionId };
}

export function handle(
  method: string,
  path: string,
  headers: Record<string, string | undefined> = {},
): Action {
  const pathname = path.split("?")[0];

  if (method === "GET" && pathname === "/health") {
    // Alive, and nothing else. A liveness probe wired to Kafka or Redis turns their outage into a
    // restart loop, which is the opposite of what a consumer with its own retry should do
    // (CHANGELOG-design.md §10.11). Whether they answer is on /metrics.
    return { kind: "reply", reply: { status: 200, json: { status: "ok", service: SERVICE } } };
  }

  if (method === "GET" && pathname === "/metrics") {
    return { kind: "metrics" };
  }

  const match = method === "GET" ? SPECTATE.exec(pathname) : null;
  if (match) {
    const principal = principalFrom(headers);
    if (!principal) {
      return { kind: "reply", reply: { status: 401, json: { error: "unauthorized" } } };
    }
    return { kind: "stream", roomId: match[1], spectatorId: principal.playerId };
  }

  return { kind: "reply", reply: { status: 404, json: { error: "not_found", service: SERVICE } } };
}
