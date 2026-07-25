package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrowserNavigateToolTest {

    private static final String EXAMPLE_URL = "https://example.com";

    @Mock
    private BrowserService browserService;

    private final Message lastAssistant = null;
    private final Session session = Session.create("user-1", "noop", "default");

    @Test
    void delegatesToBrowserService() throws Exception {
        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        when(browserService.navigate(EXAMPLE_URL)).thenReturn("Navigated to https://example.com (frameId=abc123)");

        ToolResult result = tool.execute("{\"url\":\"" + EXAMPLE_URL + "\"}", lastAssistant, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Navigated to https://example.com").contains("abc123");
        verify(browserService).navigate(EXAMPLE_URL);
    }

    @Test
    void returnsFailureWhenServiceThrows() throws Exception {
        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        when(browserService.navigate(any())).thenThrow(new RuntimeException("Connection refused"));

        ToolResult result = tool.execute("{\"url\":\"https://no-such-host.example\"}", lastAssistant, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Browser navigate failed").contains("Connection refused");
    }
}
