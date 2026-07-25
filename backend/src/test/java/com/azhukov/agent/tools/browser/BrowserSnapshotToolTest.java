package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(browserService.evaluate(org.mockito.ArgumentMatchers.anyString())).thenReturn(snapshot);

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content())
            .contains("Title: Example Domain")
            .contains("Links:")
            .contains("Inputs:")
            .contains("https://example.com/");
        verify(browserService).evaluate(org.mockito.ArgumentMatchers.contains("document.title"));
        verify(browserService).evaluate(org.mockito.ArgumentMatchers.contains("document.querySelectorAll('a')"));
    }

    @Test
    void returnsFailureWhenEvaluateThrows() throws Exception {
        BrowserSnapshotTool tool = new BrowserSnapshotTool(browserService);
        when(browserService.evaluate(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new RuntimeException("CDP socket closed"));

        ToolResult result = tool.execute("{}", lastAssistant, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Browser snapshot failed").contains("CDP socket closed");
    }
}
