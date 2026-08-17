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
# Named explicitly rather than `COPY . .` so a stray test file or a virtualenv cannot ride into
# the image. The cost is that a NEW module has to be added here too — `placement.py` (P7) was not,
# and the pod crash-looped on `ModuleNotFoundError` with every test on disk passing, because the
# tests import from a directory and the container imports from an image.
COPY app.py server.py consumer.py store.py schema.py elo.py placement.py metrics.py ./
USER appuser
ENV PATH="/venv/bin:$PATH"
ENV PORT=8084
EXPOSE 8084
CMD ["python", "server.py"]
