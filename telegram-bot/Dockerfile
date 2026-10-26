FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:0d623ea18d7b0fe1e12a2c0a920f7e950cad433387e5a49d3611e1507fa07602 AS builder
WORKDIR /workspace
COPY LICENSE NOTICE THIRD_PARTY_NOTICES.md ./
COPY settings.gradle ./
COPY gradle ./gradle
COPY docs/standards/coverage-baseline.json ./docs/standards/coverage-baseline.json
COPY gradlew ./
COPY shared/build.gradle ./shared/build.gradle
COPY shared/src ./shared/src
COPY telegram-bot/build.gradle ./telegram-bot/build.gradle
COPY telegram-bot/src ./telegram-bot/src
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew -PqualityRootGraph=clients-selected-v1 -PqualityRootComponents=bot :telegram-bot:bootJar --no-daemon --max-workers=2

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agent \
    && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=builder /workspace/telegram-bot/build/libs/*.jar app.jar
RUN mkdir -p /workspace /artifacts /home/agent/.java-agent \
    && chown -R agent:agent /app /workspace /artifacts /home/agent/.java-agent
USER agent
ENV HOME=/home/agent
EXPOSE 8091
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
