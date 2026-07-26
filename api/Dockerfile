# --- Build: compila el jar con el wrapper firmado (no se toca) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Primero solo los descriptores, para cachear la resolución de dependencias.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q || true
COPY src ./src
# Los tests necesitan Docker (Testcontainers); se ejecutan en CI, no aquí.
RUN ./gradlew --no-daemon clean bootJar -x test

# --- Runtime: JRE 21, usuario sin privilegios ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd -r guaita && useradd -r -g guaita guaita
COPY --from=build /app/build/libs/*.jar app.jar
USER guaita
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
