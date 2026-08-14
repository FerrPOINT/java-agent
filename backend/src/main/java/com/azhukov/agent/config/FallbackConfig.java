package com.azhukov.agent.config;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for a single fallback model in the fallback chain.
 * <p>
 * Mirrors Hermes fallback chain entries: each entry specifies an alternate
 * provider, model, base URL, and optional API key to use when the primary
 * model fails.
 */
@Getter
@Setter
public class FallbackConfig {
    private String provider = "openai-compatible";
    private String model = "";
    private String baseUrl = "";
    private String apiKey = "";
}