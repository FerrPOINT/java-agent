package com.azhukov.agent.service.transcription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscriptionProviderTest {

    @Mock
    private ObjectProvider<TranscriptionProvider> providerProvider;

    @Mock
    private TranscriptionProvider provider;

    private TranscriptionService service;

    @BeforeEach
    void setUp() {
        service = new TranscriptionService(providerProvider);
    }

    @Test
    void transcribe_returnsTextWhenProviderAvailable() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any())).thenReturn("Hello, this is a test.");

        String result = service.transcribe("audio-data".getBytes());

        assertThat(result).isEqualTo("Hello, this is a test.");
    }

    @Test
    void transcribe_forwardsMultipartMetadataWhenProvided() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        when(provider.transcribe(any(), eq("voice.mp3"), eq("audio/mpeg"))).thenReturn("Hello mp3");

        String result = service.transcribe("audio-data".getBytes(), "voice.mp3", "audio/mpeg");

        assertThat(result).isEqualTo("Hello mp3");
    }

    @Test
    void transcribe_returnsNullWhenNoProvider() {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        String result = service.transcribe("audio-data".getBytes());

        assertThat(result).isNull();
    }

    @Test
    void isAvailable_trueWhenProviderExists() {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        assertThat(service.isAvailable()).isTrue();
    }
}
