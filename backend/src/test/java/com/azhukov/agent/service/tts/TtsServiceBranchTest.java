package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;

/**
 * Branch coverage tests for {@link TtsService}.
 * Covers null provider, voice fallback, and isAvailable.
 */
@ExtendWith(MockitoExtension.class)
class TtsServiceBranchTest {

    @Mock
    private ObjectProvider<TtsProvider> providerProvider;

    @Mock
    private TtsProvider provider;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTts().setEnabled(true);
        properties.getTts().setVoice("alloy");
    }

    @Test
    void synthesize_providerNull_throwsIllegalState() {
        when(providerProvider.getIfAvailable()).thenReturn(null);
        TtsService service = new TtsService(providerProvider, properties);

        assertThatThrownBy(() -> service.synthesize("hello", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TTS is not enabled");
    }

    @Test
    void synthesize_usesConfiguredDefaultVoice_whenVoiceIsNull() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.synthesize("hello", "alloy")).thenReturn(new byte[]{1, 2, 3});

        TtsService service = new TtsService(providerProvider, properties);
        byte[] result = service.synthesize("hello", null);

        assertThat(result).containsExactly(1, 2, 3);
        verify(provider).synthesize("hello", "alloy");
    }

    @Test
    void synthesize_cleansNonspokenBlocksAndMarkdownBeforeProviderCall() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.synthesize("hello world", "alloy")).thenReturn(new byte[]{1, 2, 3});

        TtsService service = new TtsService(providerProvider, properties);
        byte[] result = service.synthesize("<think>internal</think> **hello** [world](https://example.com)", null);

        assertThat(result).containsExactly(1, 2, 3);
        verify(provider).synthesize("hello world", "alloy");
    }

    @Test
    void synthesize_rejectsTextThatBecomesEmptyAfterCleanup() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        TtsService service = new TtsService(providerProvider, properties);

        assertThatThrownBy(() -> service.synthesize("<think>internal</think>", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty after TTS cleanup");
        verify(provider, never()).synthesize(any(), any());
    }

    @Test
    void synthesize_usesConfiguredDefaultVoice_whenVoiceIsBlank() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.synthesize("hello", "alloy")).thenReturn(new byte[]{1, 2, 3});

        TtsService service = new TtsService(providerProvider, properties);
        byte[] result = service.synthesize("hello", "   ");

        assertThat(result).containsExactly(1, 2, 3);
        verify(provider).synthesize("hello", "alloy");
    }

    @Test
    void synthesize_usesProvidedVoice_whenNotBlank() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.synthesize("hello", "echo")).thenReturn(new byte[]{4, 5, 6});

        TtsService service = new TtsService(providerProvider, properties);
        byte[] result = service.synthesize("hello", "echo");

        assertThat(result).containsExactly(4, 5, 6);
        verify(provider).synthesize("hello", "echo");
    }

    @Test
    void synthesize_selectsConfiguredProviderWhenMultipleProvidersExist() {
        properties.getTts().setProvider("openai");
        TtsProvider edge = mock(TtsProvider.class);
        TtsProvider openai = mock(TtsProvider.class);
        when(edge.name()).thenReturn("edge");
        when(openai.name()).thenReturn("openai");
        when(providerProvider.stream()).thenReturn(Stream.of(edge, openai));
        when(openai.synthesize("hello", "alloy")).thenReturn(new byte[]{9});

        TtsService service = new TtsService(providerProvider, properties);
        byte[] result = service.synthesize("hello", null);

        assertThat(result).containsExactly(9);
        verify(openai).synthesize("hello", "alloy");
        verify(edge, never()).synthesize(any(), any());
    }

    @Test
    void isAvailable_trueWhenProviderExists() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        TtsService service = new TtsService(providerProvider, properties);
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_falseWhenProviderIsNull() {
        when(providerProvider.getIfAvailable()).thenReturn(null);
        TtsService service = new TtsService(providerProvider, properties);
        assertThat(service.isAvailable()).isFalse();
    }
}
