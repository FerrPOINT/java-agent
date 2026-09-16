package com.azhukov.agent.service.media;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
import com.azhukov.agent.service.transcription.TranscriptionProvider;
import com.azhukov.agent.service.tts.TtsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-10: capability registry — honest matrix, configured-only exposure,
 * deterministic selection, fail-closed unavailable reporting.
 */
@ExtendWith(MockitoExtension.class)
class MediaProviderRegistryTest {

    @Mock
    private TtsProvider ttsProvider;

    @Mock
    private TranscriptionProvider transcriptionProvider;

    @Mock
    private ImageGenProvider imageGenProvider;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private AgentProperties properties(boolean tts, boolean sttKey, boolean imageKey) {
        AgentProperties properties = new AgentProperties();
        properties.getTts().setEnabled(tts);
        properties.getTranscription().setApiKey(sttKey ? "key" : "");
        properties.getImageGen().setApiKey(imageKey ? "key" : "");
        return properties;
    }

    private ExistingProvidersFacade facade(AgentProperties properties) {
        return new ExistingProvidersFacade(prov(ttsProvider), prov(transcriptionProvider),
            prov(imageGenProvider), properties);
    }

    @Test
    void unconfiguredProvidersAreNotCapable() {
        ExistingProvidersFacade facade = facade(properties(false, false, false));
        MediaProviderRegistry registry = new MediaProviderRegistry(
            prov(List.of(facade.new TtsMediaProvider(), facade.new SttMediaProvider(),
                facade.new ImageMediaProvider())),
            properties(false, false, false));

        assertThat(registry.capable(MediaProvider.Operation.TTS)).isEmpty();
        assertThat(registry.capable(MediaProvider.Operation.STT)).isEmpty();
        assertThat(registry.capable(MediaProvider.Operation.IMAGE_GENERATE)).isEmpty();
        assertThat(registry.isCapabilityAvailable(MediaProvider.Operation.TTS)).isFalse();
    }

    @Test
    void configuredProviderBecomesCapableAndSelectable() {
        AgentProperties properties = properties(true, true, true);
        ExistingProvidersFacade facade = facade(properties);
        MediaProviderRegistry registry = new MediaProviderRegistry(
            prov(List.of(facade.new TtsMediaProvider(), facade.new SttMediaProvider(),
                facade.new ImageMediaProvider())),
            properties);

        assertThat(registry.capable(MediaProvider.Operation.TTS)).hasSize(1);
        assertThat(registry.capable(MediaProvider.Operation.STT)).hasSize(1);
        assertThat(registry.capable(MediaProvider.Operation.IMAGE_GENERATE)).hasSize(1);
        assertThat(registry.resolve(MediaProvider.Operation.TTS, "tts")).isPresent();
        assertThat(registry.resolve(MediaProvider.Operation.TTS, "nope")).isEmpty();
        assertThat(registry.resolve(MediaProvider.Operation.TTS, null)).isPresent();
    }

    @Test
    void matrixReportsHonestRowsWithReasons() {
        AgentProperties properties = properties(true, false, false);
        ExistingProvidersFacade facade = facade(properties);
        MediaProviderRegistry registry = new MediaProviderRegistry(
            prov(List.of(facade.new TtsMediaProvider(), facade.new SttMediaProvider(),
                facade.new ImageMediaProvider())),
            properties);

        List<MediaProviderRegistry.CapabilityRow> rows = registry.matrix();

        assertThat(rows).extracting(MediaProviderRegistry.CapabilityRow::provider)
            .containsExactlyInAnyOrder("tts", "stt", "image");
        var ttsRow = rows.stream().filter(r -> r.provider().equals("tts")).findFirst().orElseThrow();
        var sttRow = rows.stream().filter(r -> r.provider().equals("stt")).findFirst().orElseThrow();
        assertThat(ttsRow.configured()).isTrue();
        assertThat(ttsRow.outputMime()).containsExactly("audio/mpeg");
        assertThat(sttRow.configured()).isFalse();
        assertThat(sttRow.unavailableReason()).contains("API key");
    }

    @Test
    void noProvidersMeansEmptyRegistry() {
        MediaProviderRegistry registry = new MediaProviderRegistry(
            prov(null), new AgentProperties());
        assertThat(registry.providerIds()).isEmpty();
        assertThat(registry.matrix()).isEmpty();
        assertThat(registry.describe()).containsKeys("providers", "capabilities");
    }

    @Test
    void imageEditUpscaleNotAdvertised() {
        // facade advertises IMAGE_GENERATE only — edit/upscale fail closed
        AgentProperties properties = properties(false, false, true);
        ExistingProvidersFacade facade = facade(properties);
        MediaProviderRegistry registry = new MediaProviderRegistry(
            prov(List.of(facade.new ImageMediaProvider())), properties);

        assertThat(registry.capable(MediaProvider.Operation.IMAGE_EDIT)).isEmpty();
        assertThat(registry.capable(MediaProvider.Operation.IMAGE_UPSCALE)).isEmpty();
        assertThat(registry.capable(MediaProvider.Operation.IMAGE_GENERATE)).hasSize(1);
    }

    private static <T> ObjectProvider<List<T>> prov(List<T> value) {
        return new ObjectProvider<>() {
            @Override public List<T> getObject() { return value; }
            @Override public List<T> getObject(Object... args) { return value; }
            @Override public List<T> getIfAvailable() { return value; }
            @Override public List<T> getIfUnique() { return value; }
            public Stream<List<T>> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<List<T>> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }
}
