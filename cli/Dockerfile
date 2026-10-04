FROM gradle:jdk25 AS builder
WORKDIR /workspace
COPY LICENSE NOTICE THIRD_PARTY_NOTICES.md ./
COPY settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./
COPY shared/build.gradle ./shared/build.gradle
COPY shared/src ./shared/src
COPY backend/build.gradle ./backend/build.gradle
COPY telegram-bot/build.gradle ./telegram-bot/build.gradle
COPY cli/build.gradle ./cli/build.gradle
COPY cli/src ./cli/src
RUN chmod +x gradlew && ./gradlew :cli:bootJar -x test

FROM eclipse-temurin:25-jre-noble
RUN groupadd --gid 10001 agent \
    && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
COPY --from=builder /workspace/cli/build/libs/*.jar app.jar
RUN chown -R agent:agent /app
USER agent
ENV HOME=/home/agent
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
