package com.azhukov.agent.tools;

import com.azhukov.agent.tools.code.ExecuteCodeTool;
import com.azhukov.agent.tools.cron.CronJobTool;
import com.azhukov.agent.tools.delegate.DelegateTaskTool;
import com.azhukov.agent.tools.file.PatchTool;
import com.azhukov.agent.tools.file.ReadFileTool;
import com.azhukov.agent.tools.file.SearchFilesTool;
import com.azhukov.agent.tools.file.WriteFileTool;
import com.azhukov.agent.tools.imagegen.ImageGenTool;
import com.azhukov.agent.tools.memory.ClarifyTool;
import com.azhukov.agent.tools.memory.MemoryTool;
import com.azhukov.agent.tools.memory.SessionSearchTool;
import com.azhukov.agent.tools.memory.SkillManageTool;
import com.azhukov.agent.tools.memory.SkillViewTool;
import com.azhukov.agent.tools.memory.SkillsListTool;
import com.azhukov.agent.tools.memory.TodoTool;
import com.azhukov.agent.tools.terminal.ProcessTool;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.azhukov.agent.tools.tts.TtsTool;
import com.azhukov.agent.tools.vision.VisionAnalyzeTool;
import com.azhukov.agent.tools.web.WebExtractTool;
import com.azhukov.agent.tools.web.WebSearchTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolAnnotationParityTest {

    @Test
    void coreToolAnnotationsUseHermesToolsets() {
        assertTool(WebSearchTool.class, "web_search", "web");
        assertTool(WebExtractTool.class, "web_extract", "web");
        assertTool(ReadFileTool.class, "read_file", "file");
        assertTool(WriteFileTool.class, "write_file", "file");
        assertTool(PatchTool.class, "patch", "file");
        assertTool(SearchFilesTool.class, "search_files", "file");
        assertTool(TerminalTool.class, "terminal", "terminal");
        assertTool(ProcessTool.class, "process", "terminal");
        assertTool(TodoTool.class, "todo", "todo");
        assertTool(MemoryTool.class, "memory", "memory");
        assertTool(SessionSearchTool.class, "session_search", "session_search");
        assertTool(ClarifyTool.class, "clarify", "clarify");
        assertTool(SkillsListTool.class, "skills_list", "skills");
        assertTool(SkillViewTool.class, "skill_view", "skills");
        assertTool(SkillManageTool.class, "skill_manage", "skills");
        assertTool(VisionAnalyzeTool.class, "vision_analyze", "vision");
        assertTool(ImageGenTool.class, "image_generate", "image_gen");
        assertTool(TtsTool.class, "text_to_speech", "tts");
        assertTool(ExecuteCodeTool.class, "execute_code", "code_execution");
        assertTool(DelegateTaskTool.class, "delegate_task", "delegation");
        assertTool(CronJobTool.class, "cronjob", "cronjob");
    }

    @Test
    void delegateTaskDescriptionDoesNotOverpromiseGatewayReinjection() {
        String description = DelegateTaskTool.class.getAnnotation(AgentTool.class).description();

        assertThat(description)
            .contains("run_id/delegation_id")
            .contains("action='status'")
            .contains("action='read'")
            .contains("full Hermes gateway reinjection loop");
        assertThat(description)
            .doesNotContain("re-enters the conversation on its own")
            .doesNotContain("Do NOT wait or poll");
    }

    private void assertTool(Class<?> toolClass, String name, String toolset) {
        AgentTool annotation = toolClass.getAnnotation(AgentTool.class);
        assertThat(annotation)
            .as("%s @AgentTool", toolClass.getSimpleName())
            .isNotNull();
        assertThat(annotation.name()).isEqualTo(name);
        assertThat(annotation.toolset()).isEqualTo(toolset);
    }
}
