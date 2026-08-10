// The gateway is the only thing that validates a session token. Everything behind it works from
// the headers this module produces, which is what let room-gameplay drop its copy of the signing
// key (CHANGELOG-design.md §8.9).
//
// Signature and expiry only — the same bar identity's own `resolve()` applies before it reaches
// its store. Revocation is not a property of the token, it arrives on Redis pub/sub, and it lands
// in F2.

import { jwtVerify } from "jose";

export interface Principal {
  playerId: string;
  sessionId: string;
}

export class Tokens {
  private readonly key: Uint8Array;

  constructor(secret: string) {
    this.key = new TextEncoder().encode(secret);
  }

  /** The bearer token of an `Authorization` header, or undefined if there is not one. */
  static bearer(header: string | undefined): string | undefined {
    if (!header) return undefined;
    const [scheme, token] = header.split(" ");
    return scheme?.toLowerCase() === "bearer" && token ? token : undefined;
  }

  async verify(header: string | undefined): Promise<Principal | undefined> {
    const token = Tokens.bearer(header);
    if (!token) return undefined;
    try {
      const { payload } = await jwtVerify(token, this.key);
      if (typeof payload.sub !== "string" || typeof payload.sid !== "string") return undefined;
      return { playerId: payload.sub, sessionId: payload.sid };
    } catch {
      return undefined; // bad signature, expired, or signed by another cluster's secret
    }
  }
}
