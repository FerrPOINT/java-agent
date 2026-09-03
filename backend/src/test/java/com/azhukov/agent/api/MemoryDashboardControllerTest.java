package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryDashboardControllerTest {

    @TempDir
    private Path tempDir;

    private InMemoryProvider memoryProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memoryProvider = new InMemoryProvider("builtin");
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryDashboardController(memoryProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void statusReturnsHermesDashboardShapeForBuiltinMemory() throws Exception {
        memoryProvider.memory.addAll(List.of("first memory", "second memory"));
        memoryProvider.user.add("profile fact");

        mockMvc.perform(get("/api/memory").param("profile", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(""))
            .andExpect(jsonPath("$.providers[0].name").value("builtin"))
            .andExpect(jsonPath("$.providers[0].configured").value(true))
            .andExpect(jsonPath("$.providers[0].available").value(true))
            .andExpect(jsonPath("$.providers[0].status").value("ready"))
            .andExpect(jsonPath("$.builtin_files.memory").value("first memory\n\u00a7\nsecond memory".length()))
            .andExpect(jsonPath("$.builtin_files.user").value("profile fact".length()));
    }

    @Test
    void selectProviderAcceptsBuiltinAliasesAndRejectsUnknownProviders() throws Exception {
        mockMvc.perform(put("/api/memory/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"built-in\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.active").value(""));

        mockMvc.perform(put("/api/memory/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"no-such-provider-xyz\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown memory provider 'no-such-provider-xyz'."));
    }

    @Test
    void resetUserClearsOnlyUserStoreAndReturnsDeletedFileName() throws Exception {
        memoryProvider.memory.add("memory fact");
        memoryProvider.user.add("profile fact");

        mockMvc.perform(post("/api/memory/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"user\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.deleted[0]").value("USER.md"));

        assertThat(memoryProvider.memory).containsExactly("memory fact");
        assertThat(memoryProvider.user).isEmpty();
    }

    @Test
    void resetAllDefaultsWhenBodyIsMissing() throws Exception {
        memoryProvider.memory.add("memory fact");
        memoryProvider.user.add("profile fact");

        mockMvc.perform(post("/api/memory/reset"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted[0]").value("MEMORY.md"))
            .andExpect(jsonPath("$.deleted[1]").value("USER.md"));

        assertThat(memoryProvider.memory).isEmpty();
        assertThat(memoryProvider.user).isEmpty();
    }

    @Test
    void resetRejectsInvalidTarget() throws Exception {
        mockMvc.perform(post("/api/memory/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"bogus\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("target must be all, memory, or user"));
    }

    @Test
    void statusAndResetHonorProfileScopeLikeRuntimeMemory() throws Exception {
        ProfileService profileService = profileService();
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryDashboardController(memoryProvider, profileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        String workUserId = MemoryScope.userId(AgentProperties.DEFAULT_USER_ID, "work");
        memoryProvider.memory.add("default memory");
        memoryProvider.user.add("default user");
        memoryProvider.memoryFor(workUserId).addAll(List.of("work memory one", "work memory two"));
        memoryProvider.userFor(workUserId).add("work user");

        mockMvc.perform(get("/api/memory").param("profile", "work"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.builtin_files.memory")
                .value("work memory one\n\u00a7\nwork memory two".length()))
            .andExpect(jsonPath("$.builtin_files.user").value("work user".length()));

        mockMvc.perform(post("/api/memory/reset")
                .param("profile", "work")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"memory\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted[0]").value("MEMORY.md"));

        assertThat(memoryProvider.memory).containsExactly("default memory");
        assertThat(memoryProvider.user).containsExactly("default user");
        assertThat(memoryProvider.memoryFor(workUserId)).isEmpty();
        assertThat(memoryProvider.userFor(workUserId)).containsExactly("work user");
    }

    @Test
    void profilePrefixedRoutesValidateAndScopeMemory() throws Exception {
        ProfileService profileService = profileService();
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryDashboardController(memoryProvider, profileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        String workUserId = MemoryScope.userId(AgentProperties.DEFAULT_USER_ID, "work");
        memoryProvider.memory.add("default memory");
        memoryProvider.memoryFor(workUserId).add("work memory");

        mockMvc.perform(get("/p/work/api/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.builtin_files.memory").value("work memory".length()));

        mockMvc.perform(get("/p/work/api/memory").param("profile", "default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile query does not match route profile"));

        mockMvc.perform(get("/p/missing/api/memory"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: missing"));

        mockMvc.perform(get("/p/bad.profile/api/memory"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void providerConfigReturnsEmptyBuiltinSchema() throws Exception {
        mockMvc.perform(get("/api/memory/providers/builtin/config").param("surface", "declared"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("builtin"))
            .andExpect(jsonPath("$.label").value("Builtin"))
            .andExpect(jsonPath("$.docs_url").value(""))
            .andExpect(jsonPath("$.fields").isArray())
            .andExpect(jsonPath("$.fields.length()").value(0));
    }

    @Test
    void providerConfigReadReturnsEmptyFormForValidUnknownProviderLikeHermes() throws Exception {
        mockMvc.perform(get("/api/memory/providers/openviking/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("openviking"))
            .andExpect(jsonPath("$.label").value("Openviking"))
            .andExpect(jsonPath("$.docs_url").value(""))
            .andExpect(jsonPath("$.fields").isArray())
            .andExpect(jsonPath("$.fields.length()").value(0));
    }

    @Test
    void providerSetupReturnsNoOpResultForActiveBuiltinProvider() throws Exception {
        mockMvc.perform(post("/api/memory/providers/builtin/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.provider").value("builtin"))
            .andExpect(jsonPath("$.results[0].kind").value("setup"))
            .andExpect(jsonPath("$.results[0].name").value("builtin"))
            .andExpect(jsonPath("$.results[0].status").value("no_declared_steps"))
            .andExpect(jsonPath("$.results[0].command").value(""))
            .andExpect(jsonPath("$.results[0].returncode").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.results[0].stdout").value(""))
            .andExpect(jsonPath("$.results[0].stderr").value(""))
            .andExpect(jsonPath("$.status.name").value("builtin"))
            .andExpect(jsonPath("$.status.status").value("ready"));
    }

    @Test
    void providerConfigNormalizesProviderSchemaFieldsWithoutSecrets() throws Exception {
        memoryProvider = new InMemoryProvider("vector-store");
        memoryProvider.schema = List.of(
            Map.of(
                "key", "api_key",
                "label", "API key",
                "kind", "text",
                "secret", true,
                "default", "secret-value",
                "description", "Provider API key"
            ),
            Map.of(
                "key", "mode",
                "choices", List.of("cloud", "local"),
                "default", "cloud"
            )
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryDashboardController(memoryProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/api/memory/providers/vector-store/config").param("surface", "declared"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("vector-store"))
            .andExpect(jsonPath("$.fields[0].kind").value("secret"))
            .andExpect(jsonPath("$.fields[0].value").value(""))
            .andExpect(jsonPath("$.fields[0].is_set").value(false))
            .andExpect(jsonPath("$.fields[1].kind").value("select"))
            .andExpect(jsonPath("$.fields[1].value").value("cloud"))
            .andExpect(jsonPath("$.fields[1].options[0].value").value("cloud"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-value"))));
    }

    @Test
    void providerConfigUnknownAndOAuthReturn404() throws Exception {
        mockMvc.perform(get("/api/memory/providers/missing/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fields.length()").value(0));

        mockMvc.perform(post("/api/memory/providers/missing/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{}}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown memory provider: missing"));

        mockMvc.perform(post("/api/memory/providers/builtin/oauth/start"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("builtin does not support OAuth connect"));

        mockMvc.perform(get("/api/memory/providers/builtin/oauth/status"))
            .andExpect(status().isNotFound());
    }

    @Test
    void providerConfigWritesFailExplicitlyWhenValuesAreSubmitted() throws Exception {
        mockMvc.perform(put("/api/memory/providers/builtin/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"anything\":\"value\"}}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("memory provider config writes are not implemented in Java agent"));
    }

    private ProfileService profileService() {
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        return new ProfileService(properties, new RuntimeConfigService());
    }

    private static final class InMemoryProvider implements MemoryProvider {
        private final String name;
        private final Map<String, List<String>> memoryByUser = new HashMap<>();
        private final Map<String, List<String>> userByUser = new HashMap<>();
        private final List<String> memory;
        private final List<String> user;
        private List<Map<String, Object>> schema = List.of();

        private InMemoryProvider(String name) {
            this.name = name;
            this.memory = memoryFor(AgentProperties.DEFAULT_USER_ID);
            this.user = userFor(AgentProperties.DEFAULT_USER_ID);
        }

        @Override
        public List<String> recall(String userId, String query, int limit) {
            return memoryFor(userId).stream().limit(limit).toList();
        }

        @Override
        public void store(String userId, String category, String fact) {
            memoryFor(userId).add(fact);
        }

        @Override
        public void store(String userId, String target, String category, String fact) {
            bucket(userId, target).add(fact);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Map<String, Object>> getConfigSchema() {
            return schema;
        }

        @Override
        public List<String> getRawEntries(String userId, String target) {
            return new ArrayList<>(bucket(userId, target));
        }

        @Override
        public int clear(String userId, String target) {
            List<String> targetList = bucket(userId, target);
            int count = targetList.size();
            targetList.clear();
            return count;
        }

        private List<String> memoryFor(String userId) {
            return memoryByUser.computeIfAbsent(userKey(userId), ignored -> new ArrayList<>());
        }

        private List<String> userFor(String userId) {
            return userByUser.computeIfAbsent(userKey(userId), ignored -> new ArrayList<>());
        }

        private List<String> bucket(String userId, String target) {
            return "user".equals(target) ? userFor(userId) : memoryFor(userId);
        }

        private static String userKey(String userId) {
            return userId == null || userId.isBlank() ? AgentProperties.DEFAULT_USER_ID : userId;
        }
    }
}
