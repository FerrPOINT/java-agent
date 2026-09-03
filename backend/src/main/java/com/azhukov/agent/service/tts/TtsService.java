package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Locale;

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
        TtsProvider provider = selectedProvider();
        if (provider == null) {
            throw new IllegalStateException("TTS is not enabled. Set agent.tts.enabled=true to use this feature.");
        }
        String spokenText = SpokenTextNormalizer.normalize(text);
        if (spokenText.isBlank()) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Text is required");
            }
            throw new IllegalArgumentException("Text is empty after TTS cleanup");
        }
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : properties.getTts().getVoice();
        return provider.synthesize(spokenText, usedVoice);
    }

    /**
     * Check if TTS is available.
     */
    public boolean isAvailable() {
        try {
            return selectedProvider() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private TtsProvider selectedProvider() {
        List<TtsProvider> providers = providers();
        if (providers.isEmpty()) {
            return null;
        }

        String configured = "";
        if (properties.getTts() != null && properties.getTts().getProvider() != null) {
            configured = properties.getTts().getProvider().trim().toLowerCase(Locale.ROOT);
        }
        if (!configured.isBlank()) {
            for (TtsProvider provider : providers) {
                String name = provider.name();
                if (name != null && configured.equals(name.trim().toLowerCase(Locale.ROOT))) {
                    return provider;
                }
            }
        }
        if (providers.size() == 1) {
            return providers.getFirst();
        }
        throw new IllegalStateException("Configured TTS provider is not available: " + configured);
    }

    private List<TtsProvider> providers() {
        try {
            var stream = providerProvider.stream();
            if (stream != null) {
                List<TtsProvider> providers = stream.toList();
                if (!providers.isEmpty()) {
                    return providers;
                }
            }
        } catch (Exception ignored) {
            // Mockito-only fallback for older tests; real ObjectProvider supports stream().
        }
        TtsProvider provider = providerProvider.getIfAvailable();
        return provider == null ? List.of() : List.of(provider);
    }
}
