// Instrumented from its first real phase, per the program rule — P8 consolidates dashboards, it
// does not go back and retrofit counters. The first three are the business metrics P8 can build
// on (registered users, login rate, session supersessions); the last two are how a degraded
// dependency becomes visible instead of silent.

import { Counter, Histogram, Registry, collectDefaultMetrics } from "prom-client";

export const registry = new Registry();
collectDefaultMetrics({ register: registry });

export const registrations = new Counter({
  name: "identity_registrations_total",
  help: "Accounts created",
  registers: [registry],
});

export const logins = new Counter({
  name: "identity_logins_total",
  help: "Login attempts by outcome",
  labelNames: ["result"],
  registers: [registry],
});

export const supersessions = new Counter({
  name: "identity_sessions_superseded_total",
  help: "Sessions killed by a newer login for the same player",
  registers: [registry],
});

export const publishFailures = new Counter({
  name: "identity_session_event_publish_failures_total",
  help: "SessionInvalidated publishes that did not reach a transport",
  labelNames: ["transport"],
  registers: [registry],
});

export const requestDuration = new Histogram({
  name: "identity_http_request_duration_seconds",
  help: "Request latency by route and status",
  labelNames: ["route", "status"],
  buckets: [0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5],
  registers: [registry],
});
