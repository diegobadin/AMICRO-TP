import { describe, expect, it } from "vitest";
import { handle, Identity } from "../src/app.js";
import type { SessionEvents } from "../src/events.js";
import { MemoryCache, Sessions } from "../src/sessions.js";
import { MemoryStore, type Store } from "../src/store.js";

class RecordingEvents implements SessionEvents {
  readonly published: Array<{ playerId: string; oldSessionId: string; newSessionId: string | null; reason: string }> = [];
  async invalidated(playerId: string, oldSessionId: string, newSessionId: string | null, reason: string): Promise<void> {
    this.published.push({ playerId, oldSessionId, newSessionId, reason });
  }
}

function newApp(): { app: Identity; store: Store; cache: MemoryCache; events: RecordingEvents } {
  const store = new MemoryStore();
  const cache = new MemoryCache();
  const events = new RecordingEvents();
  return { app: new Identity(store, new Sessions(store, cache, "test-secret", 60, events)), store, cache, events };
}

const post = (a: Identity, path: string, body: Record<string, unknown>, token?: string) =>
  handle(a, "POST", path, token ? { authorization: `Bearer ${token}` } : {}, body);
const get = (a: Identity, path: string, token?: string) =>
  handle(a, "GET", path, token ? { authorization: `Bearer ${token}` } : {}, {});
const tokenOf = (reply: { json: unknown }) => (reply.json as { token: string }).token;

async function registered(user = "alice", pass = "pw") {
  const ctx = newApp();
  const reply = await post(ctx.app, "/auth/register", { user, pass });
  return { ...ctx, token: tokenOf(reply), reply };
}

describe("identity accounts", () => {
  it("health is ok", async () => {
    expect((await get(newApp().app, "/health")).status).toBe(200);
  });

  it("register then whoami returns the same user", async () => {
    const { app, token, reply } = await registered();
    expect(reply.status).toBe(201);
    const who = await get(app, "/auth/whoami", token);
    expect(who.status).toBe(200);
    expect((who.json as { user: string }).user).toBe("alice");
  });

  it("login with the right password issues a token, a wrong one does not", async () => {
    const { app } = await registered("carla");
    expect((await post(app, "/auth/login", { user: "carla", pass: "pw" })).status).toBe(200);
    expect((await post(app, "/auth/login", { user: "carla", pass: "nope" })).status).toBe(401);
    expect((await post(app, "/auth/login", { user: "ghost", pass: "pw" })).status).toBe(401);
  });

  it("stores a hash, never the password", async () => {
    // A distinctive password on purpose. The salt and the derived key are base64, so a two-letter
    // password like "pw" turns up inside them by pure chance in roughly one run in fifty — this
    // assertion used to fail at random on CI while proving nothing about the code.
    const secret = "correct-horse-battery-staple";
    const { store } = await registered("dani", secret);
    const player = await store.findByUsername("dani");
    expect(player?.passwordHash).toMatch(/^scrypt\$/);
    expect(player?.passwordHash).not.toContain(secret);
  });

  it("whoami without a token is 401", async () => {
    expect((await get(newApp().app, "/auth/whoami")).status).toBe(401);
  });

  it("duplicate register is 409, case-insensitively", async () => {
    const { app } = await registered("bob");
    expect((await post(app, "/auth/register", { user: "bob", pass: "pw" })).status).toBe(409);
    expect((await post(app, "/auth/register", { user: "BOB", pass: "pw" })).status).toBe(409);
  });
});

describe("identity sessions", () => {
  it("issues a JWT carrying the player and the session", async () => {
    const { token } = await registered();
    const claims = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString());
    expect(claims.sub).toMatch(/^[0-9a-f-]{36}$/);
    expect(claims.sid).toMatch(/^[0-9a-f-]{36}$/);
    expect(claims.exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
  });

  it("logout invalidates the token it was called with", async () => {
    const { app, token } = await registered();
    expect((await post(app, "/auth/logout", {}, token)).status).toBe(200);
    expect((await get(app, "/auth/whoami", token)).status).toBe(401);
  });

  it("logout is idempotent and never fails the caller", async () => {
    const { app, token } = await registered();
    await post(app, "/auth/logout", {}, token);
    const again = await post(app, "/auth/logout", {}, token);
    expect(again.status).toBe(200);
    expect((again.json as { closed: boolean }).closed).toBe(false);
  });

  it("a second login supersedes the first session (single-active-session)", async () => {
    const { app, token: first } = await registered("erin");
    const second = tokenOf(await post(app, "/auth/login", { user: "erin", pass: "pw" }));
    expect(second).not.toBe(first);
    expect((await get(app, "/auth/whoami", first)).status).toBe(401);
    expect((await get(app, "/auth/whoami", second)).status).toBe(200);
  });

  it("falls back to the database when the cache is empty, and still refuses revoked sessions", async () => {
    const { app, store, cache, token } = await registered("frank");
    const sid = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString()).sid;
    await cache.drop(sid);
    expect((await get(app, "/auth/whoami", token)).status).toBe(200);

    await store.closeSession(sid, "revoked-elsewhere");
    await cache.drop(sid);
    expect((await get(app, "/auth/whoami", token)).status).toBe(401);
  });

  it("announces the invalidation so the gateway can kill the old stream", async () => {
    const { app, events, token: first } = await registered("hugo");
    await post(app, "/auth/login", { user: "hugo", pass: "pw" });
    expect(events.published).toHaveLength(1);
    expect(events.published[0].reason).toBe("superseded");
    expect(events.published[0].newSessionId).not.toBeNull();

    await post(app, "/auth/logout", {}, first); // already superseded — nothing left to announce
    expect(events.published).toHaveLength(1);
  });

  it("announces a logout too, with no successor session", async () => {
    const { app, events, token } = await registered("iris");
    await post(app, "/auth/logout", {}, token);
    expect(events.published).toEqual([
      expect.objectContaining({ reason: "logout", newSessionId: null }),
    ]);
  });

  it("a token signed with another secret is rejected", async () => {
    const { token } = await registered("gina");
    const other = newApp();
    expect((await get(other.app, "/auth/whoami", token)).status).toBe(401);
  });
});
