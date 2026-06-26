// Identity & Session — real slice for the fully-wired service.
//
// Implements the small piece the Client CLI asserts against (faculty steer: "1 servicio, una
// parte, que valide además de los tests"): register -> login -> whoami, plus /health. Storage is
// an in-memory map (no real DB needed for the checkpoint). The HTTP handling is a pure function
// so unit tests exercise register->whoami without sockets.

import { createHmac, randomUUID } from "node:crypto";

const TOKEN_SECRET = process.env.IDENTITY_TOKEN_SECRET ?? "dev-secret";
const SERVICE = "identity";

interface User {
  userId: string;
  user: string;
  pass: string;
}

export interface Reply {
  status: number;
  json: unknown;
}

export class Identity {
  private byName = new Map<string, User>();
  private byUserId = new Map<string, User>();

  register(user: string, pass: string): Reply {
    if (!user || !pass) return { status: 400, json: { error: "user and pass required" } };
    if (this.byName.has(user)) return { status: 409, json: { error: "user exists" } };
    const u: User = { userId: randomUUID(), user, pass };
    this.byName.set(user, u);
    this.byUserId.set(u.userId, u);
    return { status: 201, json: { result: "ok", userId: u.userId, user: u.user, token: this.mint(u) } };
  }

  login(user: string, pass: string): Reply {
    const u = this.byName.get(user);
    if (!u || u.pass !== pass) return { status: 401, json: { error: "invalid credentials" } };
    return { status: 200, json: { result: "ok", userId: u.userId, user: u.user, token: this.mint(u) } };
  }

  whoami(token: string | undefined): Reply {
    const u = token ? this.verify(token) : undefined;
    if (!u) return { status: 401, json: { error: "unauthenticated" } };
    return { status: 200, json: { result: "ok", userId: u.userId, user: u.user } };
  }

  private mint(u: User): string {
    const sig = createHmac("sha256", TOKEN_SECRET).update(u.userId).digest("hex");
    return Buffer.from(`${u.userId}:${sig}`).toString("base64url");
  }

  private verify(token: string): User | undefined {
    try {
      const [userId, sig] = Buffer.from(token, "base64url").toString().split(":");
      const expected = createHmac("sha256", TOKEN_SECRET).update(userId).digest("hex");
      if (sig !== expected) return undefined;
      return this.byUserId.get(userId);
    } catch {
      return undefined;
    }
  }
}

// Pure request handler — testable without a server.
export function handle(
  app: Identity,
  method: string,
  path: string,
  headers: Record<string, string | undefined>,
  body: Record<string, unknown>,
): Reply {
  const bearer = headers["authorization"]?.replace(/^Bearer\s+/i, "");
  if (method === "GET" && path === "/health") {
    return { status: 200, json: { status: "ok", service: SERVICE } };
  }
  if (method === "POST" && path === "/register") {
    return app.register(String(body.user ?? ""), String(body.pass ?? ""));
  }
  if (method === "POST" && path === "/login") {
    return app.login(String(body.user ?? ""), String(body.pass ?? ""));
  }
  if (method === "GET" && path === "/whoami") {
    return app.whoami(bearer);
  }
  return { status: 404, json: { error: "not found" } };
}

export { SERVICE };
