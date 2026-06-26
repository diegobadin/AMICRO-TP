# CLI packaged so it runs uniformly as `docker run <image> <subcommand> <flags>` (Client §7).
# Configuration (UNOARENA_API_URL) is provided at runtime via env, never hardcoded.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --no-audit --no-fund
COPY src ./src
COPY scripts ./scripts
RUN npm run build

FROM node:20-alpine AS runtime
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY --from=build /app/scripts ./scripts
USER node
ENTRYPOINT ["node", "dist/cli.js"]
