// The gateway's route table and the policy attached to each route: where a request goes, whether
// it needs a session, and exactly which headers cross the trust boundary with it.
//
// Everything here is a pure function of (method, path, headers), so routing, authentication and
// header injection are unit-tested without a socket — the same shape identity's `handle` has.
// Only the SSE route needs the raw response, and it lives elsewhere for that reason.

import { Principal, Tokens } from "./auth.js";
import { SERVICE } from "./config.js";

export type Target = "identity" | "room-gameplay" | "gateway";

export interface Route {
  target: Target;
  /** Bounded metric label. The raw URL would let anyone grow the cardinality by inventing paths. */
  label: string;
  auth: boolean;
  stream: boolean;
}

export interface Reply {
  status: number;
  json: unknown;
}

interface Entry {
  methods: string[];
  pattern: RegExp;
  route: Route;
}

const identity = (label: string, auth: boolean): Route => ({ target: "identity", label, auth, stream: false });
const rooms = (label: string): Route => ({ target: "room-gameplay", label, auth: true, stream: false });

// `[^/]+` rather than a uuid pattern: the id format is room-gameplay's business, and a gateway that
// disagrees with it would reject requests the service would have accepted.
const TABLE: Entry[] = [
  { methods: ["GET"], pattern: /^\/health$/, route: { target: "gateway", label: "/health", auth: false, stream: false } },
  { methods: ["POST"], pattern: /^\/auth\/register$/, route: identity("/auth/register", false) },
  { methods: ["POST"], pattern: /^\/auth\/login$/, route: identity("/auth/login", false) },
  { methods: ["POST"], pattern: /^\/auth\/logout$/, route: identity("/auth/logout", true) },
  { methods: ["GET"], pattern: /^\/auth\/whoami$/, route: identity("/auth/whoami", true) },
  { methods: ["GET", "POST"], pattern: /^\/rooms$/, route: rooms("/rooms") },
  { methods: ["GET"], pattern: /^\/rooms\/[^/]+$/, route: rooms("/rooms/:id") },
  // PATCH is the reconnect (P5 E8). It was missing until then, which made `PlayerReconnected` an
  // event nothing outside the cluster could cause: the 60-second window could only ever expire.
  { methods: ["POST", "DELETE", "PATCH"], pattern: /^\/rooms\/[^/]+\/players\/[^/]+$/, route: rooms("/rooms/:id/players/:playerId") },
  { methods: ["POST"], pattern: /^\/rooms\/[^/]+\/games$/, route: rooms("/rooms/:id/games") },
  { methods: ["GET"], pattern: /^\/rooms\/[^/]+\/games\/[^/]+$/, route: rooms("/rooms/:id/games/:n") },
  { methods: ["POST"], pattern: /^\/rooms\/[^/]+\/games\/[^/]+\/moves$/, route: rooms("/rooms/:id/games/:n/moves") },
  {
    methods: ["GET"],
    pattern: /^\/rooms\/[^/]+\/stream$/,
    route: { target: "gateway", label: "/rooms/:id/stream", auth: true, stream: true },
  },
];

// A path that matches but a verb that does not is a 404 here, where the backend would have said
// 405. The gateway genuinely has no such route, and forwarding arbitrary verbs to a service that
// now trusts whatever the gateway sends is not a trade worth making for a nicer status code.
export function route(method: string, path: string): Route | undefined {
  return TABLE.find((e) => e.pattern.test(path) && e.methods.includes(method))?.route;
}

// Request headers that mean something downstream. Everything else is dropped: this is a security
// control, not tidiness. Once room-gameplay trusts `X-Player-Id`, a client-supplied one that
// reached it would be an authentication bypass, so the map below is built from scratch on every
// request rather than by editing the incoming one.
const FORWARDED = ["content-type", "if-match", "if-none-match", "idempotency-key"];

/** What room-gameplay trusts instead of a token. Named here so there is one place that knows. */
export const PLAYER_HEADER = "x-player-id";
export const SESSION_HEADER = "x-session-id";
export const CORRELATION_HEADER = "x-correlation-id";

export function downstreamHeaders(
  route: Route,
  incoming: Record<string, string | string[] | undefined>,
  correlationId: string,
  principal?: Principal,
): Record<string, string> {
  const out: Record<string, string> = { [CORRELATION_HEADER]: correlationId };
  for (const name of FORWARDED) {
    const value = incoming[name];
    if (typeof value === "string") out[name] = value;
  }
  if (route.target === "identity") {
    // identity owns sessions and resolves them itself, so its own token has to reach it — the
    // gateway validating first is a fast rejection, not a replacement for that check.
    const authorization = incoming["authorization"];
    if (typeof authorization === "string") out["authorization"] = authorization;
  }
  if (route.target === "room-gameplay" && principal) {
    out[PLAYER_HEADER] = principal.playerId;
    out[SESSION_HEADER] = principal.sessionId;
  }
  return out;
}

export type Outcome =
  | { kind: "reply"; route?: Route; reply: Reply }
  | { kind: "proxy"; route: Route; headers: Record<string, string>; principal?: Principal }
  | { kind: "stream"; route: Route; principal: Principal };

export interface Deps {
  tokens: Tokens;
  /** True once identity has said this session is dead — see revocations.ts. */
  revoked: (sessionId: string) => boolean;
}

export async function resolve(
  deps: Deps,
  method: string,
  path: string,
  headers: Record<string, string | string[] | undefined>,
  correlationId: string,
): Promise<Outcome> {
  const matched = route(method, path);
  if (!matched) return { kind: "reply", reply: { status: 404, json: { error: "not found" } } };

  if (matched.target === "gateway" && !matched.stream) {
    return { kind: "reply", route: matched, reply: { status: 200, json: { status: "ok", service: SERVICE } } };
  }

  const principal = matched.auth ? await deps.tokens.verify(headers["authorization"] as string | undefined) : undefined;
  if (matched.auth && !principal) {
    return { kind: "reply", route: matched, reply: { status: 401, json: { error: "unauthorized" } } };
  }
  // A token that is still signed and unexpired but whose session identity has killed. Named
  // distinctly so the CLI can print §5.A's `session_superseded` instead of "your login is wrong".
  if (principal && deps.revoked(principal.sessionId)) {
    return { kind: "reply", route: matched, reply: { status: 401, json: { error: "session_superseded" } } };
  }

  if (matched.stream) return { kind: "stream", route: matched, principal: principal! };
  return { kind: "proxy", route: matched, headers: downstreamHeaders(matched, headers, correlationId, principal), principal };
}
