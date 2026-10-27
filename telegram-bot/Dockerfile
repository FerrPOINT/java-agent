# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:0d623ea18d7b0fe1e12a2c0a920f7e950cad433387e5a49d3611e1507fa07602 AS builder
ARG PDLC_RUN_SLOT_SHARING=locked
ARG TARGETARCH
ARG SOURCE_DATE_EPOCH=0
ENV SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}
WORKDIR /workspace
COPY LICENSE NOTICE THIRD_PARTY_NOTICES.md ./
COPY settings.gradle ./
COPY gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
COPY scripts/quality-gradle.sh ./scripts/quality-gradle.sh
# The selected clients graph still configures CLI and shared alongside the bot.
COPY cli/build.gradle cli/test-dependencies.gradle ./cli/
COPY shared/build.gradle shared/test-dependencies.gradle ./shared/
COPY shared/src ./shared/src
COPY telegram-bot/build.gradle ./telegram-bot/build.gradle
COPY telegram-bot/src ./telegram-bot/src
RUN --mount=type=cache,id=pdlc-host-run-slot-v1,target=/.__pdlc_build_slot__,sharing=${PDLC_RUN_SLOT_SHARING} --mount=type=cache,id=java-agent-gradle-9.6.1-temurin25.0.4.1-${TARGETARCH},target=/root/.gradle,sharing=locked sed -i 's/\r$//' gradlew scripts/quality-gradle.sh && chmod +x gradlew && bash scripts/quality-gradle.sh --quality-root :telegram-bot:bootJar --build-cache --no-daemon --max-workers=2

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b
ARG PDLC_RUN_SLOT_SHARING=locked
RUN --mount=type=cache,id=pdlc-host-run-slot-v1,target=/.__pdlc_build_slot__,sharing=${PDLC_RUN_SLOT_SHARING} apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agent \
    && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=builder /workspace/telegram-bot/build/libs/*.jar app.jar
RUN --mount=type=cache,id=pdlc-host-run-slot-v1,target=/.__pdlc_build_slot__,sharing=${PDLC_RUN_SLOT_SHARING} chown -R agent:agent /app
USER agent
ENV HOME=/home/agent
EXPOSE 8091
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
