// Pass-through, not translation. The gateway relays the method, the path, the body and the headers
// the route policy allowed, and returns the backend's status, body and response headers unchanged.
//
// P3's whole concurrency contract — `ETag`, `If-Match` → `412`, `If-None-Match` → `304`,
// `Idempotency-Key` replays, `428`, `409` — crosses this function on every request. A gateway that
// "helps" by rewriting a status or re-encoding a body is how that contract rots, so the only
// decisions taken here are which response headers to carry and what to do when the backend is down.

export interface ProxyReply {
  status: number;
  headers: Record<string, string>;
  body: string;
}

// Response headers that carry meaning to the client. `ETag` is the client's next `If-Match`, and
// `Location` is where `POST /rooms` says the room now lives.
const RELAYED = ["content-type", "etag", "location"];

export async function forward(
  baseUrl: string,
  method: string,
  path: string,
  headers: Record<string, string>,
  body: string | undefined,
): Promise<ProxyReply> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: method === "GET" || method === "HEAD" ? undefined : body,
    });
  } catch (e) {
    // The backend being unreachable is the gateway's own failure to report, not a 500 pretending
    // to come from a service that never saw the request.
    return { status: 502, headers: { "content-type": "application/json" }, body: JSON.stringify({ error: "backend unavailable", detail: String(e) }) };
  }

  const out: Record<string, string> = {};
  for (const name of RELAYED) {
    const value = response.headers.get(name);
    if (value !== null) out[name] = value;
  }
  // 304 and 204 carry no body by definition; reading one would hang on an empty stream.
  const text = response.status === 304 || response.status === 204 ? "" : await response.text();
  return { status: response.status, headers: out, body: text };
}

export interface StreamSink {
  write(chunk: string): void;
  end(): void;
}

/**
 * Relay an upstream SSE response chunk by chunk. Used only for `/rooms/:id/spectate`, where the
 * spectator service owns the projection and the gateway is a pipe.
 *
 * This is NOT the player stream: that one is served here, from Redis, by `sse.ts`. The two are kept
 * apart because their guards differ — the player's feed detects gaps and resyncs against a log,
 * while a spectator's frames each carry the whole view and there is nothing to reconstruct.
 *
 * `signal` is the client's disconnect. Without it a spectator who closes the tab leaves the gateway
 * holding an upstream connection that the spectator service still counts.
 */
export async function forwardStream(
  baseUrl: string,
  path: string,
  headers: Record<string, string>,
  sink: StreamSink,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { ...headers, accept: "text/event-stream" },
    signal,
  });
  if (!response.ok || !response.body) {
    sink.end();
    return;
  }
  const decoder = new TextDecoder();
  for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
    if (signal.aborted) break;
    sink.write(decoder.decode(chunk, { stream: true }));
  }
  sink.end();
}
