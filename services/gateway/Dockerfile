# Multi-stage, non-root, minimal base. Builds an artifact that runs unchanged in any environment;
# config (PORT, secrets, URLs) comes from the environment, never baked in (consigna §6.4).
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --no-audit --no-fund
COPY src ./src
RUN npm run build && npm prune --omit=dev

# Mirrored base (ci/mirror-bases.yml), pinned by digest: gcr.io rejected these runners six times
# across P6-P8 and kaniko does not retry a base pull, so every retry was a human clicking the job.
FROM registry.gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/base/nodejs20-debian12@sha256:6fe218dbad37e979c7542e670d28d6e23d3f53d2929693bc9cdded8b622f339f AS runtime
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
ENV PORT=8080
EXPOSE 8080
USER nonroot
CMD ["dist/server.js"]
