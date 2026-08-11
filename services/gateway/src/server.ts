// HTTP wrapper around the route table. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).
//
// Three kinds of request: answered here (`/health`, `/metrics`, a rejection), forwarded to a
// backend, or held open as an SSE stream. Only the third one touches the raw response.

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import Redis from "ioredis";
import { CORRELATION_HEADER, Outcome, PLAYER_HEADER, SESSION_HEADER, resolve } from "./app.js";
import { Principal, Tokens } from "./auth.js";
import { SERVICE, fromEnv } from "./config.js";
import * as metrics from "./metrics.js";
import { forward } from "./proxy.js";
import { Revocations, listen } from "./revocations.js";
import { RoomStreams } from "./sse.js";

const config = fromEnv();
const tokens = new Tokens(config.jwtSecret);
const revocations = new Revocations(config.sessionTtlSeconds * 1000);

function log(action: string, status: number, correlationId: string, extra: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "info", service: SERVICE, action, status, correlationId, ...extra }) + "\n",
  );
}

// Three connections, because they cannot share one: a client in subscriber mode takes no ordinary
// commands, and a blocking XREAD holds its connection for the length of the block. The subscriber
// queues while it connects — issuing a `psubscribe` before the socket is up would leave a
// subscription that silently never existed.
// The tail must NOT queue: its loop is already a retry loop, and a read parked in the offline
// queue is a Redis outage nobody can see. Failing the read is what lets the loop tell the clients
// (found by pulling Redis out from under a live stream — the connection stayed open and the
// heartbeat went on reporting a sequence number that had stopped moving).
const subscriber = new Redis(config.redisUrl, { maxRetriesPerRequest: null });
const tail = new Redis(config.redisUrl, { maxRetriesPerRequest: 1, enableOfflineQueue: false });
const redis = new Redis(config.redisUrl, { maxRetriesPerRequest: 1, enableOfflineQueue: false });
for (const connection of [subscriber, tail, redis]) connection.on("error", () => undefined);

const streams = new RoomStreams(redis, tail, {
  delivered: (count) => metrics.sseEventsDelivered.inc(count),
  connections: (count) => metrics.sseConnections.set(count),
  log: (action, fields) => log(action, 0, "-", fields),
});

listen(
  subscriber,
  (event) => {
    revocations.revoke(event.oldSessionId);
    metrics.sessionsRevoked.inc();
    const closed = streams.kill(event.oldSessionId);
    log("session-invalidated", 200, "-", { player: event.playerId, session: event.oldSessionId, streamsClosed: closed });
  },
  (e) => log("session-subscribe-failed", 0, "-", { error: String(e) }),
);

const playerOf = (outcome: Outcome) => (outcome.kind === "reply" ? undefined : outcome.principal?.playerId);

async function readBody(req: IncomingMessage): Promise<string | undefined> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  return chunks.length === 0 ? undefined : Buffer.concat(chunks).toString();
}

/** The baseline the client read before connecting (D15), from the header or the query string. */
function lastEventId(req: IncomingMessage, query: string): number | undefined {
  const header = req.headers["last-event-id"];
  const raw = typeof header === "string" ? header : new URLSearchParams(query).get("lastEventId");
  const parsed = Number(raw);
  return raw !== null && raw !== undefined && raw !== "" && Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Only a member of the room may watch it (Architecture §1.6). room-gameplay already knows who is
 * seated, so the check is its own answer rather than a second copy of the membership rule here.
 */
async function isMember(roomId: string, principal: Principal, correlationId: string): Promise<boolean> {
  const headers = {
    [PLAYER_HEADER]: principal.playerId,
    [SESSION_HEADER]: principal.sessionId,
    [CORRELATION_HEADER]: correlationId,
  };
  const room = await forward(config.roomsUrl, "GET", `/rooms/${roomId}`, headers, undefined);
  if (room.status !== 200) return false;
  try {
    const players = (JSON.parse(room.body) as { players?: { playerId: string }[] }).players ?? [];
    return players.some((p) => p.playerId === principal.playerId);
  } catch {
    return false;
  }
}

createServer(async (req: IncomingMessage, res: ServerResponse) => {
  const correlationId = (req.headers["x-correlation-id"] as string) ?? randomUUID();
  const [url, query = ""] = (req.url ?? "/").split("?");
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

  // For a stream this measures the time to the first byte, not the life of the connection: a
  // request histogram with twenty-minute samples in it says nothing about latency.
  const finish = (status: number) => {
    done({ route, status });
    metrics.requests.inc({ route, status });
    log(`${method} ${url}`, status, correlationId, { route, player: playerOf(outcome) });
  };

  const reply = (status: number, json: unknown) => {
    res.writeHead(status, { "content-type": "application/json", [CORRELATION_HEADER]: correlationId });
    res.end(JSON.stringify(json));
    finish(status);
  };

  if (outcome.kind === "proxy") {
    const base = outcome.route.target === "identity" ? config.identityUrl : config.roomsUrl;
    const backend = await forward(base, method, url, outcome.headers, body);
    res.writeHead(backend.status, { ...backend.headers, [CORRELATION_HEADER]: correlationId });
    res.end(backend.body);
    finish(backend.status);
    return;
  }

  if (outcome.kind === "stream") {
    const roomId = url.split("/")[2];
    if (!(await isMember(roomId, outcome.principal, correlationId))) {
      reply(403, { error: "not_a_member" });
      return;
    }

    res.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
      // Nothing between the pod and the client buffers today, but an SSE stream that gets buffered
      // looks exactly like a broken game, and the header costs nothing.
      "x-accel-buffering": "no",
      [CORRELATION_HEADER]: correlationId,
    });
    // Node holds headers back until the first body byte, and a subscriber with nothing to replay
    // writes nothing until the first event — which would leave the client's `fetch` unresolved,
    // waiting on a connection that is in fact open and working.
    res.flushHeaders();
    finish(200);

    // The client can vanish while the replay is still being read out of Redis, and a subscriber
    // nobody detaches keeps the room tailing and the connection gauge wrong.
    let detach: (() => void) | undefined;
    let gone = false;
    req.on("close", () => {
      gone = true;
      detach?.();
    });
    try {
      detach = await streams.subscribe(
        roomId,
        outcome.principal.sessionId,
        { write: (chunk) => res.write(chunk), end: () => res.end() },
        lastEventId(req, query),
      );
      if (gone) detach();
    } catch (e) {
      // Redis is unreachable. Ending the stream sends the client back through its reconnect loop,
      // which is a far better answer than an open connection that will never carry an event.
      log("stream-subscribe-failed", 0, correlationId, { room: roomId, error: String(e) });
      res.end();
    }
    return;
  }

  reply(outcome.reply.status, outcome.reply.json);
}).listen(config.port, () => log("startup", 200, "boot", { port: config.port }));
