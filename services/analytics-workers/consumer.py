"""The `analytics-projections` consumer group, over both room topics.

A separate group from ranking's and spectator's, per architecture §7.2 — no competition for
partition offsets, and one context falling behind never slows another down. The dedup source is the
topic, so the same `ce-id` arriving on two topics could never collide (it cannot, but the key is
honest about what it identifies).
"""

from __future__ import annotations

import json
import time
from typing import Any

import metrics

PUBLIC_TOPIC = "room.public.events"
LIFECYCLE_TOPIC = "room.lifecycle.events"
# P7's bracket read model. The same consumer gains a topic rather than a new group: §7.2 forbids a
# *new consumer* joining an existing group, and this is the same consumer projecting more of the
# same system — one offset story, one transaction shape, one place a redelivery is recognised.
TOURNAMENT_TOPIC = "tournament.lifecycle.events"
TOPICS = [PUBLIC_TOPIC, LIFECYCLE_TOPIC, TOURNAMENT_TOPIC]
GROUP_ID = "analytics-projections"


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
    body = json.loads(message.value())
    headers = message.headers()
    topic = message.topic()
    event_type = event_name(headers, body)
    fallback_key = f"{body.get('roomId')}:{body.get('sequenceNumber')}"
    event_key = header_value(headers, "ce-id") or fallback_key

    metrics.events_consumed.inc()
    outcome: str = store.consume(topic, event_key, event_type, body)
    if outcome == "duplicate":
        metrics.events_deduped.inc()
    else:
        metrics.events_projected.labels(topic=topic).inc()
        metrics.projection_writes.inc()
    return outcome


def refresh_lag(consumer: Any) -> None:
    """Lag from the broker's high watermark, never from a cursor this process holds."""
    assignment = consumer.assignment()
    if not assignment:
        return
    per_topic: dict[str, int] = {topic: 0 for topic in TOPICS}
    for partition in consumer.position(assignment):
        if partition.offset is None or partition.offset < 0:
            continue
        _, high = consumer.get_watermark_offsets(partition, timeout=5.0, cached=False)
        per_topic[partition.topic] = per_topic.get(partition.topic, 0) + max(
            0, high - partition.offset
        )
    for topic, lag in per_topic.items():
        metrics.consumer_lag.labels(topic=topic).set(lag)
    metrics.lag_reads.inc()


def run(consumer: Any, store: Any, should_run: Any, lag_interval: float = 15.0) -> None:
    """Poll, project, commit. Backs off on failure and never exits on one (delta §10.11)."""
    # Subscribing is inside the loop, and retried, for the same reason spectator's start is: a
    # consumer that gives up is worse than one that crashes. This thread is a daemon, so an
    # exception escaping here would end it silently while the HTTP server kept answering /health
    # with 200 — the thirteen-minute failure the P6 drill found in the Node sibling, which would
    # look identical here.
    subscribed = False
    next_lag = 0.0
    while should_run():
        if not subscribed:
            try:
                consumer.subscribe(TOPICS)
                subscribed = True
                metrics.consumer_starts.inc()
                metrics.log_line("info", "consumer-running")
            except Exception as error:  # noqa: BLE001
                metrics.consumer_errors.inc()
                metrics.log_line("error", "subscribe-retrying", error=str(error))
                time.sleep(2.0)
                continue
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
            # The relay carries the originating request's correlation id onto every message
            # (outbox-relay/envelope.go), so one id can be followed from the player's command all
            # the way into the projections. Reading it here is what makes that true — the header
            # has travelled the whole spine since P5 with nobody consuming it.
            metrics.log_line(
                "info",
                "projected",
                outcome=outcome,
                offset=message.offset(),
                correlationId=header_value(message.headers(), "ce-correlationid") or "",
            )
        except Exception as error:  # noqa: BLE001 — one bad row must not stop the projections
            metrics.consumer_errors.inc()
            metrics.log_line("error", "project-failed", error=str(error))
            time.sleep(2.0)
