package com.azhukov.agent.shared;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h10: the shared mapper is now the single owner of JSON conventions for all
 * modules. These tests pin the conventions so a future "small tweak" in one
 * module cannot silently drift the wire format again.
 */
class SharedObjectMapperTest {

    @Test
    @DisplayName("dates serialize as ISO-8601 strings, not arrays/timestamps")
    void datesAsIsoStrings() throws Exception {
        Instant now = Instant.parse("2026-09-06T10:15:30Z");
        String json = SharedObjectMapper.get().writeValueAsString(Map.of("t", now));
        assertThat(json).contains("2026-09-06T10:15:30Z");
        assertThat(json).doesNotContain(",");
    }

    @Test
    @DisplayName("unknown properties are ignored on read (tolerant clients)")
    void tolerantReads() throws Exception {
        Record dto = SharedObjectMapper.get().readValue(
            "{\"known\":\"x\",\"unknownField\":123}", Record.class);
        assertThat(dto.known()).isEqualTo("x");
    }

    @Test
    @DisplayName("round-trip Instant via JavaTimeModule")
    void instantRoundTrip() throws Exception {
        Instant original = Instant.now();
        String json = SharedObjectMapper.get().writeValueAsString(original);
        Instant back = SharedObjectMapper.get().readValue(json, Instant.class);
        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("pretty variant is indented and still ISO-dated")
    void prettyVariant() {
        assertThat(SharedObjectMapper.pretty()
            .getSerializationConfig().hasSerializationFeatures(
                SerializationFeature.INDENT_OUTPUT.getMask())).isTrue();
        assertThat(SharedObjectMapper.get())
            .isNotSameAs(SharedObjectMapper.pretty());
    }

    record Record(String known) {
    }
}
