# Multi-stage, non-root, minimal. Unlike the placeholder era this image has runtime dependencies —
# a Kafka client and a Postgres driver — so they are installed into a venv in a build stage and only
# the venv is copied forward. `slim` and not `alpine`: confluent-kafka ships manylinux wheels that
# bundle librdkafka, and on musl there is no wheel to bundle.
# Config comes from the environment, never baked in (consigna §6.4).
FROM python:3.12-slim AS build
WORKDIR /app
COPY requirements.txt ./
RUN python -m venv /venv && /venv/bin/pip install --no-cache-dir -r requirements.txt

FROM python:3.12-slim AS runtime
WORKDIR /app
RUN useradd --create-home --uid 10001 appuser
COPY --from=build /venv /venv
COPY app.py server.py consumer.py store.py schema.py elo.py metrics.py ./
USER appuser
ENV PATH="/venv/bin:$PATH"
ENV PORT=8084
EXPOSE 8084
CMD ["python", "server.py"]
