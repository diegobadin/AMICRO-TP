// Instrumented from its first real phase, per the program rule — P8 consolidates dashboards, it
// does not go back and retrofit counters. The SSE gauges arrive with the stream itself (F4); what
// exists here is what this phase can honestly report.

import { Counter, Gauge, Histogram, Registry, collectDefaultMetrics } from "prom-client";

export const registry = new Registry();
collectDefaultMetrics({ register: registry });

export const requests = new Counter({
  name: "gateway_requests_total",
  help: "Requests by route and status",
  labelNames: ["route", "status"],
  registers: [registry],
});

export const sseConnections = new Gauge({
  name: "gateway_sse_connections_active",
  help: "Live SSE connections held by this gateway",
  registers: [registry],
});

export const sseEventsDelivered = new Counter({
  name: "gateway_sse_events_delivered_total",
  help: "Event frames written to a subscriber",
  registers: [registry],
});

export const sessionsRevoked = new Counter({
  name: "gateway_sessions_revoked_total",
  help: "Sessions killed by identity and refused here from that moment on",
  registers: [registry],
});

export const requestDuration = new Histogram({
  name: "gateway_http_request_duration_seconds",
  help: "Request latency by route and status",
  labelNames: ["route", "status"],
  buckets: [0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5],
  registers: [registry],
});
