package com.azhukov.agent.tools.browser.backend;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.tools.web.WebsitePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * WP-8: browser backend router — explicit selection matrix, fail-closed
 * unknown provider, shared URL guard, no silent cloud fallback.
 */
@ExtendWith(MockitoExtension.class)
class BrowserBackendRouterTest {

    @Mock
    private UrlSafety urlSafety;

    @Mock
    private WebsitePolicy websitePolicy;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private AgentProperties properties(String provider) {
        AgentProperties properties = new AgentProperties();
        properties.getBrowser().setCloudProvider(provider);
        return properties;
    }

    private BrowserBackendRouter router(String provider, List<BrowserBackend> backends) {
        return new BrowserBackendRouter(prov(backends), prov(urlSafety),
            prov(websitePolicy), properties(provider));
    }

    @Test
    void localProviderResolvesToNullLocalCdpPath() throws Exception {
        assertThat(router("local", List.of()).resolve()).isNull();
        assertThat(router("", List.of()).resolve()).isNull();
        assertThat(router(null, List.of()).resolve()).isNull();
    }

    @Test
    void unknownProviderFailsClosed() {
        var router = router("camofox", List.of());
        assertThatThrownBy(router::resolve)
            .isInstanceOf(BrowserBackend.BrowserBackendException.class)
            .hasMessageContaining("names no registered backend")
            .hasMessageContaining("camofox");
    }

    @Test
    void extensionProviderRequiresConfiguration() {
        // backend registered but not configured (no token)
        ExtensionBrowserBackend unconfigured = new ExtensionBrowserBackend(
            "http://127.0.0.1:8819", "");
        var router = router("extension", List.of(unconfigured));
        assertThatThrownBy(router::resolve)
            .isInstanceOf(BrowserBackend.BrowserBackendException.class)
            .hasMessageContaining("not configured")
            .hasMessageContaining("token missing");
    }

    @Test
    void configuredExtensionProviderResolves() throws Exception {
        ExtensionBrowserBackend configured = new ExtensionBrowserBackend(
            "http://127.0.0.1:8819", "secret-token");
        var router = router("extension", List.of(configured));

        BrowserBackend resolved = router.resolve();
        assertThat(resolved).isSameAs(configured);
        assertThat(resolved.capabilities()).contains(BrowserBackend.Capability.NAVIGATE);
        assertThat(resolved.capabilities()).doesNotContain(BrowserBackend.Capability.RAW_CDP);
    }

    @Test
    void backendHintNeverSelectsNonLocal() {
        // backend=extension (runtime hint) with cloud-provider=local stays local
        AgentProperties props = properties("local");
        props.getBrowser().setBackend("extension");
        var router = new BrowserBackendRouter(prov(List.<BrowserBackend>of()), prov(urlSafety),
            prov(websitePolicy), props);
        try {
            assertThat(router.resolve()).isNull(); // local CDP, hint ignored for routing
        } catch (BrowserBackend.BrowserBackendException e) {
            throw new AssertionError("hint must not route to cloud", e);
        }
    }

    @Test
    void urlGuardBlocksPrivateNetworksAndPolicySites() throws BrowserBackend.BrowserBackendException {
        lenient().when(urlSafety.isUrlAllowed("http://169.254.169.254/latest/meta-data"))
            .thenReturn(false);
        lenient().when(urlSafety.isUrlAllowed("https://example.com/ok"))
            .thenReturn(true);
        lenient().when(urlSafety.isUrlAllowed("https://denied.example/"))
            .thenReturn(true);
        lenient().when(websitePolicy.isAllowed("https://example.com/ok")).thenReturn(true);
        lenient().when(websitePolicy.isAllowed("https://denied.example/")).thenReturn(false);

        var router = router("local", List.of());
        BrowserBackend.UrlGuard guard = router.urlGuard();

        assertThatThrownBy(() -> guard.check(java.net.URI.create("http://169.254.169.254/latest/meta-data")))
            .isInstanceOf(BrowserBackend.BrowserBackendException.class)
            .hasMessageContaining("URL safety policy");
        assertThatThrownBy(() -> guard.check(java.net.URI.create("https://denied.example/")))
            .isInstanceOf(BrowserBackend.BrowserBackendException.class)
            .hasMessageContaining("website policy");
        // allowed URL passes
        guard.check(java.net.URI.create("https://example.com/ok"));
        assertThat(guard.describe()).containsKeys("url_safety", "website_policy");
    }

    @Test
    void describeProvidersReportsSelectionAndAvailability() {
        ExtensionBrowserBackend unconfigured = new ExtensionBrowserBackend(
            "http://127.0.0.1:8819", "");
        var router = router("extension", List.of(unconfigured));

        List<BrowserBackendRouter.ProviderInfo> info = router.describeProviders();

        assertThat(info).extracting(BrowserBackendRouter.ProviderInfo::id)
            .containsExactly("local", "extension");
        assertThat(info.get(0).selected()).isFalse(); // local not selected
        assertThat(info.get(1).selected()).isTrue();  // extension selected
        assertThat(info.get(1).available()).isFalse();
        assertThat(info.get(1).unavailableReason()).contains("not configured");
    }
}
