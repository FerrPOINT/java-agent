package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.security.UrlSafety;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserServiceMissingEndpointTest {

    @Test
    void handlesMissingBrowserEndpoint() throws Exception {
        String cdpUrl = "http://no-such-host:9999";
        UrlSafety urlSafety = new UrlSafety() {
            @Override
            public boolean isUrlAllowed(String url) {
                return "https://example.com".equals(url);
            }

            @Override
            public boolean isHostBlocked(String host) {
                return false;
            }
        };
        CdpClient cdpClient = new ThrowingCdpClient();
        BrowserService service = new BrowserService(cdpClient, () -> cdpUrl, urlSafety);

        String result = service.navigate("https://example.com");

        assertThat(result).contains("Navigation error").contains("No targets available");
    }

    static class ThrowingCdpClient extends CdpClient {
        ThrowingCdpClient() {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public void connect(String cdpBaseUrl) {
            throw new RuntimeException("No targets available at " + cdpBaseUrl);
        }

        @Override
        public boolean isConnected() {
            return false;
        }
    }
}
