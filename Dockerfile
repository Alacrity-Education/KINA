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
    && mkdir -p /app /data/jlcpcb /data/cross-encoder \
    && chown -R kina:kina /data
WORKDIR /app
COPY --from=build /build/target/kina.jar /app/kina.jar
# /data/jlcpcb: JLCPCB parts database; /data/cross-encoder: ranking model (downloaded on first start, DESIGN.md 3.5)
ENV KINA_JLCPCB_DATA_DIR=/data/jlcpcb \
    KINA_CROSS_ENCODER_MODEL_DIR=/data/cross-encoder
VOLUME ["/data"]
USER kina
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
# --enable-native-access: sqlite-jdbc and ONNX Runtime load native libraries (silences the JDK 21+ restricted-method
# warning); ONNX Runtime extracts its bundled library to java.io.tmpdir at first use.
# MaxRAMPercentage sizes the heap from the container memory limit (compose sets mem_limit for kina).
# Extra JVM flags can still be passed with JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "/app/kina.jar"]
