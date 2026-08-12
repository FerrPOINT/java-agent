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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * L35 test: verify that WebExtractTool sets followRedirects(false) on the Jsoup
 * connection to prevent SSRF via redirect following.
 */
@ExtendWith(MockitoExtension.class)
class WebExtractToolRedirectTest {

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
    void extractSetsFollowRedirectsFalse() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));

        Document doc = Jsoup.parse("<html><head><title>Test</title></head><body><p>Hello</p></body></html>", "https://example.com");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(eq(10000))).thenReturn(connection);
        when(connection.followRedirects(eq(false))).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect("https://example.com")).thenReturn(connection);

            WebExtractTool tool = new WebExtractTool(properties, urlSafety, redactor);
            tool.init();
            var result = tool.execute("{\"urls\":\"https://example.com\"}", null, null);

            assertThat(result.success()).isTrue();
            // Verify followRedirects(false) was called on the connection
            verify(connection).followRedirects(eq(false));
        }
    }
}