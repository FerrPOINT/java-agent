package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.AgentUserEntity;
import com.azhukov.agent.persistence.entity.UserApiKeyEntity;
import com.azhukov.agent.service.UserAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserAdminController contract tests: admin-only enforcement, key lifecycle
 * (raw key returned once, hashes never exposed), revoke semantics.
 */
@ExtendWith(MockitoExtension.class)
class UserAdminControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private UserAccessService userAccessService;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserAdminController(userAccessService)).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private AgentUserEntity user(String id, String role) {
        AgentUserEntity u = new AgentUserEntity();
        u.setId(id);
        u.setUsername("u-" + id);
        u.setDisplayName("User " + id);
        u.setRole(role);
        u.setCreatedAt(Instant.now());
        return u;
    }

    @Test
    void adminCanCreateUser() throws Exception {
        UserContext.set("admin-1", UserContext.ROLE_ADMIN);
        when(userAccessService.createUser("alice", "Alice", "user")).thenReturn(user("u-alice", "user"));

        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("username", "alice", "displayName", "Alice", "role", "user"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("u-alice"))
            .andExpect(jsonPath("$.username").value("u-u-alice"))
            .andExpect(jsonPath("$.role").value("user"));
    }

    @Test
    void nonAdminGets403OnCreate() throws Exception {
        UserContext.set("user-42", UserContext.ROLE_USER);

        mockMvc.perform(post("/api/v1/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "eve"))))
            .andExpect(status().isForbidden());

        verify(userAccessService, never()).createUser(any(), any(), any());
    }

    @Test
    void nonAdminGets403OnList() throws Exception {
        UserContext.set("user-42", UserContext.ROLE_USER);

        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isForbidden());

        verify(userAccessService, never()).listUsers();
    }

    @Test
    void adminCanIssueKeyAndRawKeyComesBackExactlyOnce() throws Exception {
        UserContext.set("admin-1", UserContext.ROLE_ADMIN);
        UUID keyId = UUID.randomUUID();
        when(userAccessService.issueApiKey("u-alice", "laptop"))
            .thenReturn(new UserAccessService.IssuedApiKey(keyId, "agk_rawsecret", "u-alice", "user"));

        mockMvc.perform(post("/api/v1/admin/users/u-alice/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("label", "laptop"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(keyId.toString()))
            .andExpect(jsonPath("$.rawKey").value("agk_rawsecret"));

        // Key listing must never contain the raw key or the hash
        UserApiKeyEntity key = new UserApiKeyEntity();
        key.setId(keyId);
        key.setUserId("u-alice");
        key.setKeyHash("deadbeef");
        key.setLabel("laptop");
        key.setCreatedAt(Instant.now());
        when(userAccessService.listApiKeys("u-alice")).thenReturn(List.of(key));

        String body = mockMvc.perform(get("/api/v1/admin/users/u-alice/keys"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("laptop");
        assertThat(body).doesNotContain("agk_rawsecret");
        assertThat(body).doesNotContain("deadbeef");
    }

    @Test
    void revokeDeletesAndReports() throws Exception {
        UserContext.set("admin-1", UserContext.ROLE_ADMIN);
        UUID keyId = UUID.randomUUID();
        when(userAccessService.revokeApiKey(keyId)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/admin/users/keys/" + keyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(true));

        when(userAccessService.revokeApiKey(keyId)).thenReturn(false);
        mockMvc.perform(delete("/api/v1/admin/users/keys/" + keyId))
            .andExpect(status().isNotFound());
    }

    @Test
    void issueKeyForUnknownUserIs404() throws Exception {
        UserContext.set("admin-1", UserContext.ROLE_ADMIN);
        when(userAccessService.issueApiKey(eq("ghost"), any()))
            .thenThrow(new IllegalArgumentException("User not found: ghost"));

        mockMvc.perform(post("/api/v1/admin/users/ghost/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
    }
}
