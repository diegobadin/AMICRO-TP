// HTTP wrapper around the pure handler. Emits one structured JSON log line per request carrying
// a correlationId — the observability seam the README documents (retrievable via `kubectl logs`).

import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";
import { handle, Identity, SERVICE } from "./app.js";

const PORT = Number(process.env.PORT ?? 8085);
const app = new Identity();

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

createServer(async (req: IncomingMessage, res: ServerResponse) => {
  const correlationId = (req.headers["x-correlation-id"] as string) ?? randomUUID();
  const url = (req.url ?? "/").split("?")[0];
  const body = req.method === "POST" ? await readBody(req) : {};
  const reply = handle(app, req.method ?? "GET", url, req.headers as Record<string, string | undefined>, body);
  log(`${req.method} ${url}`, reply.status, correlationId, { user: body.user });
  res.writeHead(reply.status, { "content-type": "application/json", "x-correlation-id": correlationId });
  res.end(JSON.stringify(reply.json));
}).listen(PORT, () => log("startup", 200, "boot", { port: PORT }));
