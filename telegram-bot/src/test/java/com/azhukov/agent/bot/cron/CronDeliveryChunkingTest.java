package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.formatting.MessageSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-03: cron delivery chunking parity. Hermes gateway/delivery.py hands the
 * FULL cron output to chunking-capable Telegram adapters; truncation to 4000
 * chars is only for non-chunking platforms. The poller must split via
 * MessageSplitter so no output tail is silently dropped.
 */
class CronDeliveryChunkingTest {

    @Test
    void longUnicodeOutputSplitsIntoOrderedChunks() {
        // 30k chars incl. 4-byte emoji (surrogate pairs) — the UTF-16 trap case
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("Строка #").append(i).append(" 🚀🎯 — данные długie текст.\n");
        }
        List<String> chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
            // No chunk ends with a lone high surrogate (broken emoji)
            char last = chunk.charAt(chunk.length() - 1);
            assertThat(Character.isHighSurrogate(last)).isFalse();
        }
        // Order preserved: reassembled content keeps the first and last markers
        assertThat(chunks.get(0)).contains("Строка #0");
        String joined = String.join("", chunks);
        assertThat(joined).contains("Строка #299");
    }

    @Test
    void shortOutputPassesThroughAsSingleChunk() {
        List<String> chunks = MessageSplitter.split("🕐 Cron: test\n\noutput");
        assertThat(chunks).hasSize(1);
    }

    @Test
    void chunksCarryContinuationIndicators() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("line ").append(i).append(" of long cron output\n");
        }
        List<String> chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0)).startsWith("(1/");
        assertThat(chunks.get(chunks.size() - 1)).contains("(" + chunks.size() + "/");
    }
}
