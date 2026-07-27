package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BrowserCdpToolTest {

    private final BrowserService service = mock(BrowserService.class);
    private final Session session = Session.create("u","p","m");

    @Test
    void invokesCdpMethod() throws Exception {
        when(service.evaluate("1+1")).thenReturn("2");
        BrowserCdpTool t = new BrowserCdpTool(service);
        ToolResult r = t.execute("{\"expression\":\"1+1\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("2");
    }
}
