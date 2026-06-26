# Multi-stage, non-root, minimal base. Builds an artifact that runs unchanged in any environment;
# config (PORT, secrets, URLs) comes from the environment, never baked in (consigna §6.4).
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --no-audit --no-fund
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM gcr.io/distroless/nodejs20-debian12 AS runtime
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
ENV PORT=8085
EXPOSE 8085
USER nonroot
CMD ["dist/server.js"]
