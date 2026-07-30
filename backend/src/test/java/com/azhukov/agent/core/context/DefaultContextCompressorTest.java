package com.azhukov.agent.core.context;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // protectFirstN=1 → head = [user "a"×2000], protectLastN=1 → tail = [user "current"]
        // middle = [assistant "b"×2000] → summarized
        // result = head(1) + summary(1) + tail(1) = 3
        assertThat(result).hasSize(3); // head + summary + tail
        assertThat(result.get(0).role()).isEqualTo(Role.USER); // head preserved
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM); // summary
        assertThat(result.get(2).role()).isEqualTo(Role.USER); // tail preserved
        assertThat(result.get(2).content()).isEqualTo("current");
    }

    @Test
    void lockTrackingWorksWithoutDb() {
        var compressor = new DefaultContextCompressor(new NoOpModelClient(), null, new AgentProperties());
        assertThat(compressor.isLocked("sess-1", 1)).isFalse();
        compressor.lock("sess-1", 2);
        assertThat(compressor.isLocked("sess-1", 1)).isTrue();
        assertThat(compressor.isLocked("sess-1", 3)).isFalse();
    }
}