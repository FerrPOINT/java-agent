package com.azhukov.agent.service.media;

import java.util.Map;
import java.util.Set;

/**
 * WP-10 (docs/35): common provider contract for media operations
 * (TTS/STT/image/vision).
 *
 * <p>Every provider publishes an HONEST capability row: what it supports,
 * which MIME types it accepts/produces, size limits, whether it is currently
 * configured (credential present), and a stable error taxonomy. Tools and
 * the dashboard consume the registry — unavailable providers are omitted or
 * disabled, never advertised as working.
 */
public interface MediaProvider {

    enum Operation {
        TTS, STT, IMAGE_GENERATE, IMAGE_EDIT, IMAGE_UPSCALE, VISION
    }

    /** Stable provider id, e.g. "openai", "edge". */
    String id();

    /** Media operations this provider implements. */
    Set<Operation> operations();

    /** Accepted input MIME types per operation (empty for output-only ops). */
    Map<Operation, Set<String>> inputMimeTypes();

    /** Produced output MIME types per operation. */
    Map<Operation, Set<String>> outputMimeTypes();

    /** Max input bytes per operation (0 = no provider-side limit documented). */
    default Map<Operation, Long> maxInputBytes() {
        return Map.of();
    }

    /** True when the provider has everything it needs to serve requests NOW. */
    boolean isConfigured();

    /** Human-readable reason when not configured (no secrets). */
    default String unavailableReason() {
        return isConfigured() ? null : "provider is not configured";
    }

    /** Sync or streaming execution mode. */
    default boolean supportsStreaming(Operation operation) {
        return false;
    }

    /** Stable error taxonomy shared by all providers. */
    class MediaProviderException extends RuntimeException {
        public enum Kind {
            NOT_CONFIGURED, UNSUPPORTED_OPERATION, UNSUPPORTED_MEDIA, TOO_LARGE,
            AUTH_FAILED, PROVIDER_ERROR, TIMEOUT, CANCELLED
        }

        private final Kind kind;

        public MediaProviderException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public MediaProviderException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
