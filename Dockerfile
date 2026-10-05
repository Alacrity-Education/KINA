# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 kina \
    && useradd --system --uid 10001 --gid kina --home-dir /app --no-create-home kina \
    && mkdir -p /app /data/jlcpcb \
    && chown -R kina:kina /data
WORKDIR /app
COPY --from=build /build/target/kina.jar /app/kina.jar
ENV KINA_JLCPCB_DATA_DIR=/data/jlcpcb
VOLUME ["/data"]
USER kina
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
# --enable-native-access: sqlite-jdbc loads its native library (silences the JDK 21+ restricted-method warning).
# MaxRAMPercentage sizes the heap from the container memory limit (compose sets mem_limit for kina).
# Extra JVM flags can still be passed with JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "/app/kina.jar"]
