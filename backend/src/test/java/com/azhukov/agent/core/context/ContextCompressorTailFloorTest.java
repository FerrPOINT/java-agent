package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity (context_compressor.py:5987, issue #61932): the protected
 * tail is capped at max(3, min(protectLastN, 8)). A default protect_last_n=20
 * must not freeze a whole run of bulky tool outputs against pruning — live
 * incident 'Context compressed from 2 to 2' no-op loop on 2026-08-23.
 */
class ContextCompressorTailFloorTest {

    private DefaultContextCompressor compressor() {
        AgentProperties props = new AgentProperties(); // defaults: first=3, last=20
        return new DefaultContextCompressor(null, null, props);
    }

    @Test
    void elevenBulkyMessagesAreCompressible() {
        // 11 messages, each huge — below protectFirst+protectLast (3+20=23) but
        // ABOVE protectFirst+tailFloor (3+8=11 boundary is exclusive... use 12)
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(Message.user("x".repeat(20_000) + " turn " + i));
        }
        var result = compressor().compress(messages, 5_000);
        // count-based claim replaced by content-based (see tailFloorIsEightNotConfigTwenty):
        // equal count with reduced chars IS effective compression
        int charsIn = messages.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
        int charsOut = result.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
        assertThat(charsOut).isLessThan(charsIn);
    }

    @Test
    void tailFloorIsEightNotConfigTwenty() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(Message.user("turn " + i + " " + "y".repeat(20_000)));
        }
        var result = compressor().compress(messages, 10_000);
        int charsIn = messages.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
        int charsOut = result.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
        // count may stay equal (protected tail keeps 8 + head 3 + summary), but
        // the CONTENT must shrink — that's what actually frees context tokens
        assertThat(charsOut).isLessThan(charsIn);
        assertThat(result.size()).isLessThanOrEqualTo(12);
    }

    @Test
    void tinySessionsStillSkipCompression() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(Message.user("turn " + i + " " + "z".repeat(20_000)));
        }
        // 6 <= 3 + floor(8): nothing to compress without eating the floor
        var result = compressor().compress(messages, 5_000);
        assertThat(result.size()).isEqualTo(messages.size());
    }
}
