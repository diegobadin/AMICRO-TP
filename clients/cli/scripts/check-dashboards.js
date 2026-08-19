#!/usr/bin/env node
// P8: every panel query in gitops/platform/dashboards resolves, and every metric name it references
// actually exists in Prometheus.
//
// The name check is the point. Panels end their queries in `or vector(0)` so a freshly installed
// cluster reads 0 instead of "No data" — but that also makes a MISTYPED metric name render as a
// confident zero, which is indistinguishable from a healthy idle system. Three phases of this
// project have been bitten by that shape (a gauge never Set, a consumer that never started, a
// rating keyed on the wrong field). So the two questions are asked separately: does the query run,
// and is the series it names one Prometheus has ever seen.
//
//   PROM_URL=http://localhost:9091 node scripts/check-dashboards.js

import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const PROM = process.env.PROM_URL ?? "http://localhost:9091";
const DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "..", "..", "..", "gitops", "platform", "dashboards", "dashboards",
);

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
          console.log(`  UNKNOWN METRIC  ${panel.title}: ${name}`);
          failures += 1;
        }
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

console.log(`\n${queries} queries, ${referenced.size} distinct metric names, ${failures} problem(s)`);
if (failures > 0) process.exit(1);
