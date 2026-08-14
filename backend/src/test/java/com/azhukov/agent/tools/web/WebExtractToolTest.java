package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
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

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebExtractToolTest {

    @Mock
    private UrlSafety urlSafety;

    @Mock
    private Redactor redactor;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getWeb().setExtractTimeoutSeconds(10);
        p.getWeb().setExtractMaxChars(10000);
        p.getSecurity().setRedactEnabled(false);
        return p;
    }

    @Test
    void extractsReadableTextFromHtml() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Example Domain</title></head>
            <body>
              <header>Header text</header>
              <nav>Nav links</nav>
              <script>alert('ignored');</script>
              <style>body { color: black; }</style>
              <main>
                <h1>Example Domain</h1>
                <p>This domain is for use in illustrative examples.</p>
              </main>
              <aside>Aside content</aside>
              <footer>Footer text</footer>
            </body>
            </html>
            """;

        Document doc = Jsoup.parse(html, "https://example.com");
        Connection connection = mock(Connection.class);
        Connection.Response response = mock(Connection.Response.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(eq(10000))).thenReturn(connection);
        when(connection.followRedirects(eq(false))).thenReturn(connection);
        when(connection.execute()).thenReturn(response);
        when(response.contentType()).thenReturn("text/html");
        when(response.parse()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://example.com")).thenReturn(connection);

            WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"urls\":[\"https://example.com\"]}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content())
                .contains("--- URL: https://example.com ---")
                .contains("Title: Example Domain")
                .contains("Example Domain")
                .contains("This domain is for use in illustrative examples")
                .doesNotContain("Header text")
                .doesNotContain("Nav links")
                .doesNotContain("alert")
                .doesNotContain("color: black")
                .doesNotContain("Aside content")
                .doesNotContain("Footer text");
        }
    }

    @Test
    void extractsMultipleUrls() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        Document doc1 = Jsoup.parse("<html><head><title>One</title></head><body><p>First page.</p></body></html>", "https://one.com");
        Document doc2 = Jsoup.parse("<html><head><title>Two</title></head><body><p>Second page.</p></body></html>", "https://two.com");

        Connection conn1 = mock(Connection.class);
        Connection.Response resp1 = mock(Connection.Response.class);
        when(conn1.userAgent(anyString())).thenReturn(conn1);
        when(conn1.timeout(eq(10000))).thenReturn(conn1);
        when(conn1.followRedirects(eq(false))).thenReturn(conn1);
        when(conn1.execute()).thenReturn(resp1);
        when(resp1.contentType()).thenReturn("text/html");
        when(resp1.parse()).thenReturn(doc1);

        Connection conn2 = mock(Connection.class);
        Connection.Response resp2 = mock(Connection.Response.class);
        when(conn2.userAgent(anyString())).thenReturn(conn2);
        when(conn2.timeout(eq(10000))).thenReturn(conn2);
        when(conn2.followRedirects(eq(false))).thenReturn(conn2);
        when(conn2.execute()).thenReturn(resp2);
        when(resp2.contentType()).thenReturn("text/html");
        when(resp2.parse()).thenReturn(doc2);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://one.com")).thenReturn(conn1);
            jsoup.when(() -> Jsoup.connect("https://two.com")).thenReturn(conn2);

            WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"urls\":[\"https://one.com\",\"https://two.com\"]}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content())
                .contains("--- URL: https://one.com ---")
                .contains("--- URL: https://two.com ---")
                .contains("Title: One")
                .contains("First page")
                .contains("Title: Two")
                .contains("Second page");
        }
    }

    @Test
    void blocksUnsafeUrl() {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed("https://evil.com")).thenReturn(false);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
        var result = tool.execute("{\"urls\":[\"https://evil.com\"]}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("URL blocked by safety policy");
        assertThat(result.content()).doesNotContain("Title:");
    }

    @Test
    void handlesInvalidUrl() {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed("not-a-url")).thenReturn(false);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
        var result = tool.execute("{\"urls\":[\"not-a-url\"]}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("--- URL: not-a-url ---");
        assertThat(result.content()).contains("URL blocked by safety policy");
    }

    @Test
    void handlesEmptyResult() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        Document doc = Jsoup.parse("<html><head><title></title></head><body></body></html>", "https://empty.example");
        Connection connection = mock(Connection.class);
        Connection.Response response = mock(Connection.Response.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(eq(10000))).thenReturn(connection);
        when(connection.followRedirects(eq(false))).thenReturn(connection);
        when(connection.execute()).thenReturn(response);
        when(response.contentType()).thenReturn("text/html");
        when(response.parse()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://empty.example")).thenReturn(connection);

            WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"urls\":[\"https://empty.example\"]}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("--- URL: https://empty.example ---");
        }
    }

    @Test
    void handlesIoExceptionPerUrl() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor) {
            @Override protected String extract(String url) throws IOException {
                throw new IOException("connection reset");
            }
        };

        tool.init();
        var result = tool.execute("{\"urls\":[\"https://down.example\"]}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content())
            .contains("--- URL: https://down.example ---")
            .contains("Failed to extract: connection reset");
    }

    @Test
    void requiresUrlsArgument() {
        AgentProperties properties = properties();
        WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();

        var result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URLs are required");
    }

    @Test
    void truncatesOversizedOutput() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setExtractMaxChars(50);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        Document doc = Jsoup.parse("<html><head><title>T</title></head><body><p>" + "x".repeat(200) + "</p></body></html>", "https://long.example");
        Connection connection = mock(Connection.class);
        Connection.Response response = mock(Connection.Response.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(eq(10000))).thenReturn(connection);
        when(connection.followRedirects(eq(false))).thenReturn(connection);
        when(connection.execute()).thenReturn(response);
        when(response.contentType()).thenReturn("text/html");
        when(response.parse()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://long.example")).thenReturn(connection);

            WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"urls\":[\"https://long.example\"]}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).endsWith("\n[truncated]");
            assertThat(result.content().length()).isLessThanOrEqualTo(50 + "\n[truncated]".length());
        }
    }
}
