import { describe, expect, it } from "vitest";
import { handle } from "../src/app.js";

describe("spectator placeholder", () => {
  it("health is ok and names the service", () => {
    const res = handle("GET", "/health");
    expect(res.status).toBe(200);
    expect((res.json as { service: string }).service).toBe("spectator");
  });

  it("spectate returns a zero spectator count", () => {
    const res = handle("GET", "/spectate");
    expect(res.status).toBe(200);
    expect(res.json).toEqual({ spectators: 0 });
  });

  it("unknown route is 404", () => {
    expect(handle("GET", "/nope").status).toBe(404);
  });
});
