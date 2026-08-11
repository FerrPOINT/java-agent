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