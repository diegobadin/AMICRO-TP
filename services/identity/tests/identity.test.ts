import { describe, expect, it } from "vitest";
import { handle, Identity } from "../src/app.js";
import { MemoryStore } from "../src/store.js";

const newApp = () => new Identity(new MemoryStore());
const post = (a: Identity, path: string, body: Record<string, unknown>, token?: string) =>
  handle(a, "POST", path, token ? { authorization: `Bearer ${token}` } : {}, body);
const get = (a: Identity, path: string, token?: string) =>
  handle(a, "GET", path, token ? { authorization: `Bearer ${token}` } : {}, {});

describe("identity real slice", () => {
  it("health is ok", async () => {
    expect((await get(newApp(), "/health")).status).toBe(200);
  });

  it("register then whoami returns the same user", async () => {
    const a = newApp();
    const reg = await post(a, "/auth/register", { user: "alice", pass: "pw" });
    expect(reg.status).toBe(201);
    const token = (reg.json as { token: string }).token;
    const who = await get(a, "/auth/whoami", token);
    expect(who.status).toBe(200);
    expect((who.json as { user: string }).user).toBe("alice");
  });

  it("login with the right password issues a token, a wrong one does not", async () => {
    const a = newApp();
    await post(a, "/auth/register", { user: "carla", pass: "pw" });
    expect((await post(a, "/auth/login", { user: "carla", pass: "pw" })).status).toBe(200);
    expect((await post(a, "/auth/login", { user: "carla", pass: "nope" })).status).toBe(401);
    expect((await post(a, "/auth/login", { user: "ghost", pass: "pw" })).status).toBe(401);
  });

  it("stores a hash, never the password", async () => {
    const store = new MemoryStore();
    await new Identity(store).register("dani", "pw");
    const player = await store.findByUsername("dani");
    expect(player?.passwordHash).toMatch(/^scrypt\$/);
    expect(player?.passwordHash).not.toContain("pw");
  });

  it("whoami without a token is 401", async () => {
    expect((await get(newApp(), "/auth/whoami")).status).toBe(401);
  });

  it("duplicate register is 409, case-insensitively", async () => {
    const a = newApp();
    await post(a, "/auth/register", { user: "bob", pass: "pw" });
    expect((await post(a, "/auth/register", { user: "bob", pass: "pw" })).status).toBe(409);
    expect((await post(a, "/auth/register", { user: "BOB", pass: "pw" })).status).toBe(409);
  });
});
