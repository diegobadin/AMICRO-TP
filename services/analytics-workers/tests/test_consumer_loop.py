"""A consumer that gives up is worse than one that crashes.

This thread is a daemon: an exception escaping `run` ends it silently while the HTTP server keeps
answering /health with 200. The P6 drill found exactly that in the Node sibling — thirteen minutes
Healthy with no consumer — and `subscribe` sitting outside the retry loop was the same bug waiting
here.
"""

from __future__ import annotations

import time
from typing import Any

import consumer


class FlakySubscribe:
    """Fails to subscribe twice, as a broker that is still electing would."""

    def __init__(self, failures: int = 2) -> None:
        self.attempts = 0
        self.failures = failures

    def subscribe(self, topics: Any) -> None:
        self.attempts += 1
        if self.attempts <= self.failures:
            raise RuntimeError("broker not ready")

    def assignment(self) -> list[Any]:
        return []

    def poll(self, timeout: float) -> None:
        return None


def test_the_loop_retries_a_failing_subscribe_instead_of_dying() -> None:
    kafka = FlakySubscribe()
    started = time.monotonic()
    consumer.run(kafka, None, lambda: time.monotonic() - started < 7.0)
    assert kafka.attempts >= 3, "the loop gave up on a subscribe that would have succeeded"


def test_a_running_consumer_subscribes_exactly_once() -> None:
    kafka = FlakySubscribe(failures=0)
    started = time.monotonic()
    consumer.run(kafka, None, lambda: time.monotonic() - started < 1.5)
    assert kafka.attempts == 1
