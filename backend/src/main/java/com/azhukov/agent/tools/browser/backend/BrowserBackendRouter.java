package com.azhukov.agent.tools.browser.backend;

import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.tools.web.WebsitePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WP-8 (docs/35): browser provider router.
 *
 * <p>Selection is EXPLICIT: only {@code agent.browser.cloud-provider} picks a
 * non-local backend (fail-closed when it names an unknown provider). The
 * runtime {@code browser.backend} hint is reported but never routes traffic.
 * The SSRF/private-network + website policy guard runs BEFORE dispatch and is
 * re-validated on the final post-redirect URL for every provider.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrowserBackendRouter {

    /** Supported explicit provider values. */
    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_EXTENSION = "extension";

    private final ObjectProvider<List<BrowserBackend>> backendsProvider;
    private final ObjectProvider<UrlSafety> urlSafetyProvider;
    private final ObjectProvider<WebsitePolicy> websitePolicyProvider;
    private final com.azhukov.agent.config.AgentProperties properties;

    public record ProviderInfo(String id, boolean selected, Set<BrowserBackend.Capability> capabilities,
                               boolean available, String unavailableReason) {}

    /** Backends registered in this build, with selection state. */
    public List<ProviderInfo> describeProviders() {
        String selected = selectedProvider();
        List<BrowserBackend> registered = backends();
        List<ProviderInfo> info = new java.util.ArrayList<>();
        info.add(new ProviderInfo(PROVIDER_LOCAL, PROVIDER_LOCAL.equals(selected),
            Set.of(BrowserBackend.Capability.values()), true, null));
        for (BrowserBackend backend : registered) {
            boolean isSelected = backend.id().equals(selected);
            boolean available = backend instanceof ExtensionBrowserBackend ext && ext.isConfigured();
            info.add(new ProviderInfo(backend.id(), isSelected, backend.capabilities(),
                available, available ? null
                    : "provider is registered but not configured (missing authorization)"));
        }
        return info;
    }

    /** Resolve the backend for the explicitly configured provider; null = local CDP. */
    public BrowserBackend resolve() throws BrowserBackend.BrowserBackendException {
        String selected = selectedProvider();
        if (selected == null || selected.isBlank() || PROVIDER_LOCAL.equals(selected)) {
            return null; // caller uses the local CDP path
        }
        for (BrowserBackend backend : backends()) {
            if (backend.id().equals(selected)) {
                if (backend instanceof ExtensionBrowserBackend ext && !ext.isConfigured()) {
                    throw new BrowserBackend.BrowserBackendException(
                        BrowserBackend.BrowserBackendException.Kind.AUTH_FAILED,
                        "browser provider 'extension' is selected but not configured "
                            + "(agent.browser.extension.token missing)");
                }
                return backend;
            }
        }
        throw new BrowserBackend.BrowserBackendException(
            BrowserBackend.BrowserBackendException.Kind.UNSUPPORTED_OPERATION,
            "browser.cloud-provider='" + selected + "' names no registered backend "
                + "(registered: local" + backends().stream()
                    .map(b -> ", " + b.id()).reduce(String::concat).orElse("") + ")");
    }

    /** Shared URL guard: SSRF/private-network + website policy for every provider. */
    public BrowserBackend.UrlGuard urlGuard() {
        UrlSafety urlSafety = urlSafetyProvider == null ? null : urlSafetyProvider.getIfAvailable();
        WebsitePolicy websitePolicy = websitePolicyProvider == null
            ? null : websitePolicyProvider.getIfAvailable();
        return new BrowserBackend.UrlGuard() {
            @Override
            public void check(URI url) throws BrowserBackend.BrowserBackendException {
                if (url == null) {
                    throw new BrowserBackend.BrowserBackendException(
                        BrowserBackend.BrowserBackendException.Kind.URL_BLOCKED, "null url");
                }
                try {
                    if (urlSafety != null && !urlSafety.isUrlAllowed(url.toString())) {
                        throw new BrowserBackend.BrowserBackendException(
                            BrowserBackend.BrowserBackendException.Kind.URL_BLOCKED,
                            "blocked by URL safety policy (private network or SSRF): " + url);
                    }
                } catch (BrowserBackend.BrowserBackendException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BrowserBackend.BrowserBackendException(
                        BrowserBackend.BrowserBackendException.Kind.URL_BLOCKED,
                        "URL safety check failed for " + url + ": " + e.getMessage());
                }
                try {
                    if (websitePolicy != null && !websitePolicy.isAllowed(url.toString())) {
                        throw new BrowserBackend.BrowserBackendException(
                            BrowserBackend.BrowserBackendException.Kind.URL_BLOCKED,
                            "blocked by website policy: " + url.getHost());
                    }
                } catch (BrowserBackend.BrowserBackendException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BrowserBackend.BrowserBackendException(
                        BrowserBackend.BrowserBackendException.Kind.URL_BLOCKED,
                        "website policy check failed for " + url + ": " + e.getMessage());
                }
            }

            @Override
            public Map<String, Object> describe() {
                Map<String, Object> description = new LinkedHashMap<>();
                description.put("url_safety", urlSafety != null);
                description.put("website_policy", websitePolicy != null);
                return description;
            }
        };
    }

    private String selectedProvider() {
        String provider = properties == null || properties.getBrowser() == null
            ? PROVIDER_LOCAL
            : properties.getBrowser().getCloudProvider();
        return provider == null ? PROVIDER_LOCAL : provider.trim().toLowerCase();
    }

    private List<BrowserBackend> backends() {
        List<BrowserBackend> backends = backendsProvider == null
            ? null : backendsProvider.getIfAvailable();
        return backends == null ? List.of() : backends;
    }
}
