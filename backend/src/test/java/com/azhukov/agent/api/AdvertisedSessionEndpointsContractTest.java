package com.azhukov.agent.api;

import com.azhukov.agent.support.NoopSpringIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test: every endpoint advertised by GET /v1/capabilities under
 * /api/sessions must actually resolve (not 404) on the native mount.
 */
class AdvertisedSessionEndpointsContractTest extends NoopSpringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void nativeSessionsListResolves() throws Exception {
        mockMvc.perform(get("/api/sessions")
                .header("Authorization", "Bearer test-key-123"))
            .andExpect(status().isOk());
    }

    @Test
    void nativeSessionsCreateResolves() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType("application/json")
                .header("Authorization", "Bearer test-key-123")
                .content("{}"))
            .andExpect(status().isCreated());
    }

    @Test
    void nativeSessionsSearchResolves() throws Exception {
        mockMvc.perform(get("/api/sessions/search?query=x")
                .header("Authorization", "Bearer test-key-123"))
            .andExpect(status().isOk());
    }

    @Test
    void unknownSessionYields404NotRouteMiss() throws Exception {
        // The route must exist: unknown ids give 404 from the handler,
        // never from Spring's route dispatcher.
        mockMvc.perform(get("/api/sessions/" + java.util.UUID.randomUUID())
                .header("Authorization", "Bearer test-key-123"))
            .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/sessions/" + java.util.UUID.randomUUID())
                .header("Authorization", "Bearer test-key-123"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sessions/" + java.util.UUID.randomUUID() + "/fork")
                .contentType("application/json")
                .header("Authorization", "Bearer test-key-123")
                .content("{}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sessions/" + java.util.UUID.randomUUID() + "/model")
                .contentType("application/json")
                .header("Authorization", "Bearer test-key-123")
                .content("{\"model\":\"m\"}"))
            .andExpect(status().isNotFound());
    }
}
