package com.azhukov.agent.service.tts;

/**
 * Provider interface for text-to-speech synthesis.
 */
public interface TtsProvider {

    /** Returns the configured provider name used to select this implementation. */
    String name();

    /**
     * Synthesize with optional provider-native controls. Implementations that
     * do not support a control ignore it while preserving the core behavior.
     */
    default byte[] synthesize(String text, String voice, Double speed, String instructions) {
        return synthesize(text, voice);
    }

    /**
     * Basic synthesis contract retained for providers that do not expose
     * provider-native speed/instructions controls.
     *
     * @param text text to synthesize
     * @param voice provider-specific voice
     * @return synthesized audio bytes
     */
    byte[] synthesize(String text, String voice);
}