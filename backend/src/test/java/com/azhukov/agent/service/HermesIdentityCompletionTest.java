package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.azhukov.agent.core.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdlc.auth.HumanApplicationAccess;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real worker handoff, with completion latches rather than sleeps or a renewed proof. */
@ExtendWith(MockitoExtension.class)
class HermesIdentityCompletionTest {
    private static final UUID SESSION = UUID.fromString("ecfd13fe-ed9c-4db4-a617-e5908709b9df");
    private final AgentRuntimeService runtime = mock(AgentRuntimeService.class);
    private final HermesSessionStreamingService service = new HermesSessionStreamingService(runtime, new ObjectMapper());

    @AfterEach
    void close() {
        UserContext.clear();
        NativeIdentityOrigin.clear();
        service.shutdown();
    }

    @Test
    void anExpiredCapturedProofCannotStartTheAgentOrEmitAnyPayload() throws Exception {
        var proof = new HumanApplicationAccess("synthetic-central-owner", "user", Set.of("java-agent"), 1, true,
                Instant.now().minusSeconds(1), System.nanoTime() - 1);
        UserContext.setCentral(proof);
        var emitter = new ObservedEmitter();
        var completed = new CountDownLatch(1);
        var calls = new AtomicInteger();

        service.streamTurn(request(), SESSION, Map.of(), emitter, () -> {
            calls.incrementAndGet();
            completed.countDown();
            throw new IllegalStateException("synthetic completion failure");
        });
        UserContext.clear();

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(emitter.completions.get()).isGreaterThanOrEqualTo(1);
        assertThat(emitter.events).isEmpty();
        verifyNoInteractions(runtime);
        assertThat(UserContext.centralAccess()).isNull();
    }

    @Test
    void anIncompleteTurnCannotEmitSuccessAndStillReleasesItsCompletionLease() throws Exception {
        when(runtime.runTurn(any(ChatRequest.class)))
                .thenReturn(new ChatResponseDto(null, null, List.of(), false));
        var emitter = new ObservedEmitter();
        var completed = new CountDownLatch(1);

        service.streamTurn(request(), SESSION, null, emitter, completed::countDown);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.events).hasSize(4);
        assertThat(String.join("", emitter.events)).contains("event:error", "agent run did not complete", "event:done")
                .doesNotContain("event:run.completed", "event:assistant.completed");
        assertThat(emitter.completions.get()).isEqualTo(1);
        verify(runtime, times(1)).runTurn(any(ChatRequest.class));
    }

    @Test
    void aStorageRefusalOnTheWorkerKeepsNativeOriginButNeverEmitsACompletedTurn() throws Exception {
        var origin = new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.USER_API_KEY, "native-owner", false);
        NativeIdentityOrigin.bind(origin);
        UserContext.set("native-owner", "user");
        when(runtime.runTurn(any(ChatRequest.class))).thenAnswer(call -> {
            assertThat(NativeIdentityOrigin.current()).isEqualTo(origin);
            assertThat(UserContext.scopeUserId()).isEqualTo("native-owner");
            throw new SecurityException("synthetic storage fence");
        });
        var emitter = new ObservedEmitter();
        var completed = new CountDownLatch(1);

        service.streamTurn(request(), SESSION, Map.of(), emitter, completed::countDown);
        NativeIdentityOrigin.clear();
        UserContext.clear();

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(String.join("", emitter.events)).contains("event:error", "event:done")
                .doesNotContain("event:run.completed", "event:assistant.completed");
        assertThat(emitter.completions.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDisconnectedEmitterCannotLeakTheTurnCompletionLease(boolean alreadyCompleted) throws Exception {
        when(runtime.runTurn(any(ChatRequest.class)))
                .thenReturn(new ChatResponseDto(SESSION, "synthetic answer", List.of(), true));
        ObservedEmitter emitter = new ObservedEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                if (alreadyCompleted) throw new IllegalStateException("synthetic completed emitter");
                throw new IOException("synthetic disconnected emitter");
            }
        };
        var completed = new CountDownLatch(1);

        service.streamTurn(request(), SESSION, Map.of(), emitter, completed::countDown);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.completions.get()).isEqualTo(1);
        verify(runtime, times(1)).runTurn(any(ChatRequest.class));
    }

    @Test
    void aStoppedWorkerRejectsTheTurnButStillReleasesItsCompletionLeaseExactlyOnce() {
        service.shutdown();
        var completed = new AtomicInteger();

        assertThatThrownBy(() -> service.streamTurn(request(), SESSION, Map.of(), new ObservedEmitter(),
                completed::incrementAndGet)).isInstanceOf(RejectedExecutionException.class);
        assertThat(completed.get()).isEqualTo(1);
        verifyNoInteractions(runtime);
    }

    private static ChatRequest request() {
        return ChatRequest.simple(SESSION, "synthetic request", null, 10_000L);
    }

    private static class ObservedEmitter extends SseEmitter {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger completions = new AtomicInteger();

        ObservedEmitter() {
            super(10_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            var rendered = new StringBuilder();
            for (Object part : builder.build()) {
                Object data = ReflectionTestUtils.getField(part, "data");
                if (data != null) rendered.append(data);
            }
            events.add(rendered.toString());
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
        }
    }
}
