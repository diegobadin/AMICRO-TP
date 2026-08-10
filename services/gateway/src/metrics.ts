// Instrumented from its first real phase, per the program rule — P8 consolidates dashboards, it
// does not go back and retrofit counters. The SSE gauges arrive with the stream itself (F4); what
// exists here is what this phase can honestly report.

import { Counter, Histogram, Registry, collectDefaultMetrics } from "prom-client";

export const registry = new Registry();
collectDefaultMetrics({ register: registry });

export const requests = new Counter({
  name: "gateway_requests_total",
  help: "Requests by route and status",
  labelNames: ["route", "status"],
  registers: [registry],
});

export const requestDuration = new Histogram({
  name: "gateway_http_request_duration_seconds",
  help: "Request latency by route and status",
  labelNames: ["route", "status"],
  buckets: [0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5],
  registers: [registry],
});
