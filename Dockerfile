# Builds the Java service and a non-root runtime with local OCR tools.
# Uses the same Gradle project and document process adapters as local tests.
# Base digests and OCR package versions are pinned; no remote OCR is configured.
FROM gradle:8.14-jdk21@sha256:566f76c0c946a2f68e10d4374d61f28ad8411f36f364a41a981ab46ded096d18 AS build
WORKDIR /src
# Dependency descriptors first, so a change to a Java file does not re-resolve the whole
# dependency graph on every deploy.
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true
COPY src ./src
COPY config ./config
# Tests are not run here. They need Docker for Testcontainers, which is not available inside
# a build container, and CI has already run them on this commit. A deploy that silently
# skipped tests would be worse, so the release workflow is what gates the tag.
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre@sha256:ccbad9e7cb84c27092b12c40a4143e5733e050db9c3784d84f632dcf423fb2ef AS runtime
WORKDIR /app
# The document adapter invokes these locally and refuses OCR when they are absent.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr=5.5.0-1build1 poppler-utils=26.01.0-2ubuntu0.2 \
    && rm -rf /var/lib/apt/lists/*
# Not root. A process that accepts strangers' file uploads should not be able to write to
# its own installation.
RUN useradd --create-home --shell /usr/sbin/nologin basis
COPY --from=build /src/build/libs/basis.jar ./basis.jar
# Broker profiles, the commodity catalogue and the rename table are read from the working
# directory at runtime, so they have to travel with the jar rather than being baked into it.
COPY --from=build /src/config ./config
USER basis

# Railway supplies PORT and expects the process to bind it. Defaulted so the image also runs
# locally with nothing set.
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

EXPOSE 8080
# "serve" rather than a bare jar invocation: it is the one command that stays up, and naming
# it here means the container cannot accidentally start a CLI process that exits immediately
# and reads as a crash loop.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=$PORT -jar basis.jar serve"]
