import { describe, expect, it } from "vitest";
import { handle } from "../src/app.js";

describe("gateway placeholder", () => {
  it("health is ok and names the service", () => {
    const res = handle("GET", "/health");
    expect(res.status).toBe(200);
    expect((res.json as { service: string }).service).toBe("gateway");
  });

  it("rooms returns an empty list", () => {
    const res = handle("GET", "/rooms");
    expect(res.status).toBe(200);
    expect(res.json).toEqual([]);
  });

  it("unknown route is 404", () => {
    expect(handle("GET", "/nope").status).toBe(404);
  });
});
