# Two stages, so the image that runs in production does not carry a build toolchain.
#
# Railway will also happily build this with Nixpacks and no Dockerfile at all. This exists
# because the build should be the same everywhere: the jar somebody tests locally, the jar CI
# publishes on a tag, and the jar that serves traffic are then the same artifact produced the
# same way, rather than three builds that agree until they do not.

FROM gradle:8.14-jdk21 AS build
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

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
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
