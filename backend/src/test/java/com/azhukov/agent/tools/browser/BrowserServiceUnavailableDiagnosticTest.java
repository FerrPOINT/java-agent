package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.security.UrlSafety;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the browser-unavailable diagnostics: when the CDP endpoint
 * is unreachable, every browser tool must return one actionable message naming the
 * configured endpoint instead of a raw java.net.ConnectException stack fragment.
 */
class BrowserServiceUnavailableDiagnosticTest {

    private static CdpClient refusingCdpClient() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(false);
        doThrow(new java.net.ConnectException("Connection refused"))
            .when(client).connect(anyString());
        when(client.send(any(), any())).thenReturn(CompletableFuture.failedFuture(
            new IllegalStateException("CDP client disconnected")));
        return client;
    }

    private static UrlSafety permissiveSafety() {
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);
        when(safety.isHostBlocked(anyString())).thenReturn(false);
        return safety;
    }

    @Test
    void navigate_namesConfiguredEndpointWhenCdpUnreachable() throws Exception {
        CdpClient client = refusingCdpClient();
        BrowserService service = new BrowserService(
            client, () -> "http://localhost:9222", permissiveSafety());

        String result = service.navigate("https://example.com");

        assertThat(result)
            .contains("Browser CDP is unavailable at http://localhost:9222")
            .contains("agent.browser.cdp-url")
            .doesNotContain("java.net.ConnectException");
        verify(client).connect(eq("http://localhost:9222"));
    }

    @Test
    void navigate_reportsEmptyEndpointPlaceholderWhenCdpUrlBlank() throws Exception {
        CdpClient client = refusingCdpClient();
        BrowserService service = new BrowserService(
            client, () -> " ", permissiveSafety());

        String result = service.navigate("https://example.com");

        assertThat(result).contains("Browser CDP is unavailable at <empty>");
    }

    @Test
    void navigate_reportsExternalEndpointWhenChromiumNotRunning() throws Exception {
        CdpClient client = refusingCdpClient();
        BrowserService service = new BrowserService(
            client, () -> "http://host.docker.internal:9222", permissiveSafety());

        String result = service.navigate("https://example.com");

        assertThat(result).contains("Browser CDP is unavailable at http://host.docker.internal:9222");
    }

    @Test
    void dialog_namesConfiguredEndpointWhenCdpUnreachable() throws Exception {
        CdpClient client = refusingCdpClient();
        BrowserService service = new BrowserService(
            client, () -> "http://localhost:9222", permissiveSafety());

        String result = service.handleDialog(true, null);

        assertThat(result)
            .startsWith("Dialog error: Browser CDP is unavailable at http://localhost:9222")
            .doesNotContain("java.net.ConnectException");
    }

    @Test
    void snapshot_namesConfiguredEndpointWhenCdpUnreachable() throws Exception {
        CdpClient client = refusingCdpClient();
        BrowserService service = new BrowserService(
            client, () -> "http://localhost:9222", permissiveSafety());

        String result;
        try {
            result = service.accessibilitySnapshot(false);
        } catch (IllegalStateException e) {
            result = e.getMessage();
        }

        assertThat(result).contains("Browser CDP is unavailable at http://localhost:9222");
    }
}
