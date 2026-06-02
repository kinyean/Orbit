# Multi-stage build:
#   build — compile the Spring Boot fat jar (Gradle, JDK)
#   data  — fetch the offline data bundles (Orekit data + TLE seed)
#   run   — slim JRE image with the jar + baked-in data
#
# CelesTrak is firewall-blocked from this host, so the catalog must work offline:
# we bake the Orekit data bundle and a TLE/GP snapshot into the image at build
# time from host-reachable sources (Orekit GitLab + a GitHub CelesTrak mirror).

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the wrapper + settings first so Docker caches dependency resolution
# when only sources change.
COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- data ----
# Downloaded in a tiny stage with curl+unzip so the runtime image stays clean.
# Bump DATA_REFRESH to cache-bust and pull fresher data.
FROM alpine:3.20 AS data
ARG DATA_REFRESH=2026-06-02
RUN apk add --no-cache curl unzip ca-certificates

# Orekit data bundle (IERS EOP, leap seconds, ephemerides, gravity models).
# Branch is `main` (not master); unpacks under orekit-data-main/.
RUN curl -sSL -o /tmp/orekit-data.zip \
      "https://gitlab.orekit.org/orekit/orekit-data/-/archive/main/orekit-data-main.zip" \
 && mkdir -p /opt/orekit-data \
 && unzip -q /tmp/orekit-data.zip -d /tmp \
 && cp -r /tmp/orekit-data-main/. /opt/orekit-data/ \
 && rm -rf /tmp/orekit-data.zip /tmp/orekit-data-main \
 && test -f /opt/orekit-data/tai-utc.dat

# Offline TLE/GP seed (CelesTrak GP/OMM JSON via a reachable GitHub mirror,
# ~15k current active satellites). Best-effort runtime refresh upgrades it.
RUN mkdir -p /opt/orbit \
 && curl -sSL -o /opt/orbit/tle-seed.json \
      "https://raw.githubusercontent.com/jaimeautomatiza/tle-proxy/main/data/tle.json" \
 && test -s /opt/orbit/tle-seed.json

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY --from=data /opt/orekit-data /opt/orekit-data
COPY --from=data /opt/orbit /opt/orbit
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
