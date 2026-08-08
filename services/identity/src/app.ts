// Identity & Session — the real service.
//
// Accounts live in Postgres with scrypt-hashed credentials and sessions are JWTs backed by the
// `sessions` table, behind the HTTP surface the architecture names
// (docs/architecture/01-service-architecture.md §5.3). The request handler is a pure function over
// injected ports, so the unit tests exercise the whole flow without a database, a Redis or a
// socket.

import { randomUUID } from "node:crypto";
import { hashPassword, verifyPassword } from "./passwords.js";
import type { Sessions } from "./sessions.js";
import type { Player, Store } from "./store.js";

const SERVICE = "identity";

export interface Reply {
  status: number;
  json: unknown;
}

export class Identity {
  constructor(
    private readonly store: Store,
    private readonly sessions: Sessions,
  ) {}

  async register(username: string, pass: string): Promise<Reply> {
    if (!username || !pass) return { status: 400, json: { error: "user and pass required" } };
    const player = await this.store.createPlayer(randomUUID(), username, await hashPassword(pass));
    if (!player) return { status: 409, json: { error: "user exists" } };
    return { status: 201, json: await this.session(player) };
  }

  async login(username: string, pass: string): Promise<Reply> {
    const player = await this.store.findByUsername(username);
    if (!player || !(await verifyPassword(pass, player.passwordHash))) {
      return { status: 401, json: { error: "invalid credentials" } };
    }
    return { status: 200, json: await this.session(player) };
  }

  async whoami(token: string | undefined): Promise<Reply> {
    const session = await this.sessions.resolve(token);
    const player = session ? await this.store.findById(session.playerId) : undefined;
    if (!player) return { status: 401, json: { error: "unauthenticated" } };
    return { status: 200, json: { result: "ok", userId: player.playerId, user: player.username } };
  }

  // Idempotent on purpose: a client whose session was already superseded should still be able to
  // clean up without the CLI reporting a failure.
  async logout(token: string | undefined): Promise<Reply> {
    const session = await this.sessions.resolve(token);
    const closed = session ? await this.sessions.close(session.playerId, session.sessionId, "logout") : false;
    return { status: 200, json: { result: "ok", closed } };
  }

  private async session(player: Player): Promise<Record<string, unknown>> {
    const { token } = await this.sessions.open(player.playerId);
    return { result: "ok", userId: player.playerId, user: player.username, token };
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
  if (method === "POST" && path === "/auth/logout") {
    return app.logout(bearer);
  }
  if (method === "GET" && path === "/auth/whoami") {
    return app.whoami(bearer);
  }
  return { status: 404, json: { error: "not found" } };
}

export { SERVICE };
