"""Prometheus surface and the structured log line for the read side."""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from typing import Any

from prometheus_client import Counter

SERVICE = "analytics-api"

reads = Counter(
    "analyticsapi_reads_total",
    "Stats reads served, by surface and status",
    ["surface", "status"],
)

read_failures = Counter(
    "analyticsapi_read_failures_total",
    "Reads that could not reach the projections",
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
