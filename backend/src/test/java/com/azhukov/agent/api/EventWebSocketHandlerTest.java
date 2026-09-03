package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.EventService;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventWebSocketHandlerTest {

    @TempDir
    private Path tempDir;

    private ObjectMapper objectMapper;
    private EventService eventService;
    private ProfileService profileService;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        eventService = new EventService(10);
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        profileService = new ProfileService(properties, new RuntimeConfigService());
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
    }

    @Test
    void sendsReplayEventsAfterRequestedCursor() throws Exception {
        eventService.publish("delegate.created", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of());
        eventService.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("result", "ok"));
        EventWebSocketHandler handler = handler();
        CountDownLatch sent = new CountDownLatch(1);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = session("ws://localhost/p/work/api/events?after=1", open, messages, sent);

        handler.afterConnectionEstablished(session);

        assertThat(sent.await(1, TimeUnit.SECONDS)).isTrue();
        open.set(false);
        assertThat(messages).hasSize(1);
        JsonNode event = objectMapper.readTree(((TextMessage) messages.get(0)).getPayload());
        assertThat(event.get("type").asText()).isEqualTo("delegate.completed");
        assertThat(event.get("profile").asText()).isEqualTo("work");
        assertThat(event.get("payload").get("result").asText()).isEqualTo("ok");
    }

    @Test
    void waitsForNewEventsAfterConnection() throws Exception {
        EventWebSocketHandler handler = handler();
        CountDownLatch sent = new CountDownLatch(1);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = session("ws://localhost/api/events?profile=work", open, messages, sent);

        handler.afterConnectionEstablished(session);
        eventService.publish("cron.success", "work", null, null, Map.of("job_id", "job-1"));

        assertThat(sent.await(1, TimeUnit.SECONDS)).isTrue();
        open.set(false);
        JsonNode event = objectMapper.readTree(((TextMessage) messages.get(0)).getPayload());
        assertThat(event.get("type").asText()).isEqualTo("cron.success");
        assertThat(event.get("payload").get("job_id").asText()).isEqualTo("job-1");
    }

    @Test
    void unknownProfileFailsClosed() throws Exception {
        EventWebSocketHandler handler = handler();
        CountDownLatch sent = new CountDownLatch(1);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = session("ws://localhost/p/missing/api/events", open, messages, sent);

        handler.afterConnectionEstablished(session);

        assertThat(sent.await(1, TimeUnit.SECONDS)).isTrue();
        JsonNode error = objectMapper.readTree(((TextMessage) messages.get(0)).getPayload());
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("error").asText()).isEqualTo("Unknown profile: missing");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    private EventWebSocketHandler handler() {
        return new EventWebSocketHandler(
            eventService,
            profileService,
            objectMapper,
            Duration.ofMillis(200),
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("test-event-ws-", 0).factory()));
    }

    private static WebSocketSession session(String uri,
                                            AtomicBoolean open,
                                            List<WebSocketMessage<?>> messages,
                                            CountDownLatch sent) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            sent.countDown();
            return null;
        }).when(session).sendMessage(any());
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any());
        return session;
    }
}
