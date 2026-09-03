package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BrowserToolWrapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void wrappersReturnStructuredErrorForMalformedArguments() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        Map<String, ToolHandler> tools = new LinkedHashMap<>();
        tools.put("browser_click", new BrowserClickTool(browserService));
        tools.put("browser_dialog", new BrowserDialogTool(browserService));
        tools.put("browser_navigate", new BrowserNavigateTool(browserService));
        tools.put("browser_press", new BrowserPressTool(browserService));
        tools.put("browser_scroll", new BrowserScrollTool(browserService));
        tools.put("browser_snapshot", new BrowserSnapshotTool(browserService));
        tools.put("browser_type", new BrowserTypeTool(browserService));
        tools.put("browser_vision", new BrowserVisionTool(browserService));

        for (Map.Entry<String, ToolHandler> entry : tools.entrySet()) {
            ToolResult result = entry.getValue().execute("{", null, null);

            assertThat(result.success()).as(entry.getKey()).isFalse();
            JsonNode json = JSON.readTree(result.content());
            assertThat(json.path("success").asBoolean()).as(entry.getKey()).isFalse();
            assertThat(json.path("error").asText()).as(entry.getKey()).contains("Invalid tool arguments");
            assertThat(result.error()).as(entry.getKey()).isEqualTo(json.path("error").asText());
        }
        verifyNoInteractions(browserService);
    }

    @Test
    void visionReturnsScreenshotPathAndMediaTag() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.captureScreenshot()).thenReturn(new BrowserService.BrowserScreenshot(
            true,
            "data:image/png;base64,AQID",
            "C:\\tmp\\browser_screenshot_1.png",
            "MEDIA:C:\\tmp\\browser_screenshot_1.png",
            "image/png",
            null));

        BrowserVisionTool tool = new BrowserVisionTool(browserService);
        ToolResult result = tool.execute("{\"question\":\"what is visible?\"}", null, null);

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("data_url").asText()).isEqualTo("data:image/png;base64,AQID");
        assertThat(json.path("screenshot_path").asText()).isEqualTo("C:\\tmp\\browser_screenshot_1.png");
        assertThat(json.path("media_tag").asText()).isEqualTo("MEDIA:C:\\tmp\\browser_screenshot_1.png");
        assertThat(json.path("mime_type").asText()).isEqualTo("image/png");
        verify(browserService).captureScreenshot();
    }

    @Test
    void visionReturnsStructuredFailureWhenCaptureFails() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.captureScreenshot()).thenReturn(new BrowserService.BrowserScreenshot(
            false, null, null, null, null, "Screenshot failed: no data"));

        BrowserVisionTool tool = new BrowserVisionTool(browserService);
        ToolResult result = tool.execute("{\"question\":\"what is visible?\"}", null, null);

        assertThat(result.success()).isFalse();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).isEqualTo("Screenshot failed: no data");
    }

    @Test
    void typeDelegatesRefToBrowserService() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.type("@search", "hello", true)).thenReturn("typed");

        BrowserTypeTool tool = new BrowserTypeTool(browserService);
        ToolResult result = tool.execute(
            "{\"ref\":\"@search\",\"text\":\"hello\",\"clear\":true}",
            null,
            null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"typed\":\"hello\"");
        assertThat(result.content()).contains("\"element\":\"@search\"");
        verify(browserService).type("@search", "hello", true);
        verify(browserService, never()).evaluate(anyString());
    }

    @Test
    void typeKeepsLegacySelectorFallback() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.type("#q", "hello", true)).thenReturn("typed");

        BrowserTypeTool tool = new BrowserTypeTool(browserService);
        ToolResult result = tool.execute(
            "{\"selector\":\"#q\",\"text\":\"hello\"}",
            null,
            null);

        assertThat(result.success()).isTrue();
        verify(browserService).type("#q", "hello", true);
    }

    @Test
    void typeAllowsExplicitNoClearExtension() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.type("@search", "hello", false)).thenReturn("typed");

        BrowserTypeTool tool = new BrowserTypeTool(browserService);
        ToolResult result = tool.execute(
            "{\"ref\":\"@search\",\"text\":\"hello\",\"clear\":false}",
            null,
            null);

        assertThat(result.success()).isTrue();
        verify(browserService).type("@search", "hello", false);
    }

    @Test
    void typeRequiresText() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserTypeTool tool = new BrowserTypeTool(browserService);
        ToolResult result = tool.execute("{\"selector\":\"#q\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("text is required");
    }

    @Test
    void typeRequiresRef() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserTypeTool tool = new BrowserTypeTool(browserService);
        ToolResult result = tool.execute("{\"text\":\"hello\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ref is required");
    }

    @Test
    void scrollUsesExpressionFunctionWrapper() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate(anyString())).thenReturn("{\"x\":0,\"y\":500}");

        BrowserScrollTool tool = new BrowserScrollTool(browserService);
        ToolResult result = tool.execute("{\"y\":500}", null, null);

        assertThat(result.success()).isTrue();
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(browserService).evaluate(captor.capture());
        String script = captor.getValue();
        assertThat(script).startsWith("(() => {");
        assertThat(script).contains("window.scrollBy(0, 500)");
        assertThat(script).contains("return { x: window.scrollX, y: window.scrollY }");
        assertThat(script).doesNotContain("return JSON.stringify");
    }

    @Test
    void scrollAcceptsHermesDirectionArgument() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate(anyString())).thenReturn("{\"x\":0,\"y\":800}");

        BrowserScrollTool tool = new BrowserScrollTool(browserService);
        ToolResult result = tool.execute("{\"direction\":\"down\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"scrolled\":\"down\"");
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(browserService).evaluate(captor.capture());
        assertThat(captor.getValue()).contains("window.scrollBy(0, 500)");
    }

    @Test
    void scrollDefaultsToHermesDownDirectionWhenNoArgumentsProvided() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate(anyString())).thenReturn("{\"x\":0,\"y\":800}");

        BrowserScrollTool tool = new BrowserScrollTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"scrolled\":\"down\"");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(browserService).evaluate(captor.capture());
        assertThat(captor.getValue()).contains("window.scrollBy(0, 500)");
    }

    @Test
    void scrollRejectsInvalidHermesDirectionWithoutEvaluating() throws Exception {
        BrowserService browserService = mock(BrowserService.class);

        BrowserScrollTool tool = new BrowserScrollTool(browserService);
        ToolResult result = tool.execute("{\"direction\":\"left\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid direction 'left'");
        assertThat(result.content()).contains("\"success\":false");
        assertThat(result.content()).contains("Invalid direction 'left'");
        verify(browserService, never()).evaluate(anyString());
    }

    @Test
    void pressUsesCdpKeyDispatchInsteadOfSyntheticDomEvent() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.press("Enter")).thenReturn("Pressed Enter");

        BrowserPressTool tool = new BrowserPressTool(browserService);
        ToolResult result = tool.execute("{\"key\":\"Enter\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"pressed\":\"Enter\"");
        verify(browserService).press("Enter");
        verify(browserService, never()).evaluate(anyString());
    }

    @Test
    void pressRequiresKey() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserPressTool tool = new BrowserPressTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("key is required");
    }

    @Test
    void clickDelegatesRefToBrowserService() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.click("@e5")).thenReturn("clicked");

        BrowserClickTool tool = new BrowserClickTool(browserService);
        ToolResult result = tool.execute("{\"ref\":\"@e5\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"clicked\":\"@e5\"");
        verify(browserService).click("@e5");
    }

    @Test
    void clickRequiresRef() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserClickTool tool = new BrowserClickTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ref is required");
    }

    @Test
    void navigateRequiresUrl() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("url is required");
    }

    @Test
    void navigateAppendsCompactSnapshotAfterSuccessfulNavigation() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.navigate("https://example.com", 12))
            .thenReturn("Navigated to https://example.com (frameId=f1)");
        when(browserService.accessibilitySnapshot(false))
            .thenReturn("button [ref=e1]: Search\n");

        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        ToolResult result = tool.execute(
            "{\"url\":\"https://example.com\",\"wait_seconds\":12}",
            null,
            null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Navigated to https://example.com");
        assertThat(result.content()).contains("Snapshot:");
        assertThat(result.content()).contains("button [ref=e1]: Search");
        verify(browserService).accessibilitySnapshot(false);
    }

    @Test
    void navigateDoesNotSnapshotFailedNavigation() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.navigate("https://blocked.example", 30))
            .thenReturn("URL blocked by safety policy: https://blocked.example");

        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        ToolResult result = tool.execute("{\"url\":\"https://blocked.example\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URL blocked by safety policy");
        assertThat(result.content()).contains("\"success\":false");
        assertThat(result.content()).doesNotContain("Snapshot:");
        verify(browserService, never()).accessibilitySnapshot(false);
    }

    @Test
    void getImagesIncludesDimensionsAndSkipsDataUrls() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate(anyString())).thenReturn("[]");

        BrowserGetImagesTool tool = new BrowserGetImagesTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(browserService).evaluate(captor.capture());
        String script = captor.getValue();
        assertThat(script).contains("naturalWidth");
        assertThat(script).contains("naturalHeight");
        assertThat(script).contains("!i.src.startsWith('data:')");
    }

    @Test
    void cdpDelegatesRawMethodAndParamsToBrowserService() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.rawCdp(eq("Target.getTargets"), any(JsonNode.class), eq(7)))
            .thenReturn("{\"success\":true}");

        BrowserCdpTool tool = new BrowserCdpTool(browserService);
        ToolResult result = tool.execute(
            "{\"method\":\"Target.getTargets\",\"params\":{\"type\":\"page\"},\"timeout\":7}",
            null,
            null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("{\"success\":true}");
        ArgumentCaptor<JsonNode> params = ArgumentCaptor.forClass(JsonNode.class);
        verify(browserService).rawCdp(eq("Target.getTargets"), params.capture(), eq(7));
        assertThat(params.getValue().path("type").asText()).isEqualTo("page");
        verify(browserService, never()).evaluate(anyString());
    }

    @Test
    void cdpReturnsStructuredFailureWhenRawNavigationIsBlockedBySafety() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.rawCdp(eq("Page.navigate"), any(JsonNode.class), eq(0)))
            .thenThrow(new IllegalArgumentException("URL blocked by safety policy: http://127.0.0.1/admin"));

        BrowserCdpTool tool = new BrowserCdpTool(browserService);
        ToolResult result = tool.execute(
            "{\"method\":\"Page.navigate\",\"params\":{\"url\":\"http://127.0.0.1/admin\"}}",
            null,
            null);

        assertThat(result.success()).isFalse();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("URL blocked by safety policy");
    }

    @Test
    void cdpRequiresMethodUnlessUsingLegacyExpressionFallback() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserCdpTool tool = new BrowserCdpTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("method is required");
    }

    @Test
    void cdpRejectsUnsupportedTargetIdInsteadOfIgnoringIt() throws Exception {
        BrowserService browserService = mock(BrowserService.class);

        BrowserCdpTool tool = new BrowserCdpTool(browserService);
        ToolResult result = tool.execute(
            "{\"method\":\"Runtime.evaluate\",\"target_id\":\"tab-1\",\"params\":{\"expression\":\"1\"}}",
            null,
            null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("target_id is not supported");
        verify(browserService, never()).rawCdp(anyString(), any(), eq(30));
    }

    @Test
    void cdpKeepsLegacyExpressionFallback() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate("1+1")).thenReturn("2");

        BrowserCdpTool tool = new BrowserCdpTool(browserService);
        ToolResult result = tool.execute("{\"expression\":\"1+1\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("2");
        verify(browserService).evaluate("1+1");
        verify(browserService, never()).rawCdp(anyString(), any(), eq(30));
    }

    @Test
    void navigateTreatsWebsitePolicyBlockAsFailureWithoutSnapshot() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.navigate("https://blocked.example", 30))
            .thenReturn("Blocked by website policy: 'blocked.example' matched rule 'blocked.example'");

        BrowserNavigateTool tool = new BrowserNavigateTool(browserService);
        ToolResult result = tool.execute("{\"url\":\"https://blocked.example\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Blocked by website policy");
        verify(browserService, never()).accessibilitySnapshot(anyBoolean());
    }

    @Test
    void dialogUsesHermesPromptTextArgument() {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.handleDialog(true, "typed answer")).thenReturn("Dialog accepted");

        BrowserDialogTool tool = new BrowserDialogTool(browserService);
        ToolResult result = tool.execute(
            "{\"action\":\"accept\",\"prompt_text\":\"typed answer\"}",
            null,
            null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Dialog accepted");
        verify(browserService).handleDialog(true, "typed answer");
    }

    @Test
    void dialogKeepsLegacyTextAlias() {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.handleDialog(true, "legacy answer")).thenReturn("Dialog accepted");

        BrowserDialogTool tool = new BrowserDialogTool(browserService);
        ToolResult result = tool.execute(
            "{\"action\":\"accept\",\"text\":\"legacy answer\"}",
            null,
            null);

        assertThat(result.success()).isTrue();
        verify(browserService).handleDialog(true, "legacy answer");
    }

    @Test
    void dialogRequiresAction() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserDialogTool tool = new BrowserDialogTool(browserService);
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("action is required");
    }

    @Test
    void dialogRejectsUnsupportedDialogIdInsteadOfIgnoringIt() {
        BrowserService browserService = mock(BrowserService.class);

        BrowserDialogTool tool = new BrowserDialogTool(browserService);
        ToolResult result = tool.execute(
            "{\"action\":\"accept\",\"dialog_id\":\"d1\"}",
            null,
            null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("dialog_id is not supported");
        verify(browserService, never()).handleDialog(anyBoolean(), any());
    }

    @Test
    void consoleExpressionReturnsHermesStyleJsonEnvelope() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate("document.title")).thenReturn("Example");

        BrowserConsoleTool tool = new BrowserConsoleTool(browserService);
        ToolResult result = tool.execute("{\"expression\":\"document.title\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"success\":true");
        assertThat(result.content()).contains("\"result\":\"Example\"");
        assertThat(result.content()).contains("\"result_type\":\"string\"");
    }

    @Test
    void consoleExpressionPreservesStructuredJsonResult() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate("JSON.stringify([1,2])")).thenReturn("[1,2]");

        BrowserConsoleTool tool = new BrowserConsoleTool(browserService);
        ToolResult result = tool.execute("{\"expression\":\"JSON.stringify([1,2])\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"result\":[1,2]");
        assertThat(result.content()).contains("\"result_type\":\"array\"");
    }

    @Test
    void consoleExpressionReportsEvaluationErrorsAsFailureEnvelope() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.evaluate("return 1")).thenReturn("Evaluation error: SyntaxError");

        BrowserConsoleTool tool = new BrowserConsoleTool(browserService);
        ToolResult result = tool.execute("{\"expression\":\"return 1\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Evaluation error: SyntaxError");
        assertThat(result.content()).contains("\"success\":false");
        assertThat(result.content()).contains("Evaluation error: SyntaxError");
    }

    @Test
    void consoleWithoutExpressionReturnsBufferedConsoleMessages() throws Exception {
        BrowserService browserService = mock(BrowserService.class);
        when(browserService.console(true))
            .thenReturn("{\"success\":true,\"console_messages\":[],\"js_errors\":[],\"total_messages\":0,\"total_errors\":0}");

        BrowserConsoleTool tool = new BrowserConsoleTool(browserService);
        ToolResult result = tool.execute("{\"clear\":true}", null, null);

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("total_messages").asInt()).isZero();
        assertThat(json.path("total_errors").asInt()).isZero();
        verify(browserService).console(true);
        verify(browserService, never()).evaluate(anyString());
    }
}
