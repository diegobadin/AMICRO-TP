# Multi-stage, non-root, minimal base. Builds an artifact that runs unchanged in any environment;
# config (PORT, secrets, URLs) comes from the environment, never baked in (consigna §6.4).
FROM gradle:8.8-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY engine ./engine
COPY src ./src
# Two JVM temp files vanish mid-build and both fail Kaniko's snapshot *after* a successful compile:
#   -XX:-UsePerfData        stops /tmp/hsperfdata_root/<pid>
#   execution.strategy      stops /root/.kotlin/daemon/*.run — `gradle --no-daemon` does not cover
#                           this one, because the Kotlin compiler spawns a daemon of its own.
# The rm is belt and braces: whatever is left there is not wanted in the image either.
RUN GRADLE_OPTS="-XX:-UsePerfData" gradle --no-daemon \
      -Dorg.gradle.jvmargs=-XX:-UsePerfData \
      -Pkotlin.compiler.execution.strategy=in-process \
      installDist \
 && rm -rf /root/.kotlin /root/.gradle/daemon

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Numeric uid, and USER given numerically: with a named user the kubelet cannot verify runAsNonRoot
# and refuses to start the container.
RUN addgroup -S -g 65532 app && adduser -S -u 65532 -G app app
COPY --from=build /app/build/install/room-gameplay ./
ENV PORT=8081
EXPOSE 8081
USER 65532
CMD ["./bin/room-gameplay"]
