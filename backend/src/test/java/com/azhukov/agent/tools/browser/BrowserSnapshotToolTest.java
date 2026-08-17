package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrowserSnapshotToolTest {

    @Mock
    private BrowserService browserService;

    private final Message lastAssistant = null;
    private final Session session = Session.create("user-1", "noop", "default");

    @Test
    void returnsAccessibleSnapshot() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        String snapshot = """
            Title: Example Domain

            Links:
            https://example.com/ | More information...

            Inputs:
            input#search[name=q] | search text
            """;
        when(browserService.accessibilitySnapshot(anyBoolean())).thenReturn(snapshot);

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content())
            .contains("Title: Example Domain")
            .contains("Links:")
            .contains("Inputs:")
            .contains("https://example.com/");
        verify(browserService).accessibilitySnapshot(anyBoolean());
    }

    @Test
    void returnsFailureWhenEvaluateThrows() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.accessibilitySnapshot(anyBoolean()))
            .thenThrow(new RuntimeException("CDP socket closed"));

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Browser snapshot failed").contains("CDP socket closed");
    }

    // ── Accessibility full parameter tests ──────────────────────────────

    @Test
    void browserSnapshotToolPassesFullParameterTrue() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.accessibilitySnapshot(true)).thenReturn("full snapshot");

        ToolResult result = tool.execute("{\"full\":true}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("full snapshot");
        verify(browserService).accessibilitySnapshot(true);
    }

    @Test
    void browserSnapshotToolPassesFullParameterFalse() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.accessibilitySnapshot(false)).thenReturn("compact snapshot");

        ToolResult result = tool.execute("{\"full\":false}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("compact snapshot");
        verify(browserService).accessibilitySnapshot(false);
    }

    @Test
    void browserSnapshotToolDefaultsFullToFalse() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.accessibilitySnapshot(false)).thenReturn("default snapshot");

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("default snapshot");
        verify(browserService).accessibilitySnapshot(false);
    }

    @Test
    void browserSnapshotToolEmptyTreeReturnsEmpty() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.accessibilitySnapshot(false)).thenReturn("");

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEmpty();
    }
}
