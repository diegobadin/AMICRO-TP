// Identity & Session — the real service.
//
// Accounts live in Postgres with scrypt-hashed credentials, behind the HTTP surface the
// architecture names (docs/architecture/01-service-architecture.md §5.3). The request handler is
// a pure function over an injected Store, so the unit tests exercise register -> login -> whoami
// without a database and without a socket.

import { createHmac, randomUUID } from "node:crypto";
import { hashPassword, verifyPassword } from "./passwords.js";
import type { Player, Store } from "./store.js";

const TOKEN_SECRET = process.env.IDENTITY_JWT_SECRET ?? "dev-secret";
const SERVICE = "identity";

export interface Reply {
  status: number;
  json: unknown;
}

export class Identity {
  constructor(private readonly store: Store) {}

  async register(username: string, pass: string): Promise<Reply> {
    if (!username || !pass) return { status: 400, json: { error: "user and pass required" } };
    const player = await this.store.createPlayer(randomUUID(), username, await hashPassword(pass));
    if (!player) return { status: 409, json: { error: "user exists" } };
    return { status: 201, json: this.session(player) };
  }

  async login(username: string, pass: string): Promise<Reply> {
    const player = await this.store.findByUsername(username);
    if (!player || !(await verifyPassword(pass, player.passwordHash))) {
      return { status: 401, json: { error: "invalid credentials" } };
    }
    return { status: 200, json: this.session(player) };
  }

  async whoami(token: string | undefined): Promise<Reply> {
    const playerId = token ? this.verify(token) : undefined;
    const player = playerId ? await this.store.findById(playerId) : undefined;
    if (!player) return { status: 401, json: { error: "unauthenticated" } };
    return { status: 200, json: { result: "ok", userId: player.playerId, user: player.username } };
  }

  private session(player: Player): Record<string, unknown> {
    return { result: "ok", userId: player.playerId, user: player.username, token: this.mint(player.playerId) };
  }

  private mint(playerId: string): string {
    const sig = createHmac("sha256", TOKEN_SECRET).update(playerId).digest("hex");
    return Buffer.from(`${playerId}:${sig}`).toString("base64url");
  }

  private verify(token: string): string | undefined {
    try {
      const [playerId, sig] = Buffer.from(token, "base64url").toString().split(":");
      const expected = createHmac("sha256", TOKEN_SECRET).update(playerId).digest("hex");
      return sig === expected ? playerId : undefined;
    } catch {
      return undefined;
    }
  }
}

// Pure request handler — testable without a server.
export async function handle(
  app: Identity,
  method: string,
  path: string,
  headers: Record<string, string | undefined>,
  body: Record<string, unknown>,
): Promise<Reply> {
  const bearer = headers["authorization"]?.replace(/^Bearer\s+/i, "");
  if (method === "GET" && path === "/health") {
    return { status: 200, json: { status: "ok", service: SERVICE } };
  }
  if (method === "POST" && path === "/auth/register") {
    return app.register(String(body.user ?? ""), String(body.pass ?? ""));
  }
  if (method === "POST" && path === "/auth/login") {
    return app.login(String(body.user ?? ""), String(body.pass ?? ""));
  }
  if (method === "GET" && path === "/auth/whoami") {
    return app.whoami(bearer);
  }
  return { status: 404, json: { error: "not found" } };
}

export { SERVICE };
