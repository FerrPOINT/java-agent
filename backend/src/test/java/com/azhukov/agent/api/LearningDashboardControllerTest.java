package com.azhukov.agent.api;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LearningDashboardControllerTest {

    private InMemorySkillManager skillManager;
    private InMemoryMemoryProvider memoryProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        skillManager = new InMemorySkillManager();
        memoryProvider = new InMemoryMemoryProvider();
        mockMvc = MockMvcBuilders.standaloneSetup(
            new LearningDashboardController(skillManager, memoryProvider)).build();
    }

    @Test
    void graphReturnsHermesStarmapShapeFromSkillsAndMemory() throws Exception {
        skillManager.put(new SkillManager.SkillInfo(
            "debug-skill", "content", "Debug helper", "coding", Instant.EPOCH,
            2, 3, Instant.EPOCH, false, "AGENT_CREATED", List.of(), List.of("test-skill"), false, null));
        skillManager.put(new SkillManager.SkillInfo(
            "test-skill", "content", "Test helper", "testing", Instant.EPOCH,
            0, 1, Instant.EPOCH, false, "AGENT_CREATED", List.of(), List.of(), false, null));
        memoryProvider.memory.add("debug-skill should be used for diagnostics");
        memoryProvider.user.add("The user likes focused tests");

        mockMvc.perform(get("/api/learning/graph"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes[0].id").value("debug-skill"))
            .andExpect(jsonPath("$.nodes[0].kind").value("skill"))
            .andExpect(jsonPath("$.nodes[0].useCount").value(5))
            .andExpect(jsonPath("$.nodes[2].id").value("memory:memory:0"))
            .andExpect(jsonPath("$.nodes[3].id").value("memory:profile:1"))
            .andExpect(jsonPath("$.edges[0].source").value("debug-skill"))
            .andExpect(jsonPath("$.memory[0].source").value("memory"))
            .andExpect(jsonPath("$.clusters").isArray())
            .andExpect(jsonPath("$.stats.memory_nodes").value(2));
    }

    @Test
    void nodeReturnsSkillOrMemoryContent() throws Exception {
        skillManager.saveSkill("debug-skill", "---\nname: debug-skill\ndescription: Debug\n---\nBody");
        memoryProvider.memory.add("First memory");

        mockMvc.perform(get("/api/learning/node?id=debug-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.kind").value("skill"))
            .andExpect(jsonPath("$.content").value("---\nname: debug-skill\ndescription: Debug\n---\nBody"));

        mockMvc.perform(get("/api/learning/node?id=memory:memory:0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("memory"))
            .andExpect(jsonPath("$.content").value("First memory"));
    }

    @Test
    void editAndDeleteLearningNodesUseExistingManagers() throws Exception {
        skillManager.saveSkill("debug-skill", "---\nname: debug-skill\ndescription: Debug\n---\nBody");
        memoryProvider.memory.add("Old memory");

        mockMvc.perform(put("/api/learning/node")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"debug-skill\",\"content\":\"---\\nname: debug-skill\\ndescription: Debug\\n---\\nNew body\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        assertThat(skillManager.getSkill("debug-skill")).contains("New body");

        mockMvc.perform(put("/api/learning/node")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"memory:memory:0\",\"content\":\"New memory\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("updated memory in MEMORY.md"));

        assertThat(memoryProvider.memory).containsExactly("New memory");

        mockMvc.perform(delete("/api/learning/node")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"debug-skill\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("archived 'debug-skill'"));

        mockMvc.perform(delete("/api/learning/node")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"memory:memory:0\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("deleted memory from MEMORY.md"));

        assertThat(memoryProvider.memory).isEmpty();
    }

    @Test
    void badLearningNodeRequestsReturnStableErrors() throws Exception {
        mockMvc.perform(get("/api/learning/node?id=missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.ok").value(false));

        mockMvc.perform(put("/api/learning/node")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":\"memory:memory:0\",\"content\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("empty memory — use delete to remove it"));
    }

    private static final class InMemorySkillManager implements SkillManager {
        private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

        void put(SkillInfo info) {
            skills.put(info.name(), info);
        }

        @Override
        public List<String> listSkillNames() {
            return new ArrayList<>(skills.keySet());
        }

        @Override
        public String getSkill(String name) {
            SkillInfo info = skills.get(name);
            return info != null ? info.content() : null;
        }

        @Override
        public void saveSkill(String name, String content) {
            saveSkill(name, content, com.azhukov.agent.core.skill.WriteOrigin.USER);
        }

        @Override
        public void saveSkill(String name, String content, com.azhukov.agent.core.skill.WriteOrigin origin) {
            skills.put(name, new SkillInfo(name, content, "Skill", "general", Instant.EPOCH,
                0, 0, Instant.EPOCH, false, "AGENT_CREATED", List.of(), List.of(), false, null));
        }

        @Override
        public boolean deleteSkill(String name) {
            return skills.remove(name) != null;
        }

        @Override
        public List<SkillInfo> listSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public SkillLookupResult getSkillInfoMultiStrategy(String name) {
            return new SkillLookupResult(skills.get(name), List.of(), null);
        }

        @Override
        public boolean archiveSkill(String name) {
            SkillInfo info = skills.get(name);
            if (info == null) {
                return false;
            }
            skills.put(name, new SkillInfo(info.name(), info.content(), info.description(), info.category(),
                info.updatedAt(), info.viewCount(), info.manageCount(), info.lastActivityAt(),
                true, info.trustLevel(), info.tags(), info.relatedSkills(), info.disabled(), info.linkedFiles()));
            return true;
        }
    }

    private static final class InMemoryMemoryProvider implements MemoryProvider {
        private final List<String> memory = new ArrayList<>();
        private final List<String> user = new ArrayList<>();

        @Override
        public List<String> recall(String userId, String query, int limit) {
            return memory.stream().limit(limit).toList();
        }

        @Override
        public void store(String userId, String category, String fact) {
            memory.add(fact);
        }

        @Override
        public List<String> getRawEntries(String userId, String target) {
            return "user".equals(target) ? new ArrayList<>(user) : new ArrayList<>(memory);
        }

        @Override
        public String replace(String userId, String target, String oldText, String newText) {
            List<String> list = "user".equals(target) ? user : memory;
            int index = list.indexOf(oldText);
            if (index < 0) {
                return "not found";
            }
            list.set(index, newText);
            return null;
        }

        @Override
        public String remove(String userId, String target, String oldText) {
            List<String> list = "user".equals(target) ? user : memory;
            return list.remove(oldText) ? null : "not found";
        }
    }
}
