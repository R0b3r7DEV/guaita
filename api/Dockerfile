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
# curl NO es opcional: lo usa el healthcheck definido en docker-compose.yml,
# que se ejecuta DENTRO de este contenedor. eclipse-temurin:21-jre (Ubuntu) no
# lo trae. No quitar aunque este Dockerfile ya no declare HEALTHCHECK.
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd -r guaita && useradd -r -g guaita guaita
COPY --from=build /app/build/libs/*.jar app.jar
USER guaita
EXPOSE 8080
# El healthcheck vive en la orquestación (docker-compose.yml), no aquí: el
# Dockerfile queda agnóstico de cómo se despliega la imagen.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
