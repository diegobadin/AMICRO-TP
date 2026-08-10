// HTTP wrapper around the route table. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).
//
// The SSE route is the next phase; everything else is live: authenticate here, forward there, and
// return exactly what the backend said.

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import Redis from "ioredis";
import { Outcome, resolve } from "./app.js";
import { Tokens } from "./auth.js";
import { SERVICE, fromEnv } from "./config.js";
import * as metrics from "./metrics.js";
import { forward } from "./proxy.js";
import { Revocations, listen } from "./revocations.js";

const config = fromEnv();
const tokens = new Tokens(config.jwtSecret);
const revocations = new Revocations(config.sessionTtlSeconds * 1000);

function log(action: string, status: number, correlationId: string, extra: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "info", service: SERVICE, action, status, correlationId, ...extra }) + "\n",
  );
}

// A connection of its own, and one that queues: a client in subscriber mode cannot run ordinary
// commands, and the subscribe itself has to survive being issued before the socket is up. The
// fail-fast, no-offline-queue setting identity uses is right for a cache on the request path and
// wrong here — it turns a cold start into a subscription that never happens.
const subscriber = new Redis(config.redisUrl, { maxRetriesPerRequest: null });
subscriber.on("error", () => undefined); // without a listener ioredis escalates to an uncaught exception
listen(
  subscriber,
  (event) => {
    revocations.revoke(event.oldSessionId);
    metrics.sessionsRevoked.inc();
    log("session-invalidated", 200, "-", { player: event.playerId, session: event.oldSessionId });
  },
  (e) => log("session-subscribe-failed", 0, "-", { error: String(e) }),
);

const playerOf = (outcome: Outcome) => (outcome.kind === "reply" ? undefined : outcome.principal?.playerId);

async function readBody(req: IncomingMessage): Promise<string | undefined> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  return chunks.length === 0 ? undefined : Buffer.concat(chunks).toString();
}

createServer(async (req: IncomingMessage, res: ServerResponse) => {
  const correlationId = (req.headers["x-correlation-id"] as string) ?? randomUUID();
  const url = (req.url ?? "/").split("?")[0];
  const method = req.method ?? "GET";

  if (method === "GET" && url === "/metrics") {
    res.writeHead(200, { "content-type": metrics.registry.contentType });
    res.end(await metrics.registry.metrics());
    return;
  }

  const done = metrics.requestDuration.startTimer();
  // The body is read before routing so a rejected request does not leave an unread socket behind.
  const body = method === "GET" || method === "HEAD" ? undefined : await readBody(req);
  const outcome = await resolve({ tokens, revoked: (sid) => revocations.has(sid) }, method, url, req.headers, correlationId);
  const route = outcome.route?.label ?? "unknown";

  let status: number;
  if (outcome.kind === "proxy") {
    const base = outcome.route.target === "identity" ? config.identityUrl : config.roomsUrl;
    const reply = await forward(base, method, url, outcome.headers, body);
    status = reply.status;
    res.writeHead(status, { ...reply.headers, "x-correlation-id": correlationId });
    res.end(reply.body);
  } else if (outcome.kind === "stream") {
    status = 501;
    res.writeHead(status, { "content-type": "application/json", "x-correlation-id": correlationId });
    res.end(JSON.stringify({ error: "not implemented yet" }));
  } else {
    status = outcome.reply.status;
    res.writeHead(status, { "content-type": "application/json", "x-correlation-id": correlationId });
    res.end(JSON.stringify(outcome.reply.json));
  }

  done({ route, status });
  metrics.requests.inc({ route, status });
  log(`${method} ${url}`, status, correlationId, { route, player: playerOf(outcome) });
}).listen(config.port, () => log("startup", 200, "boot", { port: config.port }));
