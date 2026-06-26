// Gateway — placeholder service (canned responses only, no real logic).
//
// The HTTP handling is a pure function so unit tests exercise it without sockets, mirroring the
// identity slice's testable style. There is no auth or store here — this is a stub for the
// fully-wired pipeline to exercise (build -> deliver -> deploy-staging).

const SERVICE = "gateway";

export interface Reply {
  status: number;
  json: unknown;
}

// Pure request handler — testable without a server.
export function handle(method: string, path: string): Reply {
  if (method === "GET" && path === "/health") {
    return { status: 200, json: { status: "ok", service: SERVICE } };
  }
  if (method === "GET" && path === "/rooms") {
    return { status: 200, json: [] };
  }
  return { status: 404, json: { error: "not found" } };
}

export { SERVICE };
