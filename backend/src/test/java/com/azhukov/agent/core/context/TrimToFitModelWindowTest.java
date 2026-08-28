package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Regression (2026-08-28): trimToFit used the static config max-tokens (16K)
 * instead of the model window — a >64K-char history was gutted down to
 * [system, orphan-tool], the sanitizer dropped the orphan, and providers
 * received a user-less request → "The messages parameter is illegal".
 */
class TrimToFitModelWindowTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getContext().setMaxTokens(16_000);        // legacy default
        p.getContext().setTargetTokens(12_000);
        p.getContext().setThresholdPercent(0.50);
        p.getContext().setProtectFirstN(1);
        p.getContext().setProtectLastN(4);
        return p;
    }

    private List<Message> bulkyHistory() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system prompt"));
        msgs.add(Message.user("первый вопрос"));
        msgs.add(Message.assistant("ответ", 0));
        // bulky tool-run: assistant(tool_calls) + 3 tool results (как в реальной сессии)
        msgs.add(Message.assistantWithToolCalls("", List.of(
            new com.azhukov.agent.core.model.ToolCall("call_a", "session_search", "{}")), 0));
        msgs.add(Message.toolResult("call_a", "x".repeat(19_583), 0));
        msgs.add(Message.toolResult("call_a", "y".repeat(11_051), 0));
        msgs.add(Message.toolResult("call_a", "z".repeat(8_640), 0));
        msgs.add(Message.assistant("итог", 1));
        msgs.add(Message.user("ещё раз проверь сессии"));
        return msgs;
    }

    @Test
    void realModelWindowKeepsToolPairsIntact() throws Exception {
        MemoryProvider mp = mock(MemoryProvider.class);
        SkillManager sm = mock(SkillManager.class);
        MessageRepository mr = mock(MessageRepository.class);
        var cc = mock(com.azhukov.agent.core.context.ContextCompressor.class);
        ModelMetadataService meta = mock(ModelMetadataService.class);
        doReturn(202_752).when(meta).detectContextLength(any());
        doReturn(new ModelMetadataService.ModelMetadata("m", "m", 202_752, 4))
            .when(meta).getMetadata(any());

        DefaultContextEngine engine = new DefaultContextEngine(mp, sm, mr, cc, props(), null, meta);
        java.lang.reflect.Field f = DefaultContextEngine.class.getDeclaredField("contextLength");
        f.setAccessible(true);
        f.set(engine, 202_752);

        List<Message> result = (List<Message>) trimToFit.invoke(engine, new Object[]{bulkyHistory()});
        // Полная история ~40K chars ≈ 10K tokens — влезает в окно 202K:
        // НИЧЕГО не должно триммиться, tool-пары целы.
        assertThat(result).hasSize(9);
        assertThat(result.stream().filter(m -> m.role() == com.azhukov.agent.core.model.Role.TOOL).count())
            .as("все tool results на месте").isEqualTo(3);
        assertThat(result.stream().filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER).count())
            .as("user-ходы сохранены").isEqualTo(2);
    }

    @Test
    void legacy16KConfigWouldHaveGuttedIt() throws Exception {
        // Контроль теста: без metadata (contextLength=0) старое поведение обрезало бы
        // историю до 2 сообщений — фикс должен использовать хотя бы конфиг-максимум.
        MemoryProvider mp = mock(MemoryProvider.class);
        SkillManager sm = mock(SkillManager.class);
        MessageRepository mr = mock(MessageRepository.class);
        var cc = mock(com.azhukov.agent.core.context.ContextCompressor.class);

        DefaultContextEngine engine = new DefaultContextEngine(mp, sm, mr, cc, props(), null, null);
        List<Message> result = (List<Message>) trimToFit.invoke(engine, new Object[]{bulkyHistory()});
        // Даже в legacy-режиме тримм не должен оставлять запрос без user-сообщения:
        long userCount = result.stream().filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER).count();
        assertThat(userCount).as("user-ход обязан выжить при любом тримме").isGreaterThanOrEqualTo(1);
    }

    private static final java.lang.reflect.Method trimToFit;
    static {
        try {
            trimToFit = DefaultContextEngine.class.getDeclaredMethod("trimToFit", List.class);
            trimToFit.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
