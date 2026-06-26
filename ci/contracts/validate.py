#!/usr/bin/env python3
"""Illustrative async contract check for the GameCompleted event.

Demonstrates WHERE contract testing lives in the pipeline (consigna §6.3). It does two things:

1. Producer conformance — the sample event that room-gameplay (the producer placeholder) emits
   must validate against the published JSON Schema.
2. Backward compatibility — the fields the consumers (ranking, analytics-workers) depend on must
   still be required by the schema, so a producer-side schema change cannot silently drop a field
   a consumer reads.

A failure here blocks build of the affected consumers (cross-service fail-fast).
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

HERE = Path(__file__).parent
SCHEMA = json.loads((HERE / "game-completed.schema.json").read_text())

# Sample the PRODUCER placeholder emits (room-gameplay).
PRODUCER_SAMPLE = {
    "eventId": "11111111-1111-1111-1111-111111111111",
    "roomId": "room-1",
    "gameId": "game-1",
    "sequenceNumber": 42,
    "correlationId": "corr-1",
    "roomType": "Casual",
    "isAbandoned": False,
    "finishingOrder": ["alice", "bob"],
    "cardPointTotals": {"alice": 0, "bob": 17},
}

# Fields each CONSUMER placeholder reads (must remain required by the schema).
CONSUMER_REQUIRED = {
    "ranking": ["roomType", "isAbandoned", "finishingOrder", "cardPointTotals", "gameId"],
    "analytics-workers": ["eventId", "roomId", "gameId", "sequenceNumber"],
}


def main() -> int:
    errors = sorted(Draft202012Validator(SCHEMA).iter_errors(PRODUCER_SAMPLE), key=str)
    if errors:
        print("FAIL: producer sample does not conform to GameCompleted schema:")
        for e in errors:
            print(f"  - {e.message}")
        return 1
    print("OK: producer sample conforms to GameCompleted schema.")

    required = set(SCHEMA.get("required", []))
    ok = True
    for consumer, fields in CONSUMER_REQUIRED.items():
        missing = [f for f in fields if f not in required]
        if missing:
            ok = False
            print(f"FAIL: {consumer} depends on fields no longer required: {missing}")
        else:
            print(f"OK: {consumer} required fields are all present in the contract.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
