// HTTP wrapper around the pure handler. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { handle, SERVICE } from "./app.js";

const PORT = Number(process.env.PORT ?? 8080);

function log(action: string, status: number, correlationId: string, extra: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level: "info", service: SERVICE, action, status, correlationId, ...extra }) + "\n",
  );
}

createServer((req: IncomingMessage, res: ServerResponse) => {
  const correlationId = (req.headers["x-correlation-id"] as string) ?? randomUUID();
  const url = (req.url ?? "/").split("?")[0];
  const reply = handle(req.method ?? "GET", url);
  log(`${req.method} ${url}`, reply.status, correlationId);
  res.writeHead(reply.status, { "content-type": "application/json", "x-correlation-id": correlationId });
  res.end(JSON.stringify(reply.json));
}).listen(PORT, () => log("startup", 200, "boot", { port: PORT }));
