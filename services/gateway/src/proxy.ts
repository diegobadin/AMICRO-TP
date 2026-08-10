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
  fetchImpl: typeof fetch = fetch,
): Promise<ProxyReply> {
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl}${path}`, {
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
