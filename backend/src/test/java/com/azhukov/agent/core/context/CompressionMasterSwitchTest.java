package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.memory.MemoryProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-09: agent.compression.enabled=false is a MASTER switch — preflight and
 * proactive compression checks must return false, and the uncompressed-context
 * overflow guardrail logs instead of compressing (Hermes 4d1fc6ca0a #89297).
 */
class CompressionMasterSwitchTest {

    private DefaultContextEngine newEngine(AgentProperties props) {
        return new DefaultContextEngine(null, null, null,
            new DefaultContextCompressor(null, null, props), props);
    }

    private AgentProperties props(boolean compressionEnabled) {
        AgentProperties props = new AgentProperties();
        props.getCompression().setEnabled(compressionEnabled);
        props.getContext().setMaxTokens(1000);
        props.getContext().setThresholdPercent(0.5);
        return props;
    }

    private List<Message> bigHistory() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            msgs.add(Message.user("x".repeat(500)));
        }
        return msgs;
    }

    @Test
    void preflightDisabledWhenCompressionOff() {
        DefaultContextEngine engine = newEngine(props(false));
        assertThat(engine.shouldCompressPreflight(bigHistory())).isFalse();
    }

    @Test
    void preflightActiveWhenCompressionOn() {
        DefaultContextEngine engine = newEngine(props(true));
        assertThat(engine.shouldCompressPreflight(bigHistory())).isTrue();
    }

    @Test
    void proactiveDisabledWhenCompressionOff() {
        AgentProperties props = props(false);
        DefaultContextCompressor compressor = new DefaultContextCompressor(null, null, props);
        assertThat(compressor.shouldCompressProactive(Integer.MAX_VALUE / 2, 1000)).isFalse();
        assertThat(compressor.shouldCompress(Integer.MAX_VALUE / 2)).isFalse();
    }

    @Test
    void emptyOrNullHistoryNeverTriggers() {
        DefaultContextEngine engine = newEngine(props(true));
        assertThat(engine.shouldCompressPreflight(null)).isFalse();
        assertThat(engine.shouldCompressPreflight(List.of())).isFalse();
        assertThat(engine.shouldCompressPreflight(List.of(Message.system("only system")))).isFalse();
    }
}
