package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-l (hot toolset reload parity): dashboard/API toolset toggles mutate
 * AgentProperties in-memory; the main /v1/chat/completions endpoint must
 * honor them without a restart (previously buildTools ignored the filter).
 */
@ExtendWith(MockitoExtension.class)
class ChatCompletionsToolsetHotReloadTest {

    @Mock
    private ToolRegistry toolRegistry;

    private final AgentProperties properties = new AgentProperties();

    private ChatCompletionsController controller() {
        return new ChatCompletionsController(
            null, toolRegistry, null, properties, null, null, null, null, null);
    }

    private static OpenAiChatRequest request() {
        return new OpenAiChatRequest("model-x", null, List.of(), null, null, null, null, null, null);
    }

    private List<ToolDefinition> buildTools(ChatCompletionsController c) throws Exception {
        var m = ChatCompletionsController.class.getDeclaredMethod("buildTools", OpenAiChatRequest.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolDefinition> out = (List<ToolDefinition>) m.invoke(c, request());
        return out;
    }

    @Test
    void liveToolsetListFiltersDefinitions() throws Exception {
        properties.getApi().getChatCompletionToolsets().clear();
        properties.getApi().getChatCompletionToolsets().addAll(List.of("web", "browser"));
        when(toolRegistry.getDefinitions(anySet())).thenReturn(List.of());
        var controller = controller();

        buildTools(controller);

        verify(toolRegistry).getDefinitions(eq(new HashSet<>(Set.of("web", "browser"))));
        verify(toolRegistry, never()).getDefinitions();
    }

    @Test
    void nullToolsetsFallBackToAllDefinitions() throws Exception {
        properties.getApi().setChatCompletionToolsets(null);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        var controller = controller();

        List<ToolDefinition> out = buildTools(controller);

        assertThat(out).isEmpty();
        verify(toolRegistry).getDefinitions();
        verify(toolRegistry, never()).getDefinitions(anySet());
    }

    @Test
    void defaultHermesApiServerToolsetIsApplied() throws Exception {
        // default value: ["hermes-api-server"] — must filter, not ignore
        when(toolRegistry.getDefinitions(anySet())).thenReturn(List.of());

        buildTools(controller());

        verify(toolRegistry).getDefinitions(eq(new HashSet<>(Set.of("hermes-api-server"))));
    }

    @Test
    void explicitRequestToolsBypassTheFilter() throws Exception {
        when(toolRegistry.getDefinitions(anySet())).thenReturn(List.of());
        var m = ChatCompletionsController.class.getDeclaredMethod("buildTools", OpenAiChatRequest.class);
        m.setAccessible(true);
        var req = new OpenAiChatRequest("model-x", null, List.of(), null, null, null, null, null, null);

        @SuppressWarnings("unchecked")
        List<ToolDefinition> out = (List<ToolDefinition>) m.invoke(controller(), req);

        assertThat(out).isEmpty(); // request carried no tools -> filtered path was used
        verify(toolRegistry).getDefinitions(anySet());
    }
}
