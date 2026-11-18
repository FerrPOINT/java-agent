package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.*;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.security.*;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.*;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("slow")
class SessionSearchRuntimePostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired ToolRegistry registry;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;

    @Test
    void toolWorkerMustNotReadAnotherOwnersSession() {
        SessionEntity foreign = new SessionEntity();
        foreign.setUserId("another-owner");
        foreign.setTitle("Foreign session");
        foreign.setModelProvider("noop");
        foreign.setModelName("noop");
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        UUID foreignId = sessions.saveAndFlush(foreign).getId();
        try {
            var properties = new AgentProperties();
            var execution = new ToolExecutionService(registry, properties, new DefaultToolCallGuardrail(properties),
                new SecretRedactor(properties), new ToolResultClassifier(), new ToolOutputLimiter(properties), null);
            var session = Session.create("runtime-owner", "noop", "noop");
            UserContext.set("runtime-owner", UserContext.ROLE_USER);
            try {
                var result = execution.execute("session_search", "call-foreign",
                    "{\"session_id\":\"" + foreignId + "\"}", null, session);
                assertThat(result.success()).isFalse();
                assertThat(result.content()).contains("not found");
            } finally {
                UserContext.clear();
            }
        } finally {
            transaction.executeWithoutResult(status -> sessions.deleteById(foreignId));
        }
    }

    @Test
    void toolWorkerUsesSessionOwnerForDiscoveryAndScroll() throws Exception {
        SessionEntity foreign = new SessionEntity();
        foreign.setUserId("other-owner");
        foreign.setTitle("Private session");
        foreign.setModelProvider("noop");
        foreign.setModelName("noop");
        foreign.setSource("telegram");
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        UUID id = sessions.saveAndFlush(foreign).getId();
        MessageEntity message = new MessageEntity();
        message.setSessionId(id);
        message.setRole("user");
        message.setContent("private uniqueworkerquery");
        message.setTurnIndex(0);
        message.setCreatedAt(Instant.now());
        UUID messageId = messages.saveAndFlush(message).getId();
        try {
            var properties = new AgentProperties();
            var execution = new ToolExecutionService(registry, properties, new DefaultToolCallGuardrail(properties),
                new SecretRedactor(properties), new ToolResultClassifier(), new ToolOutputLimiter(properties), null);
            var owner = Session.create("runtime-owner", "noop", "noop");
            var search = execution.execute("session_search", "search", "{\"query\":\"uniqueworkerquery\"}", null, owner);
            assertThat(search.content()).doesNotContain("private uniqueworkerquery");
            var scroll = execution.execute("session_search", "scroll", "{\"session_id\":\"" + id
                + "\",\"around_message_id\":\"" + messageId + "\"}", null, owner);
            assertThat(scroll.success()).isFalse();
            assertThat(scroll.content()).doesNotContain("private uniqueworkerquery");
        } finally {
            transaction.executeWithoutResult(status -> {
                messages.deleteBySessionId(id);
                sessions.deleteById(id);
            });
        }
    }

    @Test
    void concurrentGatewayAndCentralWorkersRemainOwnerScoped() throws Exception {
        SessionEntity ownerA = new SessionEntity();
        ownerA.setUserId("owner-a"); ownerA.setTitle("A history"); ownerA.setModelProvider("noop");
        ownerA.setModelName("noop"); ownerA.setCreatedAt(Instant.now()); ownerA.setUpdatedAt(Instant.now());
        UUID a = sessions.saveAndFlush(ownerA).getId();
        SessionEntity ownerB = new SessionEntity();
        ownerB.setUserId("owner-b"); ownerB.setTitle("B history"); ownerB.setModelProvider("noop");
        ownerB.setModelName("noop"); ownerB.setCreatedAt(Instant.now()); ownerB.setUpdatedAt(Instant.now());
        UUID b = sessions.saveAndFlush(ownerB).getId();
        var properties = new AgentProperties();
        var execution = new ToolExecutionService(registry, properties, new DefaultToolCallGuardrail(properties),
            new SecretRedactor(properties), new ToolResultClassifier(), new ToolOutputLimiter(properties), null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var gateway = pool.submit(() -> {
                UserContext.set("gateway-admin", "admin");
                try {
                    start.await();
                    var current = Session.create("owner-a", "noop", "noop");
                    return List.of(execution.execute("session_search", "a-own", "{\"session_id\":\"" + a + "\"}", null, current),
                        execution.execute("session_search", "a-foreign", "{\"session_id\":\"" + b + "\"}", null, current));
                } finally { UserContext.clear(); }
            });
            var central = pool.submit(() -> {
                var proof = new com.pdlc.auth.HumanApplicationAccess("owner-b", "user", Set.of("java-agent"), 1, true,
                    Instant.now().plusSeconds(60), System.nanoTime() + 60_000_000_000L);
                UserContext.setCentral(proof);
                try {
                    start.await();
                    var current = Session.create("owner-b", "noop", "noop");
                    var own = execution.execute("session_search", "b-own", "{\"session_id\":\"" + b + "\"}", null, current);
                    var foreign = execution.execute("session_search", "b-foreign", "{\"session_id\":\"" + a + "\"}", null, current);
                    assertThat(UserContext.centralAccess()).isSameAs(proof);
                    return List.of(own, foreign);
                } finally { UserContext.clear(); }
            });
            start.countDown();
            for (var f : List.of(gateway, central)) {
                var pair = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(pair.get(0).success()).isTrue();
                assertThat(pair.get(1).success()).isFalse();
            }
        } finally {
            pool.shutdownNow();
            transaction.executeWithoutResult(status -> { sessions.deleteById(a); sessions.deleteById(b); });
        }
    }

    @Test
    void agentToolLoopReadsPreviousSessionAndHandsResultBackToModel() {
        SessionEntity previous = new SessionEntity();
        previous.setUserId("runtime-owner");
        previous.setTitle("Previous session");
        previous.setModelProvider("noop");
        previous.setModelName("noop");
        previous.setSource("telegram");
        previous.setCreatedAt(Instant.now());
        previous.setUpdatedAt(Instant.now());
        previous = sessions.saveAndFlush(previous);
        MessageEntity previousMessage = new MessageEntity();
        previousMessage.setSessionId(previous.getId());
        previousMessage.setRole("user");
        previousMessage.setContent("previous conclusion");
        previousMessage.setTurnIndex(0);
        previousMessage.setCreatedAt(Instant.now());
        messages.saveAndFlush(previousMessage);
        UUID previousId = previous.getId();
        try {

        var properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("session_search");
        properties.getCore().setMaxTurns(10);
        ContextEngine context = mock(ContextEngine.class);
        when(context.prepareContext(any(), any())).thenAnswer(inv -> new ArrayList<Message>(inv.getArgument(1)));
        var references = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(references.resolve(any())).thenReturn(List.of());
        when(references.loadContent(any())).thenReturn(Optional.empty());
        var calls = new AtomicInteger();
        var requests = new ArrayList<List<Message>>();
        com.azhukov.agent.core.client.ModelClient model = (input, tools, options) -> {
            requests.add(List.copyOf(input));
            int call = calls.getAndIncrement();
            if (call == 0) {
                return new ChatResponse("", List.of(new ToolCall("browse-session", "session_search", "{}")), "TOOL_EXECUTION");
            }
            if (call == 1) {
                assertThat(input).anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo(Role.TOOL);
                    assertThat(message.content()).contains(previousId.toString());
                });
                return new ChatResponse("", List.of(new ToolCall("call-session", "session_search",
                    "{\"session_id\":\"" + previousId + "\"}")), "TOOL_EXECUTION");
            }
            return ChatResponse.text("continued", "STOP");
        };
        var execution = new ToolExecutionService(registry, properties, new DefaultToolCallGuardrail(properties),
            new SecretRedactor(properties), new ToolResultClassifier(), new ToolOutputLimiter(properties), null);
        var runtime = new DefaultAgentRuntime(model, registry, execution, new DefaultPromptBuilder(properties, registry), context,
            mock(MemoryProvider.class), mock(SkillManager.class), new DefaultIterationBudget(properties),
            new MessageSanitizer(new SecretRedactor(properties)), references, properties,
            new UserInputSanitizer(), new DefaultToolCallGuardrail(properties), new TurnStateManager(), null,
            new InterruptToken(), null, new SteerBuffer(), new ErrorClassifier(), null,
            new ApprovalQueue(), null, null, new TokenEstimator(), new ToolResultFormatter(), null, null);
        var result = runtime.runTurn(Session.create("runtime-owner", "noop", "noop"), "continue the previous chat");
        assertThat(result.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.ASSISTANT);
            assertThat(m.content()).isEqualTo("continued");
        });
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2)).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.TOOL);
            assertThat(m.content()).contains("previous conclusion");
        });
        } finally {
            transaction.executeWithoutResult(status -> {
                messages.deleteBySessionId(previousId);
                sessions.deleteById(previousId);
            });
        }
    }
}
