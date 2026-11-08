# syntax=docker/dockerfile:1
#
# One Dockerfile for every service of the monorepo. The Maven build runs ONCE (stage `build`) and each service is
# a small final stage that only copies its own jar, selected with `--target <service>` (docker-compose does this).

# ---- build: compile and package all modules, tests skipped (they run in CI before images are built) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
# the cache mount keeps downloaded dependencies between builds, so a rebuild takes seconds, not minutes
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

# ---- runtime base: JRE only, runs as a non-root user ----
# (not the -alpine variant: it has no ARM build, so it would not run on Apple Silicon or AWS Graviton)
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system app && useradd --system --gid app --no-create-home app
USER app
WORKDIR /app
# size the heap from the container's memory limit instead of the host's; die cleanly on OOM so Docker restarts us
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS api-gateway
COPY --from=build /build/api-gateway/target/api-gateway-*.jar /app/app.jar
EXPOSE 8080

FROM runtime AS auth-service
COPY --from=build /build/auth-service/target/auth-service-*.jar /app/app.jar
EXPOSE 8092

FROM runtime AS account-service
COPY --from=build /build/account-service/target/account-service-*.jar /app/app.jar
EXPOSE 8091

FROM runtime AS transaction-service
COPY --from=build /build/transaction-service/target/transaction-service-*.jar /app/app.jar
EXPOSE 8093

FROM runtime AS notification-service
COPY --from=build /build/notification-service/target/notification-service-*.jar /app/app.jar
EXPOSE 8094
