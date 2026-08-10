// Everything the gateway needs to run comes from the environment, never from a baked-in default
// that would differ between a laptop and the cluster (consigna §6.4). The defaults are the
// local-development ones; the staging overlay sets the real values.

export const SERVICE = "gateway";

export interface Config {
  port: number;
  identityUrl: string;
  roomsUrl: string;
  jwtSecret: string;
}

export function fromEnv(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    port: Number(env.PORT ?? 8080),
    identityUrl: (env.IDENTITY_URL ?? "http://localhost:8085").replace(/\/$/, ""),
    roomsUrl: (env.ROOM_GAMEPLAY_URL ?? "http://localhost:8081").replace(/\/$/, ""),
    // The key identity signs with. Symmetric, so the gateway is the one verifier and identity is
    // unchanged; RS256 + JWKS is the upgrade when there is a second one (CHANGELOG-design.md).
    jwtSecret: env.IDENTITY_JWT_SECRET ?? "dev-secret",
  };
}
