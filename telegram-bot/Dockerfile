FROM gradle:jdk25 AS builder
WORKDIR /workspace
COPY settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./
# settings.gradle includes ':cli'; gradle requires the directory to exist even
# when the bot does not depend on it — copy its build files too.
COPY cli/build.gradle ./cli/build.gradle
COPY backend/build.gradle ./backend/build.gradle
COPY backend/src ./backend/src
COPY telegram-bot/build.gradle ./telegram-bot/build.gradle
COPY telegram-bot/src ./telegram-bot/src
RUN chmod +x gradlew && ./gradlew :telegram-bot:bootJar -x test

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /workspace/telegram-bot/build/libs/*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]