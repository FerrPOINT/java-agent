package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real bot HTTP client and backend; Telegram remains an in-process test boundary. */
@Tag("slow")
class ReviewNetworkPostgresTest {
    @Test
    void claimBeforeNotificationIsPersistentAndNotReplayedAfterRefusal(@org.junit.jupiter.api.io.TempDir java.nio.file.Path sandbox) throws Exception {
        try (var db = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            db.start();
            int port;
            try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            var log = Files.createTempFile("review-network-backend-", ".log");
            var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                "-Duser.home=" + sandbox, "-Djava.io.tmpdir=" + sandbox, "-jar",
                System.getProperty("backend.jar"), "--server.address=127.0.0.1", "--server.port=" + port,
                "--management.server.port=" + port, "--agent.chromium.auto-start=false", "--agent.chromium.auto-install=false");
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            var env = builder.environment();
            env.clear();
            env.put("HOME", sandbox.toString());
            env.put("TMPDIR", sandbox.toString());
            builder.directory(sandbox.toFile());
            env.put("SPRING_DATASOURCE_URL", db.getJdbcUrl());
            env.put("SPRING_DATASOURCE_USERNAME", db.getUsername());
            env.put("SPRING_DATASOURCE_PASSWORD", db.getPassword());
            env.put("AGENT_SECURITY_API_KEY", "");
            env.put("AGENT_MODEL_PROVIDER", "noop");
            env.put("AGENT_MEMORY_ENABLED", "false");
            env.put("AGENT_MEMORY_BACKGROUND_REVIEW_ENABLED", "true");
            env.put("AGENT_SKILLS_ENABLED", "false");
            env.put("AGENT_MCP_ENABLED", "false");
            Process backend = builder.start();
            try {
                String base = "http://127.0.0.1:" + port;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                boolean ready = false;
                while (System.nanoTime() < deadline && backend.isAlive()) {
                    try {
                        var connection = (HttpURLConnection) URI.create(base + "/actuator/health").toURL().openConnection();
                        connection.setConnectTimeout(1000);
                        connection.setReadTimeout(1000);
                        ready = connection.getResponseCode() == 200;
                        connection.disconnect();
                        if (ready) break;
                    } catch (java.io.IOException ignored) { }
                    Thread.sleep(250);
                }
                assertThat(ready).as("isolated backend started; process alive=%s", backend.isAlive()).isTrue();
                UUID session = UUID.randomUUID(), delivery = UUID.randomUUID();
                try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                     var insert = connection.prepareStatement("INSERT INTO pending_review_summaries(delivery_id,session_id,summary) VALUES (?,?,?)")) {
                    insert.setObject(1, delivery); insert.setObject(2, session); insert.setString(3, "test review"); insert.executeUpdate();
                }
                var api = new SessionApiClient(RestClient.builder().baseUrl(base).build(), new ObjectMapper());
                UUID otherSession = UUID.randomUUID(), otherDelivery = UUID.randomUUID();
                try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                     var insert = connection.prepareStatement("INSERT INTO pending_review_summaries(delivery_id,session_id,summary) VALUES (?,?,?)")) {
                    insert.setObject(1, otherDelivery); insert.setObject(2, otherSession); insert.setString(3, "other review"); insert.executeUpdate();
                }
                assertThat(api.acknowledgePendingReview(otherSession.toString(), UUID.randomUUID().toString())).isFalse();
                assertThat(api.getPendingReview(otherSession.toString()).path("pending").asBoolean()).isTrue();
                var client = new AgentBackendClient(api, null, null, null, null, null, null, null);
                var orchestrator = new StreamingOrchestrator(client, null, null, null, new BotProperties(),
                    new MediaDeliveryService(), null, null, null);
                AtomicInteger sends = new AtomicInteger();
                var hooks = new StreamingOrchestrator.ProcessorHooks() {
                    public String resolveModelUsed(BotSessionEntity s, AgentBackendClient.ChatResult r) { return null; }
                    public void deliverMedia(long chat, List<MediaDeliveryService.MediaDescriptor> media, Integer thread) { }
                    public String buildMessageWithContext(String text, BotSessionEntity s, long chat) { return text; }
                    public boolean sendReviewMessage(long chat, String text, long message, long thread) {
                        assertThat(chat).isEqualTo(100L);
                        assertThat(text).isEqualTo("test review");
                        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                             var query = connection.prepareStatement("SELECT count(*) FROM pending_review_summaries WHERE delivery_id=?")) {
                            query.setObject(1, delivery);
                            try (var rows = query.executeQuery()) {
                                rows.next();
                                assertThat(rows.getInt(1)).as("claim committed before notification").isZero();
                            }
                        } catch (java.sql.SQLException e) {
                            throw new AssertionError("Cannot verify claim before notification", e);
                        }
                        sends.incrementAndGet();
                        return false;
                    }
                };
                try {
                    assertThat(orchestrator.deliverPendingReview(session.toString(), 100L, 0L, 0L, hooks)).isTrue();
                    assertThat(orchestrator.deliverPendingReview(session.toString(), 100L, 0L, 0L, hooks)).isFalse();
                    assertThat(sends).hasValue(1);
                    try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                         var query = connection.prepareStatement("SELECT count(*) FROM pending_review_summaries WHERE delivery_id=?")) {
                        query.setObject(1, delivery);
                        try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
                    }
                    UUID concurrentSession = UUID.randomUUID(), concurrentDelivery = UUID.randomUUID();
                    try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                         var insert = connection.prepareStatement("INSERT INTO pending_review_summaries(delivery_id,session_id,summary) VALUES (?,?,?)")) {
                        insert.setObject(1, concurrentDelivery);
                        insert.setObject(2, concurrentSession);
                        insert.setString(3, "test review");
                        insert.executeUpdate();
                    }
                    var bothRead = new java.util.concurrent.CountDownLatch(2);
                    var racingApi = new SessionApiClient(RestClient.builder().baseUrl(base).build(), new ObjectMapper()) {
                        @Override public com.fasterxml.jackson.databind.JsonNode getPendingReview(String id) {
                            var pending = super.getPendingReview(id);
                            if (id.equals(concurrentSession.toString())) {
                                assertThat(pending).isNotNull();
                                assertThat(pending.path("pending").asBoolean()).isTrue();
                                bothRead.countDown();
                                try {
                                    if (!bothRead.await(10, TimeUnit.SECONDS)) throw new AssertionError("both reads timed out");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                            }
                            return pending;
                        }
                    };
                    var racingClient = new AgentBackendClient(racingApi, null, null, null, null, null, null, null);
                    var first = new StreamingOrchestrator(racingClient, null, null, null, new BotProperties(),
                        new MediaDeliveryService(), null, null, null);
                    var second = new StreamingOrchestrator(racingClient, null, null, null, new BotProperties(),
                        new MediaDeliveryService(), null, null, null);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var callersReady = new java.util.concurrent.CountDownLatch(2);
                    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
                    AtomicInteger concurrentSends = new AtomicInteger();
                    var concurrentHooks = new StreamingOrchestrator.ProcessorHooks() {
                        public String resolveModelUsed(BotSessionEntity s, AgentBackendClient.ChatResult r) { return null; }
                        public void deliverMedia(long chat, List<MediaDeliveryService.MediaDescriptor> media, Integer thread) { }
                        public String buildMessageWithContext(String text, BotSessionEntity s, long chat) { return text; }
                        public boolean sendReviewMessage(long chat, String text, long message, long thread) {
                            concurrentSends.incrementAndGet();
                            return true;
                        }
                    };
                    try {
                        java.util.concurrent.Callable<Boolean> firstCall = () -> {
                            callersReady.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                            return first.deliverPendingReview(concurrentSession.toString(), 100L, 0L, 0L, concurrentHooks);
                        };
                        java.util.concurrent.Callable<Boolean> secondCall = () -> {
                            callersReady.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                            return second.deliverPendingReview(concurrentSession.toString(), 100L, 0L, 0L, concurrentHooks);
                        };
                        var firstResult = executor.submit(firstCall);
                        var secondResult = executor.submit(secondCall);
                        assertThat(callersReady.await(10, TimeUnit.SECONDS)).isTrue();
                        start.countDown();
                        assertThat(List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS)))
                            .containsExactlyInAnyOrder(true, false);
                        assertThat(concurrentSends).hasValue(1);
                        assertThat(api.getPendingReview(concurrentSession.toString()).path("pending").asBoolean()).isFalse();
                    } finally {
                        start.countDown();
                        executor.shutdownNow();
                        first.shutdownReviewPollScheduler();
                        second.shutdownReviewPollScheduler();
                    }
                } finally {
                    orchestrator.shutdownReviewPollScheduler();
                }
            } finally {
                backend.destroy();
                if (!backend.waitFor(20, TimeUnit.SECONDS)) { backend.destroyForcibly(); backend.waitFor(); }
                Files.deleteIfExists(log);
            }
        }
    }
}
