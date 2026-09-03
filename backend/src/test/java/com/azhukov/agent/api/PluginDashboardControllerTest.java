package com.azhukov.agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PluginDashboardControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PluginDashboardController()).build();
    }

    @Test
    void pluginNamespaceReturnsExplicitUnsupportedShape() throws Exception {
        mockMvc.perform(get("/api/plugins/kanban/board"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("plugin API routes are not implemented in the Java port"))
            .andExpect(jsonPath("$.error").value("plugin API routes are not implemented in the Java port"))
            .andExpect(jsonPath("$.plugin").value("kanban"))
            .andExpect(jsonPath("$.path").value("/board"));
    }

    @Test
    void pluginNamespaceSupportsMutatingMethodsWithoutFallingThroughTo404() throws Exception {
        mockMvc.perform(post("/api/plugins/local-task/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.plugin").value("local-task"))
            .andExpect(jsonPath("$.path").value("/events"));
    }

    @Test
    void pluginNamespaceRejectsInvalidPluginIdAndTraversalPath() throws Exception {
        mockMvc.perform(get("/api/plugins/bad.id/board"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid plugin id"));

        mockMvc.perform(get("/api/plugins/kanban/%2e%2e/board"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid plugin path"));
    }

    @Test
    void dashboardPluginCatalogAndHubReturnEmptyCompatibleShapes() throws Exception {
        mockMvc.perform(get("/api/dashboard/plugins"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/dashboard/plugins/rescan"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(get("/api/dashboard/plugins/hub"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plugins").isArray())
            .andExpect(jsonPath("$.orphan_dashboard_plugins").isArray())
            .andExpect(jsonPath("$.providers.memory_provider").value("builtin"))
            .andExpect(jsonPath("$.providers.memory_options[0].name").value("builtin"))
            .andExpect(jsonPath("$.providers.memory_options[0].status").value("ready"))
            .andExpect(jsonPath("$.providers.context_engine").value("compressor"))
            .andExpect(jsonPath("$.providers.context_options[0].name").value("compressor"));
    }

    @Test
    void dashboardAgentPluginMutationsReturnExplicitUnsupportedShapes() throws Exception {
        mockMvc.perform(post("/api/dashboard/agent-plugins/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"https://example.test/plugin.git\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("agent plugin management is not implemented in the Java port"))
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.action").value("install"))
            .andExpect(jsonPath("$.name").value("https://example.test/plugin.git"));

        mockMvc.perform(post("/api/dashboard/agent-plugins/team/tools/enable"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("agent plugin management is not implemented in the Java port"))
            .andExpect(jsonPath("$.action").value("enable"))
            .andExpect(jsonPath("$.name").value("team/tools"));

        mockMvc.perform(post("/api/dashboard/agent-plugins/team/tools/disable"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.action").value("disable"))
            .andExpect(jsonPath("$.name").value("team/tools"));

        mockMvc.perform(post("/api/dashboard/agent-plugins/team/tools/update"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.action").value("update"))
            .andExpect(jsonPath("$.name").value("team/tools"));

        mockMvc.perform(delete("/api/dashboard/agent-plugins/team/tools"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.action").value("remove"))
            .andExpect(jsonPath("$.name").value("team/tools"));
    }

    @Test
    void dashboardPluginVisibilityAndAssetsAvoidGeneric404s() throws Exception {
        mockMvc.perform(post("/api/dashboard/plugins/team/tools/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hidden\":true}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("dashboard plugin visibility is not implemented in the Java port"))
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.name").value("team/tools"))
            .andExpect(jsonPath("$.hidden").value(true));

        mockMvc.perform(post("/api/dashboard/plugins/team/%2e%2e/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hidden\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid plugin name"));

        mockMvc.perform(get("/dashboard-plugins/kanban/app.js"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Plugin not found"));

        mockMvc.perform(get("/dashboard-plugins/kanban/%2e%2e/app.js"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid plugin path"));
    }
}
