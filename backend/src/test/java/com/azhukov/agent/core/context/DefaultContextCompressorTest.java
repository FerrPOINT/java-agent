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
        var compressor = new DefaultContextCompressor(model, null, new AgentProperties());
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        assertThat(result).hasSize(2); // summary + current
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).role()).isEqualTo(Role.USER);
        assertThat(result.get(1).content()).isEqualTo("current");
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
