package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ClarifyGatewayStore;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentClarifyControllerTest {

    private ClarifyGatewayStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new ClarifyGatewayStore();
        AgentChatController controller = new AgentChatController(
            Mockito.mock(AgentRuntimeService.class), Mockito.mock(AgentStreamingService.class),
            Mockito.mock(MemoryProvider.class), Mockito.mock(SkillManager.class),
            Mockito.mock(TtsService.class), Mockito.mock(TranscriptionService.class),
            new SteerBuffer(), new InterruptToken(), null,
            Mockito.mock(com.azhukov.agent.persistence.repository.BackgroundJobRepository.class),
            null, null, null, new ApprovalQueue(), Mockito.mock(AgentProperties.class), null,
            Mockito.mock(ToolRegistry.class));
        ReflectionTestUtils.setField(controller, "clarifyStoreProviderField", provider(store));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void otherThenTypedCustomResponseResolvesTheSamePendingEntry() throws Exception {
        var pending = store.register("session-1", "Deploy where?", List.of("dev", "prod"), false);

        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/{id}/custom", pending.clarifyId())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.armed").value(true));

        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"a bespoke environment\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("resolved"));

        assertThat(pending.future()).isCompletedWithValue("a bespoke environment");
        assertThat(store.pendingForSession("session-1")).isNull();
    }

    @Test
    void customResponseCannotBeArmedForAnotherSession() throws Exception {
        var pending = store.register("session-1", "Deploy where?", List.of("dev", "prod"), false);

        mockMvc.perform(post("/api/v1/agent/session/session-2/clarify/{id}/custom", pending.clarifyId())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.armed").value(false));

        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"a bespoke environment\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("rejected_prose"));
    }

    @Test
    void resolveCannotCompleteAnotherSessionsPendingClarify() throws Exception {
        var pending = store.register("session-1", "Deploy where?", List.of("dev", "prod"), false);

        mockMvc.perform(post("/api/v1/agent/session/session-2/clarify/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clarifyId\":\"" + pending.clarifyId() + "\",\"response\":\"prod\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolved").value(false));

        assertThat(pending.future()).isNotDone();
        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clarifyId\":\"" + pending.clarifyId() + "\",\"response\":\"prod\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolved").value(true));
        assertThat(pending.future()).isCompletedWithValue("prod");
    }

    @Test
    void invalidNumericTextKeepsTheChoicePromptArmedForRetry() throws Exception {
        var pending = store.register("session-1", "Deploy where?", List.of("dev", "prod"), false);

        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("rejected_selection"));

        assertThat(pending.future()).isNotDone();
        assertThat(store.pendingForSession("session-1").clarifyId()).isEqualTo(pending.clarifyId());
    }

    @Test
    void explicitClarifyIdSelectsItsOwnPendingEntryInsteadOfTheOldestBatchQuestion() throws Exception {
        var first = store.register("session-1", "First?", List.of("one", "two"), false);
        var second = store.register("session-1", "Second?", List.of("dev", "prod"), false);
        assertThat(store.armCustomResponse("session-1", second.clarifyId())).isTrue();

        mockMvc.perform(post("/api/v1/agent/session/session-1/clarify/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clarifyId\":\"" + second.clarifyId() + "\",\"text\":\"a bespoke environment\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("resolved"));

        assertThat(first.future()).isNotDone();
        assertThat(second.future()).isCompletedWithValue("a bespoke environment");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
