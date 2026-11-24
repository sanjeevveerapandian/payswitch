# syntax=docker/dockerfile:1
#
# ONE production Dockerfile for all three Java services. Which one is chosen by a build argument:
#
#   docker build --build-arg MODULE=switch-service --build-arg PORT=8083 -t payswitch/switch-service .
#
# docker-compose.yml passes these for psp-service, switch-service and bank-service (built once, run
# three times as Bank A, B and C). The build context is the repo root, because every service needs
# the parent pom and the shared modules (common, ledger-core, observability).

# ---- Stage 1: build ---------------------------------------------------------------------------
# Full JDK + Maven. Nothing from this stage reaches the final image except the files we copy out.
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
# .dockerignore keeps target/, .git and the IDE files out, so this copies only sources and poms.
COPY . .
# -pl MODULE -am: build this service and only the modules it depends on.
# The cache mount keeps ~/.m2 between builds on this machine, so libraries are downloaded once.
# Tests are not run here: CI runs "mvn verify" (with a real Postgres) before an image is trusted.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}-*-exec.jar /src/app.jar
# Split the fat jar into its layers (Spring Boot's "tools" jar mode):
#   dependencies           third-party libraries: change rarely
#   spring-boot-loader     the launcher classes: change almost never
#   snapshot-dependencies  our own -SNAPSHOT modules (common, ledger-core, observability)
#   application            this service's own classes and resources: change on every commit
RUN java -Djarmode=tools -jar /src/app.jar extract --layers --launcher --destination /src/extracted

# ---- Stage 2: run -----------------------------------------------------------------------------
# Only a Java 21 runtime (JRE, no compiler), on Alpine: a small image with few packages to patch.
FROM eclipse-temurin:21-jre-alpine
ARG MODULE
ARG PORT=8080
# A fixed, unprivileged user. If someone breaks into the app, they are not root in the container.
RUN addgroup -S -g 10001 payswitch && adduser -S -D -H -u 10001 -G payswitch payswitch
WORKDIR /app
# One COPY per layer, least-changing first. Docker caches each COPY as its own image layer, so a
# code change rebuilds and re-uploads only the last, small "application" layer (a few hundred KB),
# not the ~60 MB of libraries. Files stay owned by root: the app can read them, not change them.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
USER payswitch

# The JVM reads JAVA_TOOL_OPTIONS by itself, so ENTRYPOINT can stay in exec form (no shell between
# Docker and java: java is PID 1 and gets Docker's stop signal directly).
#   UseContainerSupport     size the JVM from the CONTAINER's memory and CPU limits, not the host's
#                           (on by default since Java 10; stated so nobody wonders)
#   MaxRAMPercentage=75     heap up to 75% of the container limit; the other 25% is for everything
#                           that is not heap: classes (metaspace), JIT code, thread stacks, buffers
#   ExitOnOutOfMemoryError  a JVM that ran out of memory is in an unknown state: exit, and let
#                           Docker's restart policy start a clean one
ENV PORT=${PORT} \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE ${PORT}
# Docker marks the container unhealthy if Spring Boot's health check (app up AND database reachable)
# stops answering. wget is part of Alpine's busybox, so no extra package is needed.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
