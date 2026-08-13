"""Prometheus surface and the structured log line.

Every gauge here ships with a success counter beside it. A gauge that was never `Set` reads `0`,
which for lag is the *healthiest* possible number and indistinguishable from a consumer that has
never once managed to ask the broker anything — the failure P4 found in Redis, P5 found in the
relay's backlog, and there is no reason for it to be new here.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from typing import Any

from prometheus_client import Counter, Gauge

SERVICE = "analytics-workers"

events_consumed = Counter(
    "analytics_events_consumed_total",
    "Events read off both room topics and decided on",
)

events_projected = Counter(
    "analytics_events_projected_total",
    "Events applied to a read model, by topic",
    ["topic"],
)

projection_writes = Counter(
    "analytics_projection_writes_total",
    "Transactions that committed at least one projection write",
)

events_deduped = Counter(
    "analytics_events_deduped_total",
    "Redeliveries recognised by ce-id and dropped",
)

consumer_errors = Counter(
    "analytics_consumer_errors_total",
    "Poll or apply attempts that failed and will be retried",
)

consumer_lag = Gauge(
    "analytics_consumer_lag",
    "Messages between this consumer group and the broker's high watermark, by topic",
    ["topic"],
)

lag_reads = Counter(
    "analytics_lag_reads_total",
    "Lag queries that succeeded, so an unset gauge is distinguishable from a healthy one",
)


def log_line(level: str, action: str, **fields: Any) -> None:
    entry: dict[str, Any] = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "level": level,
        "service": SERVICE,
        "action": action,
    }
    entry.update(fields)
    print(json.dumps(entry), flush=True, file=sys.stdout)
