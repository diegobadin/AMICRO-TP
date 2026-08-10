# Multi-stage, non-root, minimal base. Builds an artifact that runs unchanged in any environment;
# config (PORT, secrets, URLs) comes from the environment, never baked in (consigna §6.4).
FROM gradle:8.8-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
# Two JVM temp files vanish mid-build and both fail Kaniko's snapshot *after* a successful compile:
#   -XX:-UsePerfData        stops /tmp/hsperfdata_root/<pid>
#   execution.strategy      stops /root/.kotlin/daemon/*.run — `gradle --no-daemon` does not cover
#                           this one, because the Kotlin compiler spawns a daemon of its own.
# Same fix as services/room-gameplay/Dockerfile; both are Kotlin, so both need it.
RUN GRADLE_OPTS="-XX:-UsePerfData" gradle --no-daemon \
      -Dorg.gradle.jvmargs=-XX:-UsePerfData \
      -Pkotlin.compiler.execution.strategy=in-process \
      installDist \
 && rm -rf /root/.kotlin /root/.gradle/daemon

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/install/tournament ./
ENV PORT=8083
EXPOSE 8083
USER app
CMD ["./bin/tournament"]
