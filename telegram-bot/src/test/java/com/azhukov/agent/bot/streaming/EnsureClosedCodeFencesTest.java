package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8 (Hermes stream_consumer.py ensure_closed_code_fences): orphaned code
 * fences from truncated responses get closers appended before delivery.
 */
class EnsureClosedCodeFencesTest {

    @Test
    void oddTripleFenceGetsCloser() {
        String truncated = "Here is code:\n```python\nprint('hi')";
        String fixed = StreamEditor.ensureClosedCodeFences(truncated);
        assertThat(fixed).endsWith("\n```");
        assertThat(fixed).startsWith("Here is code:\n```python");
        // balanced text untouched
        assertThat(StreamEditor.ensureClosedCodeFences("a```x```b")).isEqualTo("a```x```b");
    }

    @Test
    void orphanInlineBacktickGetsCloser() {
        assertThat(StreamEditor.ensureClosedCodeFences("value is `foo"))
            .isEqualTo("value is `foo`");
        // backticks INSIDE complete fences don't count
        assertThat(StreamEditor.ensureClosedCodeFences("see ```a`b``` end"))
            .isEqualTo("see ```a`b``` end");
    }

    @Test
    void bothFixedTogether() {
        String in = "code:\n```js\nlet x = `tpl";
        String out = StreamEditor.ensureClosedCodeFences(in);
        assertThat(out).endsWith("`");
        assertThat(out).contains("\n```"); // fence closed before inline tick
    }

    @Test
    void nullAndEmptyPassthrough() {
        assertThat(StreamEditor.ensureClosedCodeFences(null)).isNull();
        assertThat(StreamEditor.ensureClosedCodeFences("")).isEmpty();
        assertThat(StreamEditor.ensureClosedCodeFences("plain text")).isEqualTo("plain text");
    }

    @Test
    void trailingNewlineStrippedBeforeFenceCloser() {
        String in = "text\n```py\ncode()\n";
        String out = StreamEditor.ensureClosedCodeFences(in);
        assertThat(out).isEqualTo("text\n```py\ncode()\n```");
    }
}
