package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.EventService;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventsControllerTest {

    @TempDir
    private Path tempDir;

    private EventService eventService;
    private ProfileService profileService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        profileService = new ProfileService(properties, new RuntimeConfigService());
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        eventService = new EventService(10);
        mockMvc = MockMvcBuilders.standaloneSetup(new EventsController(eventService, profileService)).build();
    }

    @Test
    void bareEventsEndpointReplaysAllProfiles() throws Exception {
        eventService.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "default"));
        eventService.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "work"));

        mockMvc.perform(get("/api/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("event_list"))
            .andExpect(jsonPath("$.profile").value("all"))
            .andExpect(jsonPath("$.events.length()").value(2))
            .andExpect(jsonPath("$.next_cursor").value(2))
            .andExpect(jsonPath("$.latest_cursor").value(2));
    }

    @Test
    void afterCursorReturnsOnlyNewerEvents() throws Exception {
        eventService.publish("delegate.created", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());
        eventService.publish("delegate.started", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());

        mockMvc.perform(get("/api/events").param("after", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.events[0].type").value("delegate.started"))
            .andExpect(jsonPath("$.replay_after").value(1))
            .andExpect(jsonPath("$.next_cursor").value(2));
    }

    @Test
    void profilePrefixScopesReplayToKnownProfile() throws Exception {
        eventService.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "default"));
        eventService.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "work"));

        mockMvc.perform(get("/p/work/api/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.events[0].profile").value("work"))
            .andExpect(jsonPath("$.events[0].payload.name").value("work"));
    }

    @Test
    void queryProfileScopesBareEndpoint() throws Exception {
        eventService.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "default"));
        eventService.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "work"));

        mockMvc.perform(get("/api/events").param("profile", "work"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.events[0].payload.name").value("work"));
    }

    @Test
    void invalidProfileFailsClosed() throws Exception {
        mockMvc.perform(get("/p/bad.profile/api/events"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownProfileReturnsNotFound() throws Exception {
        mockMvc.perform(get("/p/missing/api/events"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: missing"));
    }

    @Test
    void invalidCursorAndLimitReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/events").param("after", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("cursor must be greater than or equal to 0"));

        mockMvc.perform(get("/api/events").param("limit", "501"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("limit must be between 1 and 500"));
    }
}
