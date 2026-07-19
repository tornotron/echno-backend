# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# Echno backend.
#
# Stage 1 compiles inside the image. Previously the image expected a jar built
# on the host, so it could not be reproduced from the repository alone and
# whatever happened to be in build/libs was shipped.
#
# Stage 2 runs on a JRE rather than a full JDK, as a non-root user. A compiler
# and developer tooling have no reason to exist in a running service.
# ---------------------------------------------------------------------------

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# Wrapper and dependency declarations first, so dependency resolution is cached
# and only re-runs when the build files actually change.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --refresh-dependencies || true

COPY src ./src

# Tests are the pull request gate, not part of packaging. Building an artefact
# and verifying it are separate concerns, and coupling them means a flaky test
# blocks a release that was already reviewed.
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Stage 2: run ----
FROM eclipse-temurin:21-jre-jammy AS runner

# Unprivileged. A container running as root shares that uid with the host, so a
# container escape starts from a much better position than it needs to.
RUN groupadd --system --gid 1001 echno \
 && useradd --system --uid 1001 --gid echno --home /app --shell /usr/sbin/nologin echno

WORKDIR /app

# The application's logback configuration writes to /app/logs. Running as a
# non-root user in a root-owned directory, it cannot create that, and the
# container exits on startup.
#
# Worth revisiting in the application itself: a container writing logs to a file
# traps them inside its own filesystem, where the log collector never sees them
# and they vanish with the container. Logging to stdout would let the platform
# handle collection, retention and rotation.
RUN mkdir -p /app/logs && chown -R echno:echno /app

COPY --from=builder --chown=echno:echno /build/build/libs/*.jar /app/echno-backend.jar

USER echno
EXPOSE 8080

# Container-aware heap sizing. Without this the JVM sizes itself against the
# host's memory and ignores the container limit, then gets OOM-killed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/echno-backend.jar"]
