// Against a real HTTP server, not a stubbed fetch: the claim under test is that P3's contract
// survives a network hop, and a fake would only prove that the code calls the fake.

import { createServer, IncomingMessage, Server, ServerResponse } from "node:http";
import { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { forward } from "../src/proxy.js";

let server: Server;
let base: string;
let seen: { method: string; url: string; headers: IncomingMessage["headers"]; body: string };

beforeAll(async () => {
  server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    seen = { method: req.method!, url: req.url!, headers: req.headers, body: Buffer.concat(chunks).toString() };

    if (req.url === "/rooms/r1/games/1" && req.headers["if-none-match"] === '"12"') {
      res.writeHead(304, { etag: '"12"' });
      res.end();
      return;
    }
    if (req.url === "/rooms/r1/games/1/moves" && req.headers["if-match"] === '"3"') {
      res.writeHead(412, { "content-type": "application/json", etag: '"7"' });
      res.end(JSON.stringify({ sequenceNumber: 7 }));
      return;
    }
    if (req.method === "POST" && req.url === "/rooms") {
      res.writeHead(201, { "content-type": "application/json", etag: '"1"', location: "/rooms/r1" });
      res.end(JSON.stringify({ roomId: "r1" }));
      return;
    }
    if (req.url === "/rooms/r1/players/p1") {
      res.writeHead(204);
      res.end();
      return;
    }
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true }));
  });
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(() => new Promise<void>((done) => server.close(() => done())));

describe("proxy", () => {
  it("relays the request headers the route policy allowed", async () => {
    await forward(base, "GET", "/rooms", { "x-player-id": "p1", "x-correlation-id": "c1" }, undefined);
    expect(seen.headers["x-player-id"]).toBe("p1");
    expect(seen.headers["x-correlation-id"]).toBe("c1");
  });

  it("carries a body on a POST and returns ETag and Location untouched", async () => {
    const reply = await forward(base, "POST", "/rooms", { "content-type": "application/json" }, '{"maxPlayers":4}');
    expect(seen.body).toBe('{"maxPlayers":4}');
    expect(reply.status).toBe(201);
    expect(reply.headers.etag).toBe('"1"');
    expect(reply.headers.location).toBe("/rooms/r1");
    expect(JSON.parse(reply.body)).toEqual({ roomId: "r1" });
  });

  it("passes a 304 through with no body", async () => {
    const reply = await forward(base, "GET", "/rooms/r1/games/1", { "if-none-match": '"12"' }, undefined);
    expect(reply.status).toBe(304);
    expect(reply.body).toBe("");
    expect(reply.headers.etag).toBe('"12"');
  });

  it("passes a 412 through with the reconcilable state the client needs", async () => {
    const reply = await forward(base, "POST", "/rooms/r1/games/1/moves", { "if-match": '"3"' }, "{}");
    expect(reply.status).toBe(412);
    expect(JSON.parse(reply.body)).toEqual({ sequenceNumber: 7 });
  });

  it("passes a 204 through with no body", async () => {
    const reply = await forward(base, "DELETE", "/rooms/r1/players/p1", {}, undefined);
    expect(reply.status).toBe(204);
    expect(reply.body).toBe("");
  });

  it("reports an unreachable backend as 502, not as the backend's own error", async () => {
    const reply = await forward("http://127.0.0.1:1", "GET", "/rooms", {}, undefined);
    expect(reply.status).toBe(502);
    expect(JSON.parse(reply.body).error).toBe("backend unavailable");
  });
});
