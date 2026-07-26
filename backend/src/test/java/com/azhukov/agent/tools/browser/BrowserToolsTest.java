package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class BrowserToolsTest {

    private final BrowserService service = mock(BrowserService.class);
    private final Session session = Session.create("u","p","m");

    @Test
    void browserCdpToolEvaluates() throws Exception {
        when(service.evaluate("1+1")).thenReturn("2");
        BrowserCdpTool t = new BrowserCdpTool(service);
        ToolResult r = t.execute("{\"expression\":\"1+1\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("2");
    }

    @Test
    void browserClickToolClicks() throws Exception {
        when(service.click("#btn")).thenReturn("clicked");
        BrowserClickTool t = new BrowserClickTool(service);
        ToolResult r = t.execute("{\"selector\":\"#btn\"}", null, session);
        assertThat(r.content()).isEqualTo("clicked");
    }

    @Test
    void browserTypeToolTypes() throws Exception {
        when(service.evaluate(contains("el.value"))).thenReturn("typed");
        BrowserTypeTool t = new BrowserTypeTool(service);
        ToolResult r = t.execute("{\"text\":\"hello\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserPressToolPresses() throws Exception {
        when(service.evaluate(contains("keydown"))).thenReturn("pressed");
        BrowserPressTool t = new BrowserPressTool(service);
        ToolResult r = t.execute("{\"key\":\"Enter\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserScrollToolScrolls() throws Exception {
        when(service.evaluate(contains("scrollBy"))).thenReturn("{\"x\":0,\"y\":100}");
        BrowserScrollTool t = new BrowserScrollTool(service);
        ToolResult r = t.execute("{\"x\":0,\"y\":100}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserConsoleToolEvaluates() throws Exception {
        when(service.evaluate("console.log('x')")).thenReturn("undefined");
        BrowserConsoleTool t = new BrowserConsoleTool(service);
        ToolResult r = t.execute("{\"expression\":\"console.log('x')\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserDialogToolAccepts() throws Exception {
        when(service.evaluate("window.__agent_dialog && window.__agent_dialog.accept()")).thenReturn("ok");
        BrowserDialogTool t = new BrowserDialogTool(service);
        ToolResult r = t.execute("{\"action\":\"accept\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserDialogToolDismisses() throws Exception {
        when(service.evaluate("window.__agent_dialog && window.__agent_dialog.dismiss()")).thenReturn("dismissed");
        BrowserDialogTool t = new BrowserDialogTool(service);
        ToolResult r = t.execute("{\"action\":\"dismiss\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserBackToolGoesBack() throws Exception {
        when(service.evaluate("history.back()")).thenReturn("back");
        BrowserBackTool t = new BrowserBackTool(service);
        ToolResult r = t.execute("{}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void browserGetImagesToolGetsImages() throws Exception {
        when(service.evaluate(contains("querySelectorAll"))).thenReturn("[img]");
        BrowserGetImagesTool t = new BrowserGetImagesTool(service);
        ToolResult r = t.execute("{}", null, session);
        assertThat(r.success()).isTrue();
    }
}
