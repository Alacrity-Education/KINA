# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY mvnw pom.xml lombok.config ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# ---- model: the cross-encoder, fetched and verified at build time (DESIGN.md 3.5, 11) ----
# The running container never contacts Hugging Face. Build args:
#   CROSS_ENCODER_SOURCE       HTTP(S) directory with the Hugging Face layout; default: the pinned revision below.
#                              A fine-tuned model (scripts/ranking/finetune_cross_encoder.sh) served over HTTP works too.
#   CROSS_ENCODER_VARIANTS     int8,fp32 (default) | int8 (about 90 MB smaller) | fp32
#   CROSS_ENCODER_SHA256_FILE  hash file name in docker/model/ (a custom source needs its own)
#   CROSS_ENCODER_SKIP_VERIFY  1 = skip the SHA-256 check (custom sources only)
#   CROSS_ENCODER_REVISION     revision recorded in model.json (default: from a Hugging Face resolve URL)
FROM alpine:3.22 AS model
RUN apk add --no-cache curl coreutils
ARG CROSS_ENCODER_SOURCE=https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/resolve/233902d25c440f23af6f7d6e94d2946bac0bee0a/
ARG CROSS_ENCODER_VARIANTS=int8,fp32
ARG CROSS_ENCODER_SHA256_FILE=ms-marco-MiniLM-L6-v2.sha256
ARG CROSS_ENCODER_SKIP_VERIFY=0
ARG CROSS_ENCODER_REVISION=
COPY docker/model/ /src/
RUN CROSS_ENCODER_SHA256_FILE=/src/${CROSS_ENCODER_SHA256_FILE} sh /src/fetch-model.sh /model \
    && find /model -type d -exec chmod 0555 {} + \
    && find /model -type f -exec chmod 0444 {} +

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 kina \
    && useradd --system --uid 10001 --gid kina --home-dir /app --no-create-home kina \
    && mkdir -p /app /data/jlcpcb /opt/kina \
    && chown -R kina:kina /data
WORKDIR /app
# Read-only bundled model, used in place (no download, no writes)
COPY --from=model --chown=10001:10001 /model /opt/kina/cross-encoder
RUN chmod 0555 /opt/kina/cross-encoder
COPY --from=build /build/target/kina.jar /app/kina.jar
# /data/jlcpcb: JLCPCB parts database (downloaded on first start). The ranking model is in the image;
# KINA_CROSS_ENCODER_MODEL_URL set by the operator (.env or compose) overrides the bundled directory.
ENV KINA_JLCPCB_DATA_DIR=/data/jlcpcb \
    KINA_CROSS_ENCODER_MODEL_URL=/opt/kina/cross-encoder \
    KINA_CROSS_ENCODER_AUTO_DOWNLOAD=false
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
