// HTTP wrapper around the route table. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).
//
// The proxy and the stream are the next two phases. Until they land, an authenticated and labelled
// request to a backend route answers 501: nothing points at the gateway yet — it is ClusterIP and
// the CLI still reaches identity and room-gameplay on their own NodePorts.

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { Outcome, resolve } from "./app.js";
import { Tokens } from "./auth.js";
import { SERVICE, fromEnv } from "./config.js";
import * as metrics from "./metrics.js";

const config = fromEnv();
const tokens = new Tokens(config.jwtSecret);

function log(action: string, status: number, correlationId: string, extra: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "info", service: SERVICE, action, status, correlationId, ...extra }) + "\n",
  );
}

const replyOf = (outcome: Outcome) =>
  outcome.kind === "reply" ? outcome.reply : { status: 501, json: { error: "not implemented yet" } };

const playerOf = (outcome: Outcome) => (outcome.kind === "reply" ? undefined : outcome.principal?.playerId);

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
  const outcome = await resolve(tokens, method, url, req.headers, correlationId);
  const reply = replyOf(outcome);
  const route = outcome.route?.label ?? "unknown";

  done({ route, status: reply.status });
  metrics.requests.inc({ route, status: reply.status });
  log(`${method} ${url}`, reply.status, correlationId, { route, player: playerOf(outcome) });

  res.writeHead(reply.status, { "content-type": "application/json", "x-correlation-id": correlationId });
  res.end(JSON.stringify(reply.json));
}).listen(config.port, () => log("startup", 200, "boot", { port: config.port }));
