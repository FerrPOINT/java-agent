package com.azhukov.agent.service.transcription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranscriptionService}.
 * Covers: happy path, null/blank input, API error handling, edge cases.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    @Mock
    private ObjectProvider<TranscriptionProvider> providerProvider;

    @Mock
    private TranscriptionProvider provider;

    private TranscriptionService service;

    @BeforeEach
    void setUp() {
        service = new TranscriptionService(providerProvider);
    }

    // ── Happy path ──

    @Test
    void transcribe_returnsText_whenProviderAvailable() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any(byte[].class))).thenReturn("Hello, this is a test.");

        String result = service.transcribe("audio-data".getBytes());

        assertThat(result).isEqualTo("Hello, this is a test.");
        verify(provider).transcribe(any(byte[].class));
    }

    @Test
    void transcribe_returnsEmptyString_whenProviderReturnsEmpty() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any(byte[].class))).thenReturn("");

        String result = service.transcribe(new byte[]{1, 2, 3});

        assertThat(result).isEmpty();
    }

    @Test
    void isAvailable_true_whenProviderExists() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        assertThat(service.isAvailable()).isTrue();
    }

    // ── Null / blank input ──

    @Test
    void transcribe_returnsNull_whenNoProviderAvailable() {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        String result = service.transcribe("audio".getBytes());

        assertThat(result).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    void transcribe_withNullAudioBytes_delegatesToProvider() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(null)).thenReturn("transcribed null");

        String result = service.transcribe(null);

        assertThat(result).isEqualTo("transcribed null");
    }

    @Test
    void transcribe_withEmptyByteArray_delegatesToProvider() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(new byte[]{})).thenReturn("empty audio");

        String result = service.transcribe(new byte[]{});

        assertThat(result).isEqualTo("empty audio");
    }

    // ── API error handling ──

    @Test
    void transcribe_propagatesException_whenProviderThrows() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any(byte[].class)))
            .thenThrow(new RuntimeException("API error: invalid audio format"));

        assertThatThrownBy(() -> service.transcribe("bad-audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("invalid audio format");
    }

    @Test
    void transcribe_propagatesWrappedIOException_whenProviderThrowsCheckedException() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any(byte[].class)))
            .thenThrow(new RuntimeException("Connection timeout", new java.io.IOException("timeout")));

        assertThatThrownBy(() -> service.transcribe(new byte[]{1}))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Connection timeout");
    }

    // ── Edge cases ──

    @Test
    void isAvailable_false_whenNoProvider() {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_doesNotCache_providerAvailability() {
        // First call: provider available
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        assertThat(service.isAvailable()).isTrue();

        // Second call: provider no longer available
        when(providerProvider.getIfAvailable()).thenReturn(null);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void transcribe_delegatesExactBytes_toProvider() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] audio = {0x52, 0x49, 0x46, 0x46}; // RIFF header
        when(provider.transcribe(audio)).thenReturn("wav content");

        String result = service.transcribe(audio);

        assertThat(result).isEqualTo("wav content");
        verify(provider).transcribe(audio);
    }

    @Test
    void transcribe_largeAudioArray_delegatesSuccessfully() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] largeAudio = new byte[1024 * 1024]; // 1MB
        when(provider.transcribe(largeAudio)).thenReturn("large file transcribed");

        String result = service.transcribe(largeAudio);

        assertThat(result).isEqualTo("large file transcribed");
    }
}