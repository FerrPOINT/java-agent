package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * multiple URLs, and PDF content-type extraction.
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

    private JsonNode onlyResult(ToolResult result) throws Exception {
        JsonNode results = new ObjectMapper().readTree(result.content()).path("results");
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private JsonNode errorPayload(ToolResult result) throws Exception {
        JsonNode root = new ObjectMapper().readTree(result.content());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(result.error()).isEqualTo(root.path("error").asText());
        return root;
    }

    // ── 1. Valid URL — extract returns title + body, success ─────────────

    @Test
    void executeWithValidUrlReturnsExtractedContent() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Document doc = Jsoup.parse("<html><head><title>Hello</title></head><body><p>World</p></body></html>", "https://example.com");
        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(200);
        when(response.contentType()).thenReturn("text/html");
        when(response.parse()).thenReturn(doc);
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(false)).thenReturn(connection);
        when(connection.execute()).thenReturn(response);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("example.com"))).thenReturn(connection);

            WebExtractTool tool = newTool(p);
            ToolResult result = tool.execute("{\"urls\":[\"https://example.com\"]}", null, session());

            assertThat(result.success()).isTrue();
            JsonNode page = onlyResult(result);
            assertThat(page.path("url").asText()).isEqualTo("https://example.com");
            assertThat(page.path("title").asText()).isEqualTo("Hello");
            assertThat(page.path("content").asText()).contains("World");
            assertThat(page.path("error").isNull()).isTrue();
        }
    }

    // ── 2. Empty URL list → fail ──────────────────────────────────────────

    @Test
    void executeWithEmptyUrlsListFails() throws Exception {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        ToolResult result = tool.execute("{\"urls\":[]}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).path("error").asText()).contains("Content was inaccessible or not found");
    }

    // ── 3. Null args / invalid JSON → fail ────────────────────────────────

    @Test
    void executeWithNullUrlsFieldFails() throws Exception {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        // urls field omitted entirely → parsed as null
        ToolResult result = tool.execute("{}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).path("error").asText()).contains("Content was inaccessible or not found");
    }

    // ── 4. Blocked URL — appends safety message, does not extract ─────────

    @Test
    void executeWithBlockedUrlAppendsSafetyMessage() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(false);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        ToolResult result = tool.execute("{\"urls\":[\"https://blocked.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode page = onlyResult(result);
        assertThat(page.path("error").asText()).isEqualTo("URL blocked by safety policy");
        assertThat(page.path("blocked_by_policy").asBoolean()).isTrue();
    }

    @Test
    void executeBlocksUnsafeRedirectTarget() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed("https://public.example/start")).thenReturn(true);
        when(urlSafety.isUrlAllowed("http://127.0.0.1/private")).thenReturn(false);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(302);
        when(response.header("Location")).thenReturn("http://127.0.0.1/private");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(false)).thenReturn(connection);
        when(connection.execute()).thenReturn(response);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://public.example/start")).thenReturn(connection);

            WebExtractTool tool = newTool(p);
            ToolResult result = tool.execute("{\"urls\":[\"https://public.example/start\"]}", null, session());

            assertThat(result.success()).isTrue();
            JsonNode page = onlyResult(result);
            assertThat(page.path("error").asText())
                .isEqualTo("Failed to extract: Redirect blocked: URL blocked by safety policy");
            assertThat(page.path("blocked_by_policy").asBoolean()).isTrue();
        }
    }

    @Test
    void executeWithWebsitePolicyBlockDoesNotFetch() throws Exception {
        AgentProperties p = properties();
        p.getWeb().getBlockedDomains().add("blocked.example");
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);

        ToolResult result = spy.execute("{\"urls\":[\"https://sub.blocked.example/page\"]}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode page = onlyResult(result);
        assertThat(page.path("error").asText()).contains("Blocked by website policy");
        assertThat(page.path("blocked_by_policy").asBoolean()).isTrue();
        verify(spy, never()).extract(anyString());
        verify(urlSafety, never()).isUrlAllowed(anyString());
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
        assertThat(onlyResult(result).path("error").asText()).isEqualTo("Failed to extract: connection refused");
    }

    @Test
    void executeNormalizesIriUrlBeforeSafetyAndFetch() throws Exception {
        AgentProperties p = properties();
        String normalized = "https://wttr.in/K%C3%B6ln?q=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82%20world&unit=m";
        when(urlSafety.isUrlAllowed(normalized)).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doReturn("IRI content").when(spy).extract(normalized);

        ToolResult result = spy.execute("""
            {"urls":["https://wttr.in/Köln?q=привет world&unit=m"]}
            """, null, session());

        assertThat(result.success()).isTrue();
        JsonNode page = onlyResult(result);
        assertThat(page.path("url").asText()).isEqualTo(normalized);
        assertThat(page.path("content").asText()).isEqualTo("IRI content");
        verify(urlSafety).isUrlAllowed(normalized);
        verify(spy).extract(normalized);
    }

    @Test
    void executeRepairsWhitespaceAfterSchemeBeforeSafetyAndFetch() throws Exception {
        AgentProperties p = properties();
        String normalized = "https://docs.example/a%20b";
        when(urlSafety.isUrlAllowed(normalized)).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doReturn("Docs content").when(spy).extract(normalized);

        ToolResult result = spy.execute("{\"urls\":[\"https:// docs.example/a b\"]}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode page = onlyResult(result);
        assertThat(page.path("url").asText()).isEqualTo(normalized);
        assertThat(page.path("content").asText()).isEqualTo("Docs content");
        verify(urlSafety).isUrlAllowed(normalized);
        verify(spy).extract(normalized);
    }

    @Test
    void executeBlocksSensitiveParamAfterEarlierQueryParamWhenNormalizing() throws Exception {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"urls\":[\"https://example.com/path?foo=bar&token=opaque secret\"]}",
            null,
            session());

        assertThat(result.success()).isFalse();
        JsonNode root = errorPayload(result);
        assertThat(root.path("error").asText()).contains("credential-like query parameter (token)");
        verify(urlSafety, never()).isUrlAllowed(anyString());
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
        JsonNode pages = new ObjectMapper().readTree(result.content()).path("results");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).path("content").asText()).isEqualTo("Page A content");
        assertThat(pages.get(1).path("content").asText()).isEqualTo("Page B content");
        assertThat(pages.get(0).path("url").asText()).isEqualTo("https://a.example");
        assertThat(pages.get(1).path("url").asText()).isEqualTo("https://b.example");
    }

    // ── 7. PDF content-type — extracts PDF text without HTML parsing ──────

    @Test
    void executeExtractsPdfContentTypeLikeHermes() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(200);
        when(response.contentType()).thenReturn("application/pdf");
        when(response.bodyAsBytes()).thenReturn(samplePdf("Sample PDF", "Hello from PDF"));
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.followRedirects(false)).thenReturn(connection);
        when(connection.execute()).thenReturn(response);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("doc.pdf"))).thenReturn(connection);

            WebExtractTool tool = newTool(p);
            ToolResult result = tool.execute("{\"urls\":[\"https://example.com/doc.pdf\"]}", null, session());

            assertThat(result.success()).isTrue();
            JsonNode page = onlyResult(result);
            assertThat(page.path("error").isNull()).isTrue();
            assertThat(page.path("title").asText()).isEqualTo("Sample PDF");
            assertThat(page.path("content").asText()).contains("Hello from PDF");
            verify(response, never()).parse();
        }
    }

    // ── 8. Truncation when content exceeds maxChars ───────────────────────

    @Test
    void executeTruncatesContentExceedingMaxChars(@TempDir Path cacheDir) throws Exception {
        AgentProperties p = properties();
        // Use a large maxChars so the clamping to [2000, 500000] doesn't override it
        p.getWeb().setExtractMaxChars(3000);
        p.getWeb().setExtractCacheDir(cacheDir.toString());
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        // Return content longer than maxChars (3000)
        String fullContent = "line\n".repeat(2000); // 10_000 chars
        doReturn(fullContent).when(spy).extract(anyString());

        ToolResult result = spy.execute("{\"urls\":[\"https://long.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        String content = onlyResult(result).path("content").asText();
        assertThat(content).contains("[TRUNCATED]");
        // Head+tail truncation: should contain both head and tail content
        assertThat(content).contains("middle omitted — see footer");
        assertThat(content).contains("Full text saved to:");
        assertThat(content).contains("read_file path=\"");

        Path storedPath = Path.of(extractStoredPath(content));
        assertThat(storedPath).exists();
        assertThat(storedPath.getParent()).isEqualTo(cacheDir.toAbsolutePath().normalize());
        assertThat(Files.readString(storedPath)).isEqualTo(fullContent);
    }

    @Test
    void executeAcceptsSearchResultObjectsAndKeepsInvalidItemsInOrder() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doReturn("Object URL content").when(spy).extract("https://object.example");
        doReturn("Href content").when(spy).extract("https://href.example");

        ToolResult result = spy.execute("""
            {"urls":[
              {"title":"Search result","url":"https://object.example"},
              {"href":"https://href.example"},
              {"title":"Missing url"},
              42
            ]}
            """, null, session());

        assertThat(result.success()).isTrue();
        JsonNode pages = new ObjectMapper().readTree(result.content()).path("results");
        assertThat(pages).hasSize(4);
        assertThat(pages.get(0).path("url").asText()).isEqualTo("https://object.example");
        assertThat(pages.get(0).path("content").asText()).isEqualTo("Object URL content");
        assertThat(pages.get(1).path("url").asText()).isEqualTo("https://href.example");
        assertThat(pages.get(1).path("content").asText()).isEqualTo("Href content");
        assertThat(pages.get(2).path("error").asText())
            .isEqualTo("Invalid URL item at index 2: expected a URL string or an object with a string 'url' or 'href' field");
        assertThat(pages.get(3).path("error").asText())
            .isEqualTo("Invalid URL item at index 3: expected a URL string or an object with a string 'url' or 'href' field");
    }

    @Test
    void executeReplacesInlineBase64ImagesWithPlaceholders() throws Exception {
        AgentProperties p = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WebExtractTool tool = newTool(p);
        WebExtractTool spy = org.mockito.Mockito.spy(tool);
        doReturn("""
            ![diagram](data:image/png;base64,AAAA)
            (data:image/jpeg;base64,BBBB)
            data:image/gif;base64,CCCC
            ![real](https://example.com/image.png)
            """).when(spy).extract(anyString());

        ToolResult result = spy.execute("{\"urls\":[\"https://images.example\"]}", null, session());

        assertThat(result.success()).isTrue();
        String content = onlyResult(result).path("content").asText();
        assertThat(content).contains("[IMAGE: diagram]");
        assertThat(content).contains("[IMAGE]");
        assertThat(content).contains("![real](https://example.com/image.png)");
        assertThat(content).doesNotContain("base64,AAAA");
        assertThat(content).doesNotContain("base64,BBBB");
        assertThat(content).doesNotContain("base64,CCCC");
    }

    @Test
    void executeBlocksSecretBearingUrlsBeforeFetch() throws Exception {
        AgentProperties p = properties();
        WebExtractTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"urls\":[\"https://example.com/callback?token=opaque-secret\"]}",
            null,
            session());

        assertThat(result.success()).isFalse();
        JsonNode root = errorPayload(result);
        assertThat(root.path("error").asText()).contains("credential-like query parameter (token)");
    }

    private String extractStoredPath(String content) {
        Matcher matcher = Pattern.compile("Full text saved to: (.+)").matcher(content);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1).trim();
    }

    private byte[] samplePdf(String title, String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle(title);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
