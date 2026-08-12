# Multi-stage, non-root, minimal. Unlike the placeholder era this image has runtime dependencies —
# a Postgres driver — so it is installed into a venv in a build stage and only the venv is copied
# forward. No Kafka client: this service is the read side and consumes nothing.
# Config comes from the environment, never baked in (consigna §6.4).
FROM python:3.12-slim AS build
WORKDIR /app
COPY requirements.txt ./
RUN python -m venv /venv && /venv/bin/pip install --no-cache-dir -r requirements.txt

FROM python:3.12-slim AS runtime
WORKDIR /app
RUN useradd --create-home --uid 10001 appuser
COPY --from=build /venv /venv
COPY app.py server.py reader.py queries.py metrics.py ./
USER appuser
ENV PATH="/venv/bin:$PATH"
ENV PORT=8091
EXPOSE 8091
CMD ["python", "server.py"]
