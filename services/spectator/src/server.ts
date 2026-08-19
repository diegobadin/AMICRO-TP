// Entrypoint: connect Redis and Kafka, start the projection, serve the streams. Emits one
// structured JSON log line per interesting event, carrying the correlationId where there is one —
// the observability seam the README documents (retrievable via `kubectl logs`).

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { Kafka } from "kafkajs";
import Redis from "ioredis";
import { Broker } from "./broker.js";
import * as consumerModule from "./consumer.js";
import * as metrics from "./metrics.js";
import { log } from "./metrics.js";
import { SERVICE, handle } from "./app.js";
import { Store } from "./store.js";
import { HEARTBEAT_MS, encodeFrame, heartbeatFrame, snapshotFrame, updateFrame } from "./sse.js";
import { emptyView } from "./view.js";

const PORT = Number(process.env.PORT ?? 8086);
const REDIS_URL = process.env.REDIS_URL ?? "redis://localhost:6379";
const BROKERS = (process.env.KAFKA_BROKERS ?? "localhost:9092").split(",");
const LAG_INTERVAL_MS = Number(process.env.LAG_INTERVAL_MS ?? 15_000);

// The projection's own connection. Its loop retries, so it must be told about a failure rather than
// have it parked in an offline queue — P4/F8's lesson, decided per connection and not per service.
const redis = new Redis(REDIS_URL, { enableOfflineQueue: false, maxRetriesPerRequest: null });
redis.on("error", (error: Error) => log("warn", "redis-error", { error: error.message }));

const store = new Store(redis);
const broker = new Broker();
const kafka = new Kafka({ clientId: SERVICE, brokers: BROKERS, logLevel: 1 });
const consumer = kafka.consumer({ groupId: consumerModule.GROUP_ID });
const admin = kafka.admin();

async function stream(
  roomId: string,
  spectatorId: string,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void> {
  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });
  // Node holds response headers until the first body byte, so a spectator watching a quiet room
  // would be left with an unresolved `fetch` on a connection that is open and working.
  res.flushHeaders();

  let closed = false;
  const unsubscribe = broker.subscribe(roomId, (update) => {
    res.write(encodeFrame(updateFrame(update)));
  });
  const heartbeat = setInterval(() => {
    void store
      .read(roomId)
      .then((view) => res.write(encodeFrame(heartbeatFrame(view))))
      .catch(() => undefined);
  }, HEARTBEAT_MS);

  const detach = async (): Promise<void> => {
    if (closed) return;
    closed = true;
    clearInterval(heartbeat);
    unsubscribe();
    metrics.activeStreams.dec();
    const remaining = await store.removeSpectator(roomId, spectatorId).catch(() => 0);
    log("info", "spectator-left", { roomId, spectatorId, spectators: remaining });
  };

  // Registered BEFORE the first await. A client that vanishes during the snapshot read would
  // otherwise never be detached: the room keeps a listener and the gauge drifts up forever.
  req.on("close", () => void detach());

  metrics.activeStreams.inc();
  metrics.streamsOpened.inc();

  try {
    await store.addSpectator(roomId, spectatorId);
    const view = (await store.read(roomId)) ?? emptyView(roomId);
    view.spectatorCount = await store.spectatorCount(roomId);
    if (!closed) res.write(encodeFrame(snapshotFrame(view)));
    log("info", "spectator-joined", { roomId, spectatorId, seq: view.lastSequence });
  } catch (error) {
    // Redis is down or the view is unreadable. Say so and close, rather than hold a connection that
    // will never carry a frame — a stream that is silently dead is the failure P4 spent a drill on.
    log("error", "snapshot-failed", { roomId, error: (error as Error).message });
    if (!closed) res.write(encodeFrame({ event: "unavailable", data: { roomId } }));
    await detach();
    res.end();
  }
}

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  const headers: Record<string, string | undefined> = {};
  for (const [key, value] of Object.entries(req.headers)) {
    headers[key.toLowerCase()] = Array.isArray(value) ? value[0] : value;
  }
  const action = handle(req.method ?? "GET", req.url ?? "/", headers);

  if (action.kind === "metrics") {
    void metrics.registry.metrics().then((body) => {
      res.writeHead(200, { "content-type": metrics.registry.contentType });
      res.end(body);
    });
    return;
  }

  if (action.kind === "reply") {
    // Not the kubelet's probes. They arrive every few seconds and drowned the one line that
    // mattered during the P6 drill — the consumer reporting that it had given up.
    const path = (req.url ?? "/").split("?")[0];
    if (path !== "/health") log("info", `${req.method} ${path}`, { status: action.reply.status });
    res.writeHead(action.reply.status, { "content-type": "application/json" });
    res.end(JSON.stringify(action.reply.json));
    return;
  }

  void stream(action.roomId, action.spectatorId, req, res);
});

async function main(): Promise<void> {
  server.listen(PORT, () => log("info", "listen", { port: PORT }));

  // The consumer retries FOREVER. A broker outage is a stale projection — a spectator problem,
  // never a gameplay one — so the process must not die; but it must also not give up, which is the
  // half the P6 drill caught missing. kafkajs exhausts its own retries during a cold start (Kafka
  // is still electing when this pod is already up), the promise rejected, and a `.catch` that only
  // logged left the service running perfectly healthy with no consumer at all for thirteen minutes.
  // `/health` said 200 the whole time, because the process really was alive.
  void (async () => {
    for (let attempt = 1; ; attempt++) {
      try {
        await consumerModule.start(consumer, store, broker);
        metrics.consumerStarts.inc();
        log("info", "consumer-running", { attempt });
        return;
      } catch (error) {
        metrics.consumerErrors.inc();
        const wait = Math.min(30_000, 2 ** Math.min(attempt, 5) * 1000);
        log("error", "consumer-retrying", { attempt, wait, error: (error as Error).message });
        await new Promise((resolve) => setTimeout(resolve, wait));
      }
    }
  })();

  await admin.connect().catch(() => undefined);
  setInterval(() => {
    void consumerModule.refreshLag(admin).catch((error: Error) => {
      log("warn", "lag-read-failed", { error: error.message });
    });
  }, LAG_INTERVAL_MS);
}

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    log("info", "shutdown", { signal });
    server.close();
    void consumer.disconnect().finally(() => process.exit(0));
  });
}

void main();
