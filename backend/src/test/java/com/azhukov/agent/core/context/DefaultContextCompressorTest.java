package com.azhukov.agent.core.context;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultContextCompressorTest {

    @Test
    void returnsEarlyWhenUnderTarget() {
        var compressor = new DefaultContextCompressor(new NoOpModelClient(), null, new AgentProperties());
        var messages = List.of(Message.user("hi"), Message.assistant("hello", 1));
        var result = compressor.compress(messages, 1000);
        assertThat(result).isEqualTo(messages);
    }

    @Test
    void fallsBackToTruncationWhenModelFails() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        Mockito.when(model.complete(Mockito.any(), Mockito.any())).thenThrow(new RuntimeException("boom"));
        var props = new AgentProperties();
        // Use small protect values so 3 messages will compress
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        // Hermes parity tail floor: protectLastN=1 floors to 3 → conversation
        // needs head(1) + middle(2) + tail(3) = 6 messages to compress
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 2),
            Message.user("pre-current"),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // head(1) + summary(1) + tail(3) = 5
        assertThat(result).hasSize(5);
        assertThat(result.get(0).role()).isEqualTo(Role.USER); // head preserved
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM); // summary
        assertThat(result.get(4).role()).isEqualTo(Role.USER); // tail preserved
        assertThat(result.get(4).content()).isEqualTo("current");
    }

    @Test
    void lockTrackingWorksWithoutDb() {
        var compressor = new DefaultContextCompressor(new NoOpModelClient(), null, new AgentProperties());
        assertThat(compressor.isLocked("sess-1", 1)).isFalse();
        compressor.lock("sess-1", 2);
        assertThat(compressor.isLocked("sess-1", 1)).isTrue();
        assertThat(compressor.isLocked("sess-1", 3)).isFalse();
    }

    // ── Image placeholder tests (Finding 5.3) ───────────────────────────

    /**
     * Helper: create a compressor with a mock model that echoes back the summary input.
     * This lets us verify what text the compressor passes to the summarizer.
     */
    private DefaultContextCompressor compressorWithEchoModel() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        // Echo back the last user message (which is the summary input)
        when(model.complete(any(), any())).thenAnswer(inv -> {
            List<Message> msgs = inv.getArgument(0);
            String lastContent = "";
            for (Message m : msgs) {
                if (m.content() != null) lastContent = m.content();
            }
            return ChatResponse.text(lastContent);
        });
        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        return new DefaultContextCompressor(model, null, props);
    }

    private List<Message> buildMessagesForCompression(String middleContent, int imageCount) {
        List<Message> messages = new ArrayList<>();
        // Head: one large user message
        messages.add(Message.user("head".repeat(500)));
        // Middle: a message with images
        if (imageCount > 0) {
            messages.add(Message.userWithImages(middleContent, imageCount));
        } else {
            messages.add(Message.user(middleContent != null ? middleContent.repeat(500) : "middle".repeat(500)));
        }
        // Make sure total exceeds threshold
        if (middleContent == null || middleContent.length() < 500) {
            // For image-only messages, the IMAGE_CHAR_EQUIVALENT already makes it large enough
        }
        // Tail: current user message
        messages.add(Message.user("current query"));
        return messages;
    }

    @Test
    void compressReplacesImageContentWithPlaceholder() {
        // Message with imageCount=2 + text → summary should contain "[image: 2 images attached]"
        var compressor = compressorWithEchoModel();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("head".repeat(500)));
        // Middle: user message with 2 images and some text
        messages.add(Message.userWithImages("look at these screenshots".repeat(50), 2));
        messages.add(Message.user("filler-1 ".repeat(200)));
        messages.add(Message.assistant("filler-2 ".repeat(200), 1));
        messages.add(Message.user("current query"));

        // Compress with small target to trigger compression
        var result = compressor.compress(messages, 100);

        // head(1)+summary(1)+tail(3): the summary system message contains the placeholder
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).contains("[image: 2 images attached]");
    }

    @Test
    void compressImageOnlyMessageGetsPlaceholderOnly() {
        // imageCount=1, no text → summary contains "[image: 1 image attached]"
        var compressor = compressorWithEchoModel();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("head".repeat(500)));
        // Middle: user message with 1 image and no meaningful text content
        messages.add(Message.userWithImages("", 1));
        messages.add(Message.user("filler-1 ".repeat(200)));
        messages.add(Message.assistant("filler-2 ".repeat(200), 1));
        messages.add(Message.user("current query"));

        var result = compressor.compress(messages, 100);

        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).contains("[image: 1 image attached]");
    }

    @Test
    void compressMessageWithNoImagesOmitsPlaceholder() {
        // imageCount=0 → no "[image:" in summary
        var compressor = compressorWithEchoModel();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("head".repeat(500)));
        // Middle: plain text message (no images)
        messages.add(Message.assistant("some important discussion".repeat(50), 1));
        messages.add(Message.user("filler-1 ".repeat(200)));
        messages.add(Message.assistant("filler-2 ".repeat(200), 2));
        messages.add(Message.user("current query"));

        var result = compressor.compress(messages, 100);

        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).doesNotContain("[image:");
    }
}