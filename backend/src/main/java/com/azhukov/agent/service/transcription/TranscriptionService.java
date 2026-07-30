package com.azhukov.agent.service.transcription;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Service for audio transcription (speech-to-text).
 * Delegates to the available {@link TranscriptionProvider} if transcription is enabled.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TranscriptionService {

    private final ObjectProvider<TranscriptionProvider> providerProvider;


    /**
     * Transcribe audio to text.
     *
     * @param audioBytes the audio file bytes
     * @return the transcribed text, or null if transcription is not available
     */
    public String transcribe(byte[] audioBytes) {
        TranscriptionProvider provider = providerProvider.getIfAvailable();
        if (provider == null) {
            log.debug("Transcription is not enabled");
            return null;
        }
        return provider.transcribe(audioBytes);
    }

    /**
     * Check if transcription is available.
     */
    public boolean isAvailable() {
        return providerProvider.getIfAvailable() != null;
    }
}