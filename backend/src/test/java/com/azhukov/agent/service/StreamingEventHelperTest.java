package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.StreamContext;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link StreamingEventHelper} — the SSE event-sending
 * and metadata-formatting collaborator extracted from AgentStreamingService.
 */
class StreamingEventHelperTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ObjectMapper objectMapper;
    private ToolResultFormatter toolResultFormatter;
    private ModelMetadataService modelMetadataService;
    private AgentProperties properties;
    private UsageTracker usageTracker;
    private RuntimeConfigService runtimeConfigService;
    private StreamingEventHelper helper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        toolResultFormatter = new ToolResultFormatter();
        modelMetadataService = new ModelMetadataService();
        properties = new AgentProperties();
        properties.getContext().setMaxTokens(8192);
        properties.getModel().setModelName("moonshotai/kimi-k2.6");
        usageTracker = mock(UsageTracker.class);
        runtimeConfigService = new RuntimeConfigService();

        helper = new StreamingEventHelper(
            objectMapper, toolResultFormatter, modelMetadataService,
            properties, usageTracker, runtimeConfigService);
    }

    // ── send() ─────────────────────────────────────────────────────────────

    @Test
    void sendWritesEventToEmitter() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        StreamEvent event = new StreamEvent("token", "hello", null, null);

        helper.send(emitter, event, ctx);

        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0).name).isEqualTo("token");
        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.token()).isEqualTo("hello");
        assertThat(ctx.isClientDisconnected()).isFalse();
    }

    @Test
    void sendSkipsWhenClientDisconnected() {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        ctx.markDisconnected();

        helper.send(emitter, new StreamEvent("token", "x", null, null), ctx);

        assertThat(emitter.events).isEmpty();
    }

    @Test
    void sendMarksDisconnectedOnIOException() {
        // Use an emitter that throws IOException on send
        SseEmitter emitter = new SseEmitter(30_000L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("connection broken");
            }
        };
        StreamContext ctx = new StreamContext();

        helper.send(emitter, new StreamEvent("token", "x", null, null), ctx);

        assertThat(ctx.isClientDisconnected()).isTrue();
    }

    @Test
    void sendSwallowsIllegalStateException() {
        SseEmitter emitter = new SseEmitter(30_000L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IllegalStateException("already completed");
            }
        };
        StreamContext ctx = new StreamContext();

        // Should not throw
        helper.send(emitter, new StreamEvent("token", "x", null, null), ctx);
        assertThat(ctx.isClientDisconnected()).isFalse();
    }

    // ── safeCompleteWithError() ────────────────────────────────────────────

    @Test
    void safeCompleteWithErrorCompletesNormally() {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        helper.safeCompleteWithError(emitter, new RuntimeException("boom"));

        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    @Test
    void safeCompleteWithErrorSwallowsAlreadyCompletedException() {
        SseEmitter emitter = new SseEmitter(30_000L) {
            @Override
            public void complete() {
                throw new IllegalStateException("already completed");
            }
        };

        // Should not throw
        helper.safeCompleteWithError(emitter, new RuntimeException("boom"));
    }

    // ── formatResultPreview() ───────────────────────────────────────────────

    @Test
    void formatResultPreviewReturnsShortContentAsIs() {
        ToolResult result = ToolResult.ok("short result");
        assertThat(helper.formatResultPreview(result)).isEqualTo("short result");
    }

    @Test
    void formatResultPreviewTruncatesLongContent() {
        String longContent = "x".repeat(600);
        ToolResult result = ToolResult.ok(longContent);
        String preview = helper.formatResultPreview(result);
        assertThat(preview).hasSize(503); // 500 + "..."
        assertThat(preview).endsWith("...");
        assertThat(preview.substring(0, 500)).isEqualTo("x".repeat(500));
    }

    @Test
    void formatResultPreviewFormatsErrorResult() {
        ToolResult result = ToolResult.fail("disk full");
        // Structured failure envelope (Hermes parity): failed results with no
        // content surface as {"success":false,"error":...} JSON.
        assertThat(helper.formatResultPreview(result))
            .isEqualTo("{\"success\":false,\"error\":\"disk full\"}");
    }

    @Test
    void formatResultPreviewAtExactly500CharsIsNotTruncated() {
        String exactContent = "y".repeat(500);
        ToolResult result = ToolResult.ok(exactContent);
        assertThat(helper.formatResultPreview(result)).isEqualTo(exactContent);
    }

    @Test
    void formatResultPreviewAt501CharsIsTruncated() {
        String content = "y".repeat(501);
        ToolResult result = ToolResult.ok(content);
        String preview = helper.formatResultPreview(result);
        assertThat(preview).hasSize(503);
        assertThat(preview).endsWith("...");
    }

    // ── sendMetadataEvent() ─────────────────────────────────────────────────

    @Test
    void sendMetadataEventEmitsMetadataWithModelAndContext() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "moonshotai/kimi-k2.6");

        helper.sendMetadataEvent(emitter, session, ctx);

        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0).name).isEqualTo("metadata");
        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.type()).isEqualTo("metadata");
        assertThat(sent.modelUsed()).isEqualTo("moonshotai/kimi-k2.6");
        assertThat(sent.contextLength()).isEqualTo(262_144); // kimi context window
        assertThat(sent.sessionId()).isEqualTo(session.id());
    }

    @Test
    void sendMetadataEventUsesLastInputTokensWhenProvided() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "moonshotai/kimi-k2.6");

        helper.sendMetadataEvent(emitter, session, ctx, 5000);

        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.contextTokens()).isEqualTo(5000);
    }

    @Test
    void sendMetadataEventFallsBackToUsageTrackerForContextTokens() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "moonshotai/kimi-k2.6");
        when(usageTracker.getSessionUsage(session.id()))
            .thenReturn(new UsageDto(session.id(), 3, 1500));

        helper.sendMetadataEvent(emitter, session, ctx); // lastInputTokens = 0 → fallback

        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.contextTokens()).isEqualTo(1500);
    }

    @Test
    void sendMetadataEventFallsBackToMaxTokensWhenContextLengthUndetectable() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "some-unknown-model");

        helper.sendMetadataEvent(emitter, session, ctx);

        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        // Unknown model → detectContextLength returns default (likely >0 but if 0, falls back to maxTokens)
        // ModelMetadataService.DETECT_DEFAULT is typically 32768, so contextLength should be >0
        assertThat(sent.contextLength()).isGreaterThan(0);
    }

    @Test
    void sendMetadataEventUsesModelOverrideFromSessionMetadata() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "original-model")
            .withMetadata("modelOverride", "gpt-4o");

        helper.sendMetadataEvent(emitter, session, ctx);

        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.modelUsed()).isEqualTo("gpt-4o");
    }

    @Test
    void sendMetadataEventUsesRuntimeConfigOverrideWhenNoSessionModel() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        StreamContext ctx = new StreamContext();
        runtimeConfigService.setModelOverride("override-model");
        Session session = Session.create("user-1", "openai-compatible", "");

        helper.sendMetadataEvent(emitter, session, ctx);

        StreamEvent sent = objectMapper.readValue(emitter.events.get(0).data, StreamEvent.class);
        assertThat(sent.modelUsed()).isEqualTo("override-model");
    }

    @Test
    void sendMetadataEventSwallowsExceptionsGracefully() {
        // Use an emitter that throws on send → the method should catch and log, not throw
        SseEmitter emitter = new SseEmitter(30_000L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("broken");
            }
        };
        StreamContext ctx = new StreamContext();
        Session session = Session.create("user-1", "openai-compatible", "kimi-k2.6");

        // Should not throw — exception is caught internally
        helper.sendMetadataEvent(emitter, session, ctx);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static class CollectingEmitter extends SseEmitter {
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        CollectingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(new SseEvent(builder));
        }

        @Override
        public void complete() {
            this.completed.set(true);
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            this.error.set(ex);
            super.completeWithError(ex);
        }
    }

    private static class SseEvent {
        final String id;
        final String name;
        final String data;

        SseEvent(SseEmitter.SseEventBuilder builder) {
            try {
                Set<?> dataWithMediaTypes = builder.build();
                StringBuilder payload = new StringBuilder();
                for (Object dwmt : dataWithMediaTypes) {
                    Field dataField = getField(dwmt.getClass(), "data");
                    Object dataValue = dataField.get(dwmt);
                    if (dataValue != null) {
                        payload.append(dataValue.toString());
                    }
                }
                String rendered = payload.toString();
                this.name = parseEventName(rendered);
                this.data = extractData(rendered);
                this.id = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String parseEventName(String rendered) {
            if (rendered == null) return null;
            int eventIdx = rendered.indexOf("event:");
            if (eventIdx >= 0) {
                int nl = rendered.indexOf('\n', eventIdx);
                return rendered.substring(eventIdx + 6, nl >= 0 ? nl : rendered.length()).trim();
            }
            return null;
        }

        private static String extractData(String rendered) {
            if (rendered == null) return null;
            int dataIdx = rendered.lastIndexOf("data:");
            if (dataIdx >= 0) {
                return rendered.substring(dataIdx + 5).trim();
            }
            return rendered;
        }

        private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }
    }
}