package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B3.6: Handles location messages from Telegram.
 * <p>
 * Parses the location (latitude, longitude) from an {@link UpdateEvent} and
 * formats it as "Location: lat, lon" for inclusion in LLM context.
 */
@Component
@Slf4j
public class LocationHandler {

    /**
     * Handle a location UpdateEvent and return a formatted description for the LLM.
     *
     * @param event the UpdateEvent of type LOCATION
     * @return Optional with formatted location text, or empty if the event is not a location
     */
    public Optional<String> handle(UpdateEvent event) {
        if (event == null || event.type() != UpdateEvent.Type.LOCATION) {
            return Optional.empty();
        }
        String text = event.text();
        if (text == null || text.isBlank()) {
            log.debug("Location event has no text (parsing may have failed)");
            return Optional.empty();
        }
        log.debug("Handled location: {}", text);
        return Optional.of("[" + text + "]");
    }
}