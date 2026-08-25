package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebExtractTool}.
 * Covers execute() with valid URL, empty/null args, blocked URL, IO errors,
 * multiple URLs, and PDF content-type detection.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebExtractToolTest {

    @Mock
    private UrlSafety urlSafety;

    @Mock
    private Redactor redactor;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getWeb().setExtractTimeoutSeconds(10);
        p.getWeb().setExtractMaxChars(100_000);
        return p;
    }

    private WebExtractTool newTool(AgentProperties p) {
        WebExtractTool tool = new WebExtractTool(p, urlSafety, redactor);
        tool.init();
        return tool;
    }

    private Session session() {
        return Session.create("user", "openai", "gpt-4");
    }

    // ── 1. Valid URL — extract returns title + body, success ─────────────

    @Test
    void executeWithValidUrlReturnsExtractedContent() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Document doc = Jsoup.parse("<html><head><title>Hello</title></head><body><p>World</p></body></html>", "https://example.com");
        Connection.Response response = mock(Connection.Response.class);
        when(response.contentType()).thenReturn("text/html");
        when(response.parse()).thenReturn(doc);
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(true)).thenReturn(connection);
        when(connection.execute()).thenReturn(response);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("example.com"))).thenReturn(connection);

            WebExtractTool tool = newTool(p);
            ToolResult result = tool.execute("{\"urls\":[\"https://example.com\"]}", null, session());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("# Hello");
            assertThat(result.content()).contains("World");
            assertThat(result.content()).contains("--- URL: https://example.com ---");
        }
    }

    // ── 2. Empty URL list → fail ──────────────────────────────────────────

    @Test
    void executeWithEmptyUrlsListFails() {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        ToolResult result = tool.execute("{\"urls\":[]}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URLs are required");
    }

    // ── 3. Null args / invalid JSON → fail ────────────────────────────────

    @Test
    void executeWithNullUrlsFieldFails() {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        // urls field omitted entirely → parsed as null
        ToolResult result = tool.execute("{}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URLs are required");
    }

    // ── 4. Blocked URL — appends safety message, does not extract ─────────

    @Test
    void executeWithBlockedUrlAppendsSafetyMessage() {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(false);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        ToolResult result = tool.execute("{\"urls\":[\"https://blocked.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("URL blocked by safety policy");
        assertThat(result.content()).contains("--- URL: https://blocked.example ---");
    }

    // ── 5. IO error during extract — appends failure message ──────────────

    @Test
    void executeHandlesIoExceptionFromExtract() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        // Use a spy to override extract() and throw IOException
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doThrow(new IOException("connection refused"))
            .when(spy).extract(anyString());

        ToolResult result = spy.execute("{\"urls\":[\"https://fail.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Failed to extract: connection refused");
    }

    // ── 6. Multiple URLs — processes each independently ───────────────────

    @Test
    void executeWithMultipleUrlsProcessesEach() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doReturn("Page A content").when(spy).extract("https://a.example");
        doReturn("Page B content").when(spy).extract("https://b.example");

        ToolResult result = spy.execute(
            "{\"urls\":[\"https://a.example\",\"https://b.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Page A content");
        assertThat(result.content()).contains("Page B content");
        assertThat(result.content()).contains("--- URL: https://a.example ---");
        assertThat(result.content()).contains("--- URL: https://b.example ---");
    }

    // ── 7. PDF content-type — returns helpful error, does not parse ───────

    @Test
    void executeDetectsPdfContentType() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Connection.Response response = mock(Connection.Response.class);
        when(response.contentType()).thenReturn("application/pdf");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(true)).thenReturn(connection);
        when(connection.execute()).thenReturn(response);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("doc.pdf"))).thenReturn(connection);

            WebExtractTool tool = newTool(p);
            ToolResult result = tool.execute("{\"urls\":[\"https://example.com/doc.pdf\"]}", null, session());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("PDF content detected");
            assertThat(result.content()).contains("cannot extract text from PDF");
            // parse() should never be called for PDF
            verify(response, never()).parse();
        }
    }

    // ── 8. Truncation when content exceeds maxChars ───────────────────────

    @Test
    void executeTruncatesContentExceedingMaxChars() throws Exception {
        AgentProperties p = properties();
        // Use a large maxChars so the clamping to [2000, 500000] doesn't override it
        p.getWeb().setExtractMaxChars(3000);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        // Return content longer than maxChars (3000)
        doReturn("line\n".repeat(2000)).when(spy).extract(anyString()); // 10_000 chars

        ToolResult result = spy.execute("{\"urls\":[\"https://long.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("[TRUNCATED]");
        // Head+tail truncation: should contain both head and tail content
        assertThat(result.content()).contains("middle omitted");
    }
}