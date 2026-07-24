# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
COPY backend/build.gradle backend/settings.gradle ./backend/
RUN ./gradlew --version

COPY backend/src backend/src
RUN ./gradlew :backend:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S agent && adduser -S agent -G agent
WORKDIR /app
COPY --from=builder /workspace/backend/build/libs/*.jar app.jar
RUN chown -R agent:agent /app
USER agent

ENV AGENT_SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
