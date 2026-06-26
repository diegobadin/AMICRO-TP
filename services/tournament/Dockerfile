# Multi-stage, non-root, minimal base. Builds an artifact that runs unchanged in any environment;
# config (PORT, secrets, URLs) comes from the environment, never baked in (consigna §6.4).
FROM gradle:8.8-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/install/tournament ./
ENV PORT=8083
EXPOSE 8083
USER app
CMD ["./bin/tournament"]
