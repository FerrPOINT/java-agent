package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BrowserVisionToolTest {

    private final BrowserService service = mock(BrowserService.class);
    private final Session session = Session.create("u","p","m");

    @Test
    void returnsScreenshotDataUri() throws Exception {
        when(service.screenshot()).thenReturn("data:image/png;base64,abc");
        BrowserVisionTool t = new BrowserVisionTool(service);
        ToolResult r = t.execute("{}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).startsWith("data:image/png;base64,");
    }

    @Test
    void returnsFailureOnException() throws Exception {
        when(service.screenshot()).thenThrow(new RuntimeException("cdp down"));
        BrowserVisionTool t = new BrowserVisionTool(service);
        ToolResult r = t.execute("{}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("cdp down");
    }
}
