// Instrumented from its first real phase, per the program rule. Every gauge here ships with a
// counter beside it: a gauge that was never `Set` reads 0, and 0 is the healthy value for both of
// these — the same lie P4 found in a Redis outage and P5 found twice in the relay's backlog.

import { Counter, Gauge, Registry, collectDefaultMetrics } from "prom-client";
import { SERVICE } from "./app.js";

// The structured log line lives here rather than in server.ts because the consumer needs it too,
// and server.ts imports the consumer — the other direction would be a cycle. Same home as the two
// Python consumers' `log_line`, for the same reason.
export function log(level: string, action: string, fields: Record<string, unknown> = {}) {
  process.stdout.write(
    JSON.stringify({ ts: new Date().toISOString(), level, service: SERVICE, action, ...fields }) + "\n",
  );
}

export const registry = new Registry();
collectDefaultMetrics({ register: registry });

export const eventsProjected = new Counter({
  name: "spectator_events_projected_total",
  help: "Events applied to a room view, by topic",
  labelNames: ["topic"],
  registers: [registry],
});

export const eventsDeduped = new Counter({
  name: "spectator_events_deduped_total",
  help: "Redeliveries recognised by sequence number and dropped",
  registers: [registry],
});

// Expected to stay at zero forever: the seed is stripped by `publicPayload` in the same transaction
// that writes the event. If this ever moves, the privacy filter upstream has stopped working and
// this is the alert, not a nuisance counter.
export const privateFieldRejections = new Counter({
  name: "spectator_private_field_rejections_total",
  help: "Events refused because they carried a field a spectator may never see",
  registers: [registry],
});

export const activeStreams = new Gauge({
  name: "spectator_active_streams",
  help: "SSE connections currently held",
  registers: [registry],
});

export const streamsOpened = new Counter({
  name: "spectator_streams_opened_total",
  help: "Streams opened, so an idle gauge is distinguishable from one that never served anyone",
  registers: [registry],
});

// Whether the consume loop is actually running. A projection counter alone cannot tell "no games
// have been played" from "no consumer has ever started" — the P6 drill spent its diagnosis on
// exactly that ambiguity.
export const consumerStarts = new Counter({
  name: "spectator_consumer_starts_total",
  help: "Times the consume loop reached a running state",
  registers: [registry],
});

export const eventsMalformed = new Counter({
  name: "spectator_events_malformed_total",
  help: "Messages without the roomId/sequenceNumber a projection needs",
  registers: [registry],
});

export const consumerErrors = new Counter({
  name: "spectator_consumer_errors_total",
  help: "Consume attempts that failed and will be retried",
  registers: [registry],
});

export const consumerLag = new Gauge({
  name: "spectator_consumer_lag",
  help: "Messages between this consumer group and the broker's high watermark",
  labelNames: ["topic"],
  registers: [registry],
});

export const lagReads = new Counter({
  name: "spectator_lag_reads_total",
  help: "Lag queries that succeeded, so an unset gauge is distinguishable from a healthy one",
  registers: [registry],
});
