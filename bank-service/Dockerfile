# syntax=docker/dockerfile:1
# Simple two-stage image. Build context is the repo root (see docker-compose.yml),
# because bank-service needs the parent pom and the modules it depends on.

# Stage 1: compile and package with Maven. The cache mount keeps downloaded libraries
# between builds; "locked" lets only one build use that folder at a time.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl bank-service -am package -DskipTests

# Stage 2: only a Java runtime and the runnable jar. No Maven, no source code.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/bank-service/target/bank-service-*-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
