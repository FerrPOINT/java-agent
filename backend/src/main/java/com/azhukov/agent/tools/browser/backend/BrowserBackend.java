package com.azhukov.agent.tools.browser.backend;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WP-8 (docs/35): browser backend capability port. Every non-local browser
 * provider implements this interface; the router selects by explicit
 * {@code agent.browser.cloud-provider} config — the runtime {@code backend}
 * hint NEVER silently selects a cloud backend.
 *
 * <p>The URL policy (SSRF/private-network + website policy) is centralized in
 * the router BEFORE provider dispatch and re-checked after navigation
 * redirects — every provider inherits the same boundary.
 */
public interface BrowserBackend {

    /** Stable provider id, e.g. "extension". */
    String id();

    /** Capabilities this backend actually supports (no advertising beyond). */
    Set<Capability> capabilities();

    /** Normalize provider-specific failures into a stable error taxonomy. */
    BrowserBackendException normalizeError(Exception raw);

    /** Open (or reuse) a session; the lease is owned by the router. */
    SessionHandle openSession(URI targetUrl, UrlGuard guard) throws BrowserBackendException;

    enum Capability {
        NAVIGATE, DOM_SNAPSHOT, CLICK, TYPE, SCREENSHOT, VISION, RAW_CDP, SESSION_LIFECYCLE
    }

    /** Opaque provider session handle; close() must be idempotent. */
    interface SessionHandle extends AutoCloseable {
        String sessionId();

        /** Navigate and re-validate the FINAL url through the guard (redirect-safe). */
        NavigationResult navigate(URI url, UrlGuard guard) throws BrowserBackendException;

        /** DOM snapshot text (provider-specific extraction). */
        String domSnapshot() throws BrowserBackendException;

        void click(String selector) throws BrowserBackendException;

        void type(String selector, String text) throws BrowserBackendException;

        /** Path to the captured screenshot file (provider-specific transport). */
        java.nio.file.Path screenshot() throws BrowserBackendException;
    }

    record NavigationResult(String finalUrl, int status, String redirectedFrom) {}

    /**
     * Router-supplied URL guard: providers MUST call check() before any
     * navigation attempt; the router calls it again on the final URL after
     * redirects. Private/loopback/link-local networks and policy-denied
     * sites are rejected identically for every provider.
     */
    interface UrlGuard {
        void check(URI url) throws BrowserBackendException;

        /** Guard snapshot for diagnostics (never secrets). */
        Map<String, Object> describe();
    }

    /** Stable error taxonomy shared across providers. */
    class BrowserBackendException extends Exception {
        public enum Kind {
            URL_BLOCKED, UNSUPPORTED_OPERATION, PROVIDER_UNAVAILABLE, AUTH_FAILED, TIMEOUT, SESSION_LOST
        }

        private final Kind kind;

        public BrowserBackendException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public BrowserBackendException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
