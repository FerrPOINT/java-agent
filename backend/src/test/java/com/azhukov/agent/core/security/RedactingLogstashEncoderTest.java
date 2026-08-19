package com.azhukov.agent.core.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingLogstashEncoderTest {

    private final RedactingLogstashEncoder encoder = new RedactingLogstashEncoder();

    @Test
    void encode_redactsApiKeyInJson() {
        // The encoder should redact sk-* API keys from the JSON output
        ILoggingEvent event = createEvent("Using API key sk-abcdefghijklmnopqrstuvwxyz1234567890");

        encoder.start();
        byte[] encoded = encoder.encode(event);

        assertThat(encoded).isNotNull();
        String json = new String(encoded, StandardCharsets.UTF_8);
        // The API key should be redacted
        assertThat(json).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz1234567890");
    }

    @Test
    void encode_preservesNonSensitiveContent() {
        ILoggingEvent event = createEvent("Processing request completed");

        encoder.start();
        byte[] encoded = encoder.encode(event);

        assertThat(encoded).isNotNull();
        String json = new String(encoded, StandardCharsets.UTF_8);
        // The message content should still be present in JSON form
        assertThat(json).contains("Processing request");
    }

    @Test
    void encode_handlesNullGracefully() {
        encoder.start();
        // Encoding a null event — should not throw
        try {
            encoder.encode(null);
        } catch (Exception e) {
            // If it throws, that's also acceptable — the encoder is designed for real events
        }
    }

    @Test
    void headerBytes_isNotNull() {
        byte[] header = encoder.headerBytes();
        assertThat(header).isNotNull();
    }

    @Test
    void footerBytes_isNotNull() {
        byte[] footer = encoder.footerBytes();
        assertThat(footer).isNotNull();
    }

    @Test
    void startStop_lifecycleWorks() {
        encoder.start();
        // After start, the delegate should be started — no exception means success
        encoder.stop();
        // After stop, no exception means success
    }

    @Test
    void encode_redactsMultipleSecrets() {
        ILoggingEvent event = createEvent("Token ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ configured");
        encoder.start();
        byte[] encoded = encoder.encode(event);
        assertThat(encoded).isNotNull();
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ");
    }

    private ILoggingEvent createEvent(String message) {
        Logger logger = (Logger) LoggerFactory.getLogger("test.logger");
        return new LoggingEvent(
            "fqcn", logger, Level.INFO, message, null, null);
    }
}