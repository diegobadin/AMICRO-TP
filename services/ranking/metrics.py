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

SERVICE = "ranking"

events_consumed = Counter(
    "ranking_events_consumed_total",
    "Lifecycle events read off the topic and decided on",
)

events_skipped = Counter(
    "ranking_events_skipped_total",
    "Events deliberately not scored, by reason",
    ["reason"],
)

elo_updates = Counter(
    "ranking_elo_updates_total",
    "Games that moved at least one rating",
)

events_deduped = Counter(
    "ranking_events_deduped_total",
    "Redeliveries recognised by ce-id and dropped",
)

# Whether the consume loop ever reached a running state. A projection counter alone cannot tell
# "nobody has played a game yet" from "no consumer has ever started" — the ambiguity the P6 drill
# spent its diagnosis on.
consumer_starts = Counter(
    "ranking_consumer_starts_total",
    "Times the consume loop successfully subscribed",
)

consumer_errors = Counter(
    "ranking_consumer_errors_total",
    "Poll or apply attempts that failed and will be retried",
)

consumer_lag = Gauge(
    "ranking_consumer_lag",
    "Messages between this consumer group and the broker's high watermark",
)

lag_reads = Counter(
    "ranking_lag_reads_total",
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
