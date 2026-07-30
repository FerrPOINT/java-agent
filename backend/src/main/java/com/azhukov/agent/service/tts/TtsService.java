package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Service for text-to-speech synthesis.
 * Delegates to the available {@link TtsProvider} if TTS is enabled.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TtsService {

    private final ObjectProvider<TtsProvider> providerProvider;
    private final AgentProperties properties;


    /**
     * Synthesize text to speech audio.
     *
     * @param text  the text to synthesize
     * @param voice the voice to use (may be null — falls back to configured default)
     * @return the synthesized audio bytes (MP3)
     */
    public byte[] synthesize(String text, String voice) {
        TtsProvider provider = providerProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("TTS is not enabled. Set agent.tts.enabled=true to use this feature.");
        }
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : properties.getTts().getVoice();
        return provider.synthesize(text, usedVoice);
    }

    /**
     * Check if TTS is available.
     */
    public boolean isAvailable() {
        return providerProvider.getIfAvailable() != null;
    }
}