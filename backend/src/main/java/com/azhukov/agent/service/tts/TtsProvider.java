package com.azhukov.agent.service.tts;

/**
 * Provider interface for text-to-speech synthesis.
 */
public interface TtsProvider {

    /**
     * Synthesize text into audio.
     *
     * @param text  the text to synthesize
     * @param voice the voice to use (provider-specific, may be ignored)
     * @return the synthesized audio as raw bytes (MP3/OGG)
     */
    byte[] synthesize(String text, String voice);
}