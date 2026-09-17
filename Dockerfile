# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/build.gradle backend/settings.gradle backend/gradle.properties ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY backend/src src
# h10: the backend build resolves :shared as a sibling module (backend/settings.gradle
# includes ../shared when present) — copy it in so the container layout matches the repo.
COPY shared/ shared/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-noble

# Chromium runtime dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fonts-liberation \
        libasound2t64 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libc6 \
        libcairo2 \
        libcups2 \
        libdbus-1-3 \
        libdrm2 \
        libexpat1 \
        libgbm1 \
        libglib2.0-0 \
        libgtk-3-0 \
        libheif-examples \
        libheif-plugin-aomdec \
        libheif-plugin-libde265 \
        libnspr4 \
        libnss3 \
        libpango-1.0-0 \
        libx11-6 \
        libxcb1 \
        libxcomposite1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxkbcommon0 \
        libxrandr2 \
        libxshmfence1 \
        python3 \
        python3-venv \
        unzip \
        wget \
        xdg-utils \
    && python3 -m venv /opt/edge-tts \
    && /opt/edge-tts/bin/pip install --no-cache-dir edge-tts==7.2.7 \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN chown -R agent:agent /app

# Cache dir for Chromium auto-install
RUN mkdir -p /home/agent/.azhukov-agent && chown -R agent:agent /home/agent/.azhukov-agent

USER agent
ENV AGENT_SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV HOME=/home/agent
EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=50.0", "-jar", "/app/app.jar"]
