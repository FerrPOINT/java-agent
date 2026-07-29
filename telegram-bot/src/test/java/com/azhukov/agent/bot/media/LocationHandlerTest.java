package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3.6: Tests for LocationHandler — handles location messages from Telegram.
 */
class LocationHandlerTest {

    private final LocationHandler handler = new LocationHandler();

    @Test
    void handle_locationType_returnsOptionalWithText() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.LOCATION, 123L, 456L,
            "jdoe", "Location: 40.7128, -74.0060", null, null, "location",
            null, null, null, false, null, null, 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("[Location: 40.7128, -74.0060]");
    }

    @Test
    void handle_locationTypeWithBlankText_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.LOCATION, 123L, 456L,
            "jdoe", "  ", null, null, "location",
            null, null, null, false, null, null, 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_locationTypeWithNullText_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.LOCATION, 123L, 456L,
            "jdoe", null, null, null, "location",
            null, null, null, false, null, null, 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_textType_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.TEXT, 123L, 456L,
            "jdoe", "Hello world", null, null, null,
            null, null, null, false, null, null, 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_commandType_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.COMMAND, 123L, 456L,
            "jdoe", "/start", null, null, null,
            null, null, null, true, "start", "", 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_photoType_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(
            1L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, "Check this", "file-1", "photo",
            null, null, null, false, null, null, 0L, null, 0L
        );

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_nullEvent_returnsEmpty() {
        Optional<String> result = handler.handle(null);

        assertThat(result).isEmpty();
    }
}