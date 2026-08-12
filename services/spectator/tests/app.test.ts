import { describe, expect, it } from "vitest";
import { handle, principalFrom } from "../src/app.js";

const AUTH = { "x-player-id": "carol", "x-session-id": "s-1" };

describe("routing", () => {
  it("health reports the process is alive and nothing else", () => {
    const action = handle("GET", "/health");
    expect(action).toEqual({ kind: "reply", reply: { status: 200, json: { status: "ok", service: "spectator" } } });
  });

  it("metrics is its own action", () => {
    expect(handle("GET", "/metrics").kind).toBe("metrics");
  });

  it("spectate with both headers becomes a stream", () => {
    const action = handle("GET", "/rooms/room-1/spectate", AUTH);
    expect(action).toEqual({ kind: "stream", roomId: "room-1", spectatorId: "carol" });
  });

  it("either header alone is a 401", () => {
    // Both are required, the same rule room-gameplay follows. A probe that sets only one looks
    // like a bug and is not.
    for (const headers of [{ "x-player-id": "carol" }, { "x-session-id": "s-1" }, {}]) {
      const action = handle("GET", "/rooms/room-1/spectate", headers);
      expect(action).toEqual({ kind: "reply", reply: { status: 401, json: { error: "unauthorized" } } });
    }
  });

  it("ignores a query string when matching", () => {
    expect(handle("GET", "/rooms/room-1/spectate?x=1", AUTH).kind).toBe("stream");
  });

  it("is read-only: no verb but GET routes anywhere", () => {
    for (const method of ["POST", "DELETE", "PATCH", "PUT"]) {
      const action = handle(method, "/rooms/room-1/spectate", AUTH);
      expect(action.kind).toBe("reply");
      expect((action as { reply: { status: number } }).reply.status).toBe(404);
    }
  });

  it("unknown path is a 404", () => {
    const action = handle("GET", "/nope", AUTH);
    expect((action as { reply: { status: number } }).reply.status).toBe(404);
  });

  it("principalFrom needs both halves", () => {
    expect(principalFrom(AUTH)).toEqual({ playerId: "carol", sessionId: "s-1" });
    expect(principalFrom({ "x-player-id": "carol" })).toBeUndefined();
  });
});
