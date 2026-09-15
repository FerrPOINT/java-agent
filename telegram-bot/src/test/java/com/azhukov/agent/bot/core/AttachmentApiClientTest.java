package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WP-11 (docs/35): the bot registers inbound Telegram media as backend
 * attachment artifacts before the chat request references the ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class AttachmentApiClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private AttachmentApiClient client;

    @BeforeEach
    void setUp() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        client = new AttachmentApiClient(restClient, new ObjectMapper());
    }

    @Test
    void registerParsesArtifactId() {
        when(responseSpec.body(String.class)).thenReturn(
            "{\"id\":\"att_abc\",\"contentHash\":\"h1\",\"duplicate\":false}");

        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[] {1, 2}, "photo.jpg", "image/jpeg", "photo",
                "telegram", "123", null, "456");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("att_abc");
        assertThat(result.get().duplicate()).isFalse();
    }

    @Test
    void registerReturnsDuplicateFlag() {
        when(responseSpec.body(String.class)).thenReturn(
            "{\"id\":\"att_existing\",\"contentHash\":\"h1\",\"duplicate\":true}");

        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[] {1}, "photo.jpg", "image/jpeg", "photo",
                "telegram", "123", null, null);

        assertThat(result).isPresent();
        assertThat(result.get().duplicate()).isTrue();
    }

    @Test
    void registerEmptyOn500() {
        when(responseSpec.body(String.class))
            .thenThrow(new RuntimeException("500 Internal Server Error"));

        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[] {1}, "photo.jpg", "image/jpeg", "photo",
                "telegram", "123", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void registerEmptyOnUnparsableBody() {
        when(responseSpec.body(String.class)).thenReturn("not-json{{");

        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[] {1}, "photo.jpg", "image/jpeg", "photo",
                "telegram", "123", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void registerEmptyOnBodyWithoutId() {
        when(responseSpec.body(String.class)).thenReturn("{\"duplicate\":false}");

        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[] {1}, "photo.jpg", "image/jpeg", "photo",
                "telegram", "123", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void registerEmptyOnEmptyData() {
        Optional<AttachmentApiClient.Registered> result =
            client.register(new byte[0], "x", "image/jpeg", "photo",
                "telegram", "123", null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void markDeliveredPostsAndReturnsTrue() {
        assertThat(client.markDelivered("att_abc")).isTrue();
    }

    @Test
    void markDeliveredFalseOnFailure() {
        when(responseSpec.toBodilessEntity())
            .thenThrow(new RuntimeException("connect refused"));
        assertThat(client.markDelivered("att_abc")).isFalse();
    }

    @Test
    void markDeliveredFalseOnBlankId() {
        assertThat(client.markDelivered(null)).isFalse();
        assertThat(client.markDelivered(" ")).isFalse();
    }
}
