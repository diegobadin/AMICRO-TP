"""The `room.lifecycle.events` consumer group.

Architecture §4.5 puts the Elo scope rules at the consumer's entry point, and `classify` is that
entry point written as a pure function so the rules can be tested without a broker.

The wire shape is P5's and is not re-derived here: `ce-id` is `{roomId}:{sequenceNumber}` and is the
dedup key; the body is `publicPayload(event)` with the room and sequence merged in, flat, with no
envelope to unwrap.
"""

from __future__ import annotations

import json
import time
from typing import Any

import metrics

TOPIC = "room.lifecycle.events"
GROUP_ID = "ranking-elo"

# The Kotlin enum name, which is what travels on the wire — not the catalog's `Casual`. The producer
# is the truth here (CHANGELOG-design.md §10.5) and a plausible-looking `"Casual"` would silently
# rank nothing at all.
CASUAL = "CASUAL"


def classify(event_type: str, body: dict[str, Any]) -> tuple[bool, str]:
    """The Elo scope rules of architecture §4.5. Returns (should_apply, reason)."""
    if event_type != "GameCompleted":
        return False, "not_game_completed"
    if body.get("roomType") != CASUAL:
        return False, "tournament"
    if body.get("isAbandoned"):
        return False, "abandoned"
    if len(body.get("finishingOrder") or []) < 2:
        return False, "too_few_players"
    return True, "casual"


def header_value(headers: list[tuple[str, bytes]] | None, name: str) -> str | None:
    for key, value in headers or []:
        if key == name:
            return value.decode("utf-8") if isinstance(value, bytes) else str(value)
    return None


# The CloudEvents `ce-type` header is a reverse-DNS URI the relay builds as
# `com.unoarena.room.<EventName>.v1` (outbox-relay/envelope.go). The BODY's `type` is the catalog's
# bare event name — the one `docs/design/04-commands-events.md` uses and the one the contract schema
# pins with `"const": "GameCompleted"`. Classify on the body and treat the header as metadata:
# comparing the URI against a bare name silently skips every event, which is what the P6 drill found
# after ranking read four lifecycle events and scored none of them.
def event_name(headers: list[tuple[str, bytes]] | None, body: dict[str, Any]) -> str:
    name = body.get("type")
    if isinstance(name, str) and name:
        return name
    qualified = header_value(headers, "ce-type") or ""
    parts = qualified.split(".")
    if len(parts) >= 2 and parts[-1].startswith("v"):
        return parts[-2]
    return qualified


def handle(message: Any, store: Any) -> str:
    """Decide and apply one message. Returns the outcome for logging and metrics."""
    body = json.loads(message.value())
    headers = message.headers()
    event_type = event_name(headers, body)
    fallback_key = f"{body.get('roomId')}:{body.get('sequenceNumber')}"
    event_key = header_value(headers, "ce-id") or fallback_key

    metrics.events_consumed.inc()
    should_apply, reason = classify(event_type, body)
    if reason == "not_game_completed":
        # Nothing is written for these, so there is nothing a redelivery could double. Recording
        # them would grow `consumed_events` by every room lifecycle event for no guarantee.
        metrics.events_skipped.labels(reason=reason).inc()
        return "ignored"

    outcome: str = store.consume_game_completed(event_key, body, should_apply)
    if outcome == "duplicate":
        metrics.events_deduped.inc()
    elif outcome == "skipped":
        metrics.events_skipped.labels(reason=reason).inc()
    else:
        metrics.elo_updates.inc()
    return outcome


def refresh_lag(consumer: Any) -> None:
    """Lag from the broker's high watermark, never from a cursor this process holds.

    A number a consumer derives from its own progress cannot report that it has stopped moving —
    P5's lesson about `outboxrelay_lag_seconds`, one layer over.
    """
    assignment = consumer.assignment()
    if not assignment:
        return
    total = 0
    for partition in consumer.position(assignment):
        if partition.offset is None or partition.offset < 0:
            continue
        _, high = consumer.get_watermark_offsets(partition, timeout=5.0, cached=False)
        total += max(0, high - partition.offset)
    metrics.consumer_lag.set(total)
    metrics.lag_reads.inc()


def run(consumer: Any, store: Any, should_run: Any, lag_interval: float = 15.0) -> None:
    """Poll, decide, commit. Backs off on failure and never exits on one (delta §10.11)."""
    consumer.subscribe([TOPIC])
    next_lag = 0.0
    while should_run():
        try:
            if time.monotonic() >= next_lag:
                # Isolated on purpose. Reading the lag is observability, and observability must not
                # be able to stop the thing it observes: in the P6 drill a mislabelled gauge threw
                # here on every iteration, before `poll` was ever reached, and the projections
                # stopped for 326 consecutive loops while the pod stayed Healthy.
                try:
                    refresh_lag(consumer)
                except Exception as error:  # noqa: BLE001
                    metrics.log_line("warn", "lag-read-failed", error=str(error))
                next_lag = time.monotonic() + lag_interval
            message = consumer.poll(1.0)
            if message is None:
                continue
            if message.error():
                metrics.consumer_errors.inc()
                metrics.log_line("warn", "poll-failed", error=str(message.error()))
                continue
            outcome = handle(message, store)
            # Committed only after the transaction landed, so a crash re-delivers rather than
            # skipping. The dedup insert is what makes that safe.
            consumer.commit(message=message, asynchronous=False)
            metrics.log_line("info", "consumed", outcome=outcome, offset=message.offset())
        except Exception as error:  # noqa: BLE001 — a consumer that dies on a bad row stops ranking
            metrics.consumer_errors.inc()
            metrics.log_line("error", "consume-failed", error=str(error))
            time.sleep(2.0)
