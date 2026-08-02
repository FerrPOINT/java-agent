package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerUnitTest {

    private MockMvc mockMvc;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        when(properties.getName()).thenReturn("test-agent");
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(properties)).build();
    }

    @Test
    void healthReturnsUpWithAgentName() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.name").value("test-agent"));
    }

    @Test
    void healthReflectsConfiguredName() {
        when(properties.getName()).thenReturn("custom-java-agent");
        var response = new HealthController(properties).health();
        assertThat(response).containsEntry("status", "UP").containsEntry("name", "custom-java-agent");
    }
}
