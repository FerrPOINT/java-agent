package com.azhukov.agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BrowserControlDisabledControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BrowserControlDisabledController())
            .build();
    }

    @Test
    void browserControlRegisterReturnsHermesDisabledError() throws Exception {
        mockMvc.perform(post("/v1/browser-control/register"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Browser control is not enabled on this server."))
            .andExpect(jsonPath("$.error.code").value("browser_control_disabled"));
    }

    @Test
    void profilePrefixedBrowserControlRegisterMirrorsDisabledError() throws Exception {
        mockMvc.perform(post("/p/work/v1/browser-control/register"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Browser control is not enabled on this server."))
            .andExpect(jsonPath("$.error.code").value("browser_control_disabled"));
    }

    @Test
    void browserControlWebsocketReturnsPlainNotFoundWhenDisabled() throws Exception {
        mockMvc.perform(get("/v1/browser-control/ws"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(""));
    }

    @Test
    void artifactUploadReturnsHermesDisabledError() throws Exception {
        mockMvc.perform(post("/v1/artifacts/upload"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Browser control is not enabled on this server."))
            .andExpect(jsonPath("$.error.code").value("browser_control_disabled"));
    }

    @Test
    void profilePrefixedArtifactRoutesMirrorDisabledResponses() throws Exception {
        mockMvc.perform(post("/p/work/v1/artifacts/upload"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("browser_control_disabled"));

        mockMvc.perform(get("/p/work/v1/artifacts/download/{artifactId}", "art_123"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(""));
    }

    @Test
    void artifactDownloadReturnsPlainNotFoundWhenDisabled() throws Exception {
        mockMvc.perform(get("/v1/artifacts/download/{artifactId}", "art_123"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(""));
    }
}
