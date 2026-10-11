package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.service.AgentRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.persistence.PostgresActiveServicesTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@Tag("slow")
class AgentControllerIntegrationTest extends PostgresActiveServicesTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentRuntimeService agentRuntimeService;

    @Test
    void chatReturnsNoOpResponse() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "hello", null, null);

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("NoOp response: hello"))
            .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    @Test
    void chatWithEmptyMessageReturnsValidationError() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "", null, null);

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
    }
}
