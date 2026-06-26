import { describe, expect, it } from "vitest";
import { handle, Identity } from "../src/app.js";

const post = (a: Identity, path: string, body: Record<string, unknown>, token?: string) =>
  handle(a, "POST", path, token ? { authorization: `Bearer ${token}` } : {}, body);
const get = (a: Identity, path: string, token?: string) =>
  handle(a, "GET", path, token ? { authorization: `Bearer ${token}` } : {}, {});

describe("identity real slice", () => {
  it("health is ok", () => {
    expect(get(new Identity(), "/health").status).toBe(200);
  });

  it("register then whoami returns the same user", () => {
    const a = new Identity();
    const reg = post(a, "/register", { user: "alice", pass: "pw" });
    expect(reg.status).toBe(201);
    const token = (reg.json as { token: string }).token;
    const who = get(a, "/whoami", token);
    expect(who.status).toBe(200);
    expect((who.json as { user: string }).user).toBe("alice");
  });

  it("whoami without a token is 401", () => {
    expect(get(new Identity(), "/whoami").status).toBe(401);
  });

  it("duplicate register is 409", () => {
    const a = new Identity();
    post(a, "/register", { user: "bob", pass: "pw" });
    expect(post(a, "/register", { user: "bob", pass: "pw" }).status).toBe(409);
  });
});
