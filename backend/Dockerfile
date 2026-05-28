# Multi-stage build: compile Gradle → JDK image; run on slimmer JRE image.

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the wrapper + settings first so Docker can cache dependency resolution
# when only sources change.
COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew

# Pre-warm the dependency cache. Allowed to "fail" (no source yet); the goal
# is just to download dependencies into the layer.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
