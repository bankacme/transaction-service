# syntax=docker/dockerfile:1

# ---- Etapa 1: compilar ----------------------------------------------------------------------
# Maven dentro de la imagen: no hace falta Java ni Maven en la máquina para construir.
# Sin pruebas (-DskipTests): ya corren con ./mvnw verify antes del commit, y algunas leen
# carpetas hermanas (../bank-config) que no están dentro de la imagen. Checkstyle sí corre.
# --platform=$BUILDPLATFORM: se compila una vez, en la arquitectura de quien construye (el .jar
# sirve para cualquiera); solo la etapa 2 se arma para cada plataforma (amd64 y arm64 en el pipeline).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
COPY src ./src
# La caché de ~/.m2 se reutiliza entre construcciones y entre servicios (BuildKit).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted \
    && mkdir -p extracted/dependencies extracted/spring-boot-loader \
                extracted/snapshot-dependencies extracted/application

# ---- Etapa 2: ejecutar ----------------------------------------------------------------------
# Solo el JRE. Las capas van de la que menos cambia (dependencias) a la que más (nuestro código),
# así una reconstrucción tras cambiar el código reutiliza las capas de dependencias.
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring && useradd --system --gid spring spring
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER spring
# La memoria la fija el contenedor (mem_limit en el docker-compose); la JVM usa el 75 %.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "application.jar"]
