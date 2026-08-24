package com.azhukov.agent.client.langchain4j;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Hermes parity (agent/message_sanitization.py:655-664):
 * Classifies the provider for reasoning_content echo-back policy.
 *
 * DeepSeek/Kimi/MiMo require reasoning_content on assistant tool-call
 * messages when replaying history; strict OpenAI-compatible providers
 * (Mistral, Cerebras, Groq, SambaNova) reject it with 400/422.
 *
 * Detection: provider name, model substring, base_url host matching.
 */
@Slf4j
public final class ReasoningEchoFamily {

    public enum Family { KIMI, DEEPSEEK, MIMO, NONE }

    // (family, raw providers, lowered providers, model substrings, base_url hosts)
    private static final Rule[] RULES = {
        new Rule(Family.KIMI,
            Set.of("kimi-coding", "kimi-coding-cn"),
            Set.of(),
            Set.of(),
            Set.of("api.kimi.com", "moonshot.ai", "moonshot.cn")),
        new Rule(Family.DEEPSEEK,
            Set.of(),
            Set.of("deepseek"),
            Set.of("deepseek"),
            Set.of("api.deepseek.com")),
        new Rule(Family.MIMO,
            Set.of(),
            Set.of("xiaomi"),
            Set.of("mimo"),
            Set.of("api.xiaomimimo.com", "xiaomimimo.com")),
    };

    /**
     * Detect the reasoning_content echo family for a provider/model/baseURL.
     * Returns the first matching family, or NONE.
     */
    public static Family detect(String provider, String model, String baseUrl) {
        String providerLower = provider == null ? "" : provider.toLowerCase();
        String modelLower = model == null ? "" : model.toLowerCase();
        String baseLower = baseUrl == null ? "" : baseUrl.toLowerCase();

        for (Rule rule : RULES) {
            // Raw provider match
            if (provider != null && rule.rawProviders.contains(provider)) {
                return rule.family;
            }
            // Lowered provider match
            if (!providerLower.isEmpty() && rule.loweredProviders.contains(providerLower)) {
                return rule.family;
            }
            // Model substring match
            for (String sub : rule.modelSubstrings) {
                if (modelLower.contains(sub)) return rule.family;
            }
            // Base URL host match
            for (String host : rule.baseUrlHosts) {
                if (baseLower.contains(host)) return rule.family;
            }
        }
        return Family.NONE;
    }

    /**
     * Whether the endpoint requires reasoning_content echo-back.
     */
    public static boolean needsReasoningEcho(String provider, String model, String baseUrl) {
        return detect(provider, model, baseUrl) != Family.NONE;
    }

    private record Rule(Family family, Set<String> rawProviders,
                        Set<String> loweredProviders,
                        Set<String> modelSubstrings,
                        Set<String> baseUrlHosts) {}
}