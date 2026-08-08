// HTTP wrapper around the pure handler. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import pg from "pg";
import { handle, Identity, SERVICE } from "./app.js";
import { createRedis, RedisCache } from "./cache-redis.js";
import { migrate } from "./migrate.js";
import { Sessions } from "./sessions.js";
import { PgStore } from "./store.js";

const PORT = Number(process.env.PORT ?? 8085);
const TOKEN_TTL_SECONDS = Number(process.env.IDENTITY_TOKEN_TTL_SECONDS ?? 3600);

const pool = new pg.Pool({
  host: process.env.DATABASE_HOST ?? "localhost",
  port: Number(process.env.DATABASE_PORT ?? 5432),
  user: process.env.DATABASE_USER ?? "identity",
  database: process.env.DATABASE_NAME ?? "identity",
  password: process.env.IDENTITY_DB_PASSWORD,
  max: 10,
});
const store = new PgStore(pool);
const redis = createRedis(process.env.REDIS_URL ?? "redis://localhost:6379");
const cache = new RedisCache(redis, () => log("cache-unavailable", 0, "-"));
const sessions = new Sessions(store, cache, process.env.IDENTITY_JWT_SECRET ?? "dev-secret", TOKEN_TTL_SECONDS);
const app = new Identity(store, sessions);

function log(action: string, status: number, correlationId: string, extra: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "info", service: SERVICE, action, status, correlationId, ...extra }) + "\n",
  );
}

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString());
  } catch {
    return {};
  }
}

// Migrate before listening. If Postgres is not there the process exits and the kubelet retries —
// a pod that never becomes ready is a far better failure than one answering against no schema.
try {
  await migrate(pool);
} catch (e) {
  process.stderr.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "error", service: SERVICE, action: "migrate", error: String(e) }) + "\n",
  );
  process.exit(1);
}

createServer(async (req: IncomingMessage, res: ServerResponse) => {
  const correlationId = (req.headers["x-correlation-id"] as string) ?? randomUUID();
  const url = (req.url ?? "/").split("?")[0];
  const body = req.method === "POST" ? await readBody(req) : {};
  const reply = await handle(app, req.method ?? "GET", url, req.headers as Record<string, string | undefined>, body);
  log(`${req.method} ${url}`, reply.status, correlationId, { user: body.user });
  res.writeHead(reply.status, { "content-type": "application/json", "x-correlation-id": correlationId });
  res.end(JSON.stringify(reply.json));
}).listen(PORT, () => log("startup", 200, "boot", { port: PORT }));
