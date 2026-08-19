#!/usr/bin/env node
// P8: every panel query in gitops/platform/dashboards resolves, and every metric name it references
// actually exists in Prometheus.
//
// The name check is the point. Panels end their queries in `or vector(0)` so a freshly installed
// cluster reads 0 instead of "No data" — but that also makes a MISTYPED metric name render as a
// confident zero, which is indistinguishable from a healthy idle system. Three phases of this
// project have been bitten by that shape (a gauge never Set, a consumer that never started, a
// rating keyed on the wrong field). So the questions are asked separately: does the query run, has
// Prometheus ever seen the series, and — when it has not — does any service actually declare it.
//
//   PROM_URL=http://localhost:9091 node scripts/check-dashboards.js

import { readdirSync, readFileSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const PROM = process.env.PROM_URL ?? "http://localhost:9091";
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const DIR = join(ROOT, "gitops", "platform", "dashboards", "dashboards");

// A metric whose name carries a tag has no series until the first thing it counts happens:
// `roomgameplay_engine_rejections_total` does not exist until somebody plays an illegal card, and on
// a freshly installed cluster that is true of most of them. Prometheus cannot tell that apart from a
// typo — both are "no such series" — so the name is checked against the source that declares it
// instead. Unknown to Prometheus *and* undeclared in any service is a typo and fails; unknown to
// Prometheus but declared is reported and allowed.
function declaredNames() {
  const blob = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      if (entry === "node_modules" || entry === "build" || entry === "dist" || entry === ".gradle") continue;
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (/\.(kt|py|ts|go)$/.test(entry)) blob.push(readFileSync(path, "utf8"));
    }
  };
  walk(join(ROOT, "services"));
  return blob.join("\n");
}

const SOURCES = declaredNames();

function isDeclared(metric) {
  const stem = metric.replace(/_(total|bucket|count|sum|created)$/, "");
  // Micrometer declares `roomgameplay.engine.rejections`; prom-client and prometheus_client use the
  // underscore form directly.
  return SOURCES.includes(stem) || SOURCES.includes(stem.replace(/_/g, "."));
}

// A metric name in PromQL: lowercase word with at least one underscore. Function names are filtered
// out rather than parsed — this is a lint, not a PromQL implementation.
const FUNCTIONS = new Set(["or", "and", "unless", "by", "without", "le", "vector", "rate", "irate",
  "sum", "avg", "min", "max", "count", "increase", "histogram_quantile", "clamp_min", "clamp_max"]);

async function prom(path, params) {
  const url = new URL(join("/api/v1", path), PROM);
  for (const [k, v] of Object.entries(params ?? {})) url.searchParams.set(k, v);
  const res = await fetch(url, { signal: AbortSignal.timeout(15_000) });
  if (!res.ok) throw new Error(`${url.pathname} -> HTTP ${res.status}`);
  return res.json();
}

const known = new Set((await prom("/label/__name__/values")).data);
let failures = 0;
let queries = 0;
const referenced = new Set();
const lazy = new Set();

for (const file of readdirSync(DIR).filter((f) => f.endsWith(".json"))) {
  const board = JSON.parse(readFileSync(join(DIR, file), "utf8"));
  console.log(`\n### ${board.title}  (${file})`);
  for (const panel of board.panels ?? []) {
    for (const target of panel.targets ?? []) {
      const expr = target.expr;
      queries += 1;

      for (const name of expr.match(/\b[a-z][a-z0-9]*_[a-z0-9_]+\b/g) ?? []) {
        if (FUNCTIONS.has(name)) continue;
        referenced.add(name);
        if (!known.has(name)) {
          if (isDeclared(name)) {
            if (!lazy.has(name)) {
              console.log(`  not yet emitted  ${name} (declared in a service, tagged — no series until first use)`);
              lazy.add(name);
            }
          } else {
            console.log(`  UNKNOWN METRIC  ${panel.title}: ${name} — declared nowhere in services/`);
            failures += 1;
          }
        }
      }

      // The P7 handoff's standing requirement: two relay Deployments publish to different topics
      // from one image, so a summed panel hides one of them going idle. Enforced rather than
      // remembered — this is the kind of rule that survives exactly as long as the person who
      // wrote it is the one editing the board.
      if (expr.includes("outboxrelay_") && !/by\s*\(\s*job\b/.test(expr)) {
        console.log(`  RELAY NOT SPLIT ${panel.title}: ${expr}`);
        failures += 1;
      }

      const result = await prom("/query", { query: expr });
      if (result.status !== "success") {
        console.log(`  QUERY FAILED    ${panel.title}: ${expr}`);
        failures += 1;
      } else if (result.data.result.length === 0) {
        // With `or vector(0)` an empty result means the expression itself is malformed, not that
        // the system is idle.
        console.log(`  EMPTY RESULT    ${panel.title}: ${expr}`);
        failures += 1;
      }
    }
  }
  console.log(`  ${board.panels.length} panels checked`);
}

console.log(`\n${queries} queries, ${referenced.size} distinct metric names, ${lazy.size} not yet emitted, ${failures} problem(s)`);
if (failures > 0) process.exit(1);
