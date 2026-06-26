# Multi-stage, non-root, minimal. Stdlib-only runtime — no pip install, no deps.
# Config (PORT) comes from the environment, never baked in (consigna §6.4).
FROM python:3.12-slim AS runtime
WORKDIR /app
RUN useradd --create-home --uid 10001 appuser
COPY app.py server.py ./
USER appuser
ENV PORT=8087
EXPOSE 8087
CMD ["python", "server.py"]
