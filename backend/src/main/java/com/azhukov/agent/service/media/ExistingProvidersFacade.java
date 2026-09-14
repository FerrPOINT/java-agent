package com.azhukov.agent.service.media;

import com.azhukov.agent.service.imagegen.ImageGenProvider;
import com.azhukov.agent.service.transcription.TranscriptionProvider;
import com.azhukov.agent.service.tts.TtsProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * WP-10: honest MediaProvider facade over the EXISTING provider beans
 * (OpenAI TTS/STT/image, Edge TTS). Capability rows reflect the real
 * configuration state of each backing bean; nothing is advertised beyond
 * what the current implementation supports (no image edit/upscale/video —
 * those fail closed as unsupported until scope is explicitly accepted).
 */
@Component
public class ExistingProvidersFacade {

    private final ObjectProvider<TtsProvider> ttsProvider;
    private final ObjectProvider<TranscriptionProvider> transcriptionProvider;
    private final ObjectProvider<ImageGenProvider> imageGenProvider;
    private final com.azhukov.agent.config.AgentProperties properties;

    public ExistingProvidersFacade(ObjectProvider<TtsProvider> ttsProvider,
                                   ObjectProvider<TranscriptionProvider> transcriptionProvider,
                                   ObjectProvider<ImageGenProvider> imageGenProvider,
                                   com.azhukov.agent.config.AgentProperties properties) {
        this.ttsProvider = ttsProvider;
        this.transcriptionProvider = transcriptionProvider;
        this.imageGenProvider = imageGenProvider;
        this.properties = properties;
    }

    private boolean ttsEnabled() {
        return properties != null && properties.getTts() != null
            && properties.getTts().isEnabled();
    }

    private boolean transcriptionConfigured() {
        return properties != null && properties.getTranscription() != null
            && properties.getTranscription().getApiKey() != null
            && !properties.getTranscription().getApiKey().isBlank();
    }

    private boolean imageGenConfigured() {
        return properties != null && properties.getImageGen() != null
            && properties.getImageGen().getApiKey() != null
            && !properties.getImageGen().getApiKey().isBlank();
    }

    /** TTS facade: edge (free, always capable when enabled) or openai. */
    @Component("mediaTtsProvider")
    public class TtsMediaProvider implements MediaProvider {
        @Override public String id() { return "tts"; }
        @Override public Set<Operation> operations() { return Set.of(Operation.TTS); }
        @Override public Map<Operation, Set<String>> inputMimeTypes() { return Map.of(); }
        @Override public Map<Operation, Set<String>> outputMimeTypes() {
            return Map.of(Operation.TTS, Set.of("audio/mpeg"));
        }
        @Override public boolean isConfigured() {
            return ttsEnabled() && ttsProvider.getIfAvailable() != null;
        }
        @Override public String unavailableReason() {
            if (!ttsEnabled()) {
                return "agent.tts.enabled=false";
            }
            return ttsProvider.getIfAvailable() == null
                ? "no TTS provider bean is registered" : null;
        }
    }

    @Component("mediaSttProvider")
    public class SttMediaProvider implements MediaProvider {
        @Override public String id() { return "stt"; }
        @Override public Set<Operation> operations() { return Set.of(Operation.STT); }
        @Override public Map<Operation, Set<String>> inputMimeTypes() {
            return Map.of(Operation.STT, Set.of("audio/ogg", "audio/mpeg", "audio/wav",
                "audio/mp4", "audio/webm"));
        }
        @Override public Map<Operation, Set<String>> outputMimeTypes() {
            return Map.of(Operation.STT, Set.of("text/plain"));
        }
        @Override public boolean isConfigured() {
            return transcriptionConfigured()
                && transcriptionProvider.getIfAvailable() != null;
        }
        @Override public String unavailableReason() {
            if (!transcriptionConfigured()) {
                return "transcription API key is not configured";
            }
            return transcriptionProvider.getIfAvailable() == null
                ? "no transcription provider bean is registered" : null;
        }
    }

    @Component("mediaImageProvider")
    public class ImageMediaProvider implements MediaProvider {
        @Override public String id() { return "image"; }
        @Override public Set<Operation> operations() { return Set.of(Operation.IMAGE_GENERATE); }
        @Override public Map<Operation, Set<String>> inputMimeTypes() { return Map.of(); }
        @Override public Map<Operation, Set<String>> outputMimeTypes() {
            return Map.of(Operation.IMAGE_GENERATE, Set.of("image/png", "image/jpeg"));
        }
        @Override public boolean isConfigured() {
            return imageGenConfigured() && imageGenProvider.getIfAvailable() != null;
        }
        @Override public String unavailableReason() {
            if (!imageGenConfigured()) {
                return "image generation API key is not configured";
            }
            return imageGenProvider.getIfAvailable() == null
                ? "no image generation provider bean is registered" : null;
        }
    }
}
