package com.azhukov.agent.gateway;

import java.util.Map;

/**
 * Optional platform adapter contract for Hermes-style HTTP event callbacks.
 */
public interface HttpEventPlatformAdapter extends BasePlatformAdapter {

    VerificationResult verifyHttpEventRequest(String authorizationHeader);

    Object dispatchHttpEvent(Map<String, Object> payload);

    record VerificationResult(boolean ok, String code) {
        public static VerificationResult accepted() {
            return new VerificationResult(true, null);
        }

        public static VerificationResult denied(String code) {
            return new VerificationResult(false, code);
        }
    }
}
