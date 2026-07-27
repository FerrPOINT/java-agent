package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAdapterExtraTest {

    @Test
    void disconnectSetsConnectedFalse() throws Exception {
        AgentProperties props = new AgentProperties();
        TelegramAdapter a = new TelegramAdapter(props, null);
        a.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        assertThat(a.isConnected()).isTrue();
        a.disconnect().get();
        assertThat(a.isConnected()).isFalse();
    }

    @Test
    void sendImageReturnsStub() throws Exception {
        AgentProperties props = new AgentProperties();
        TelegramAdapter a = new TelegramAdapter(props, null);
        var r = a.sendImage(new SessionSource(Platform.TELEGRAM, "1", "u", "u", "U"), new byte[0], "c").get();
        assertThat(r.success()).isTrue();
        assertThat(r.messageId()).isEqualTo("stub-image");
    }

    @Test
    void sendDocumentReturnsStub() throws Exception {
        AgentProperties props = new AgentProperties();
        TelegramAdapter a = new TelegramAdapter(props, null);
        var r = a.sendDocument(new SessionSource(Platform.TELEGRAM, "1", "u", "u", "U"), new byte[0], "f", "c").get();
        assertThat(r.success()).isTrue();
        assertThat(r.messageId()).isEqualTo("stub-doc");
    }

    @Test
    void buildSourceReturnsInput() {
        AgentProperties props = new AgentProperties();
        TelegramAdapter a = new TelegramAdapter(props, null);
        assertThat(a.buildSource(Map.of("x", 1))).hasValue(Map.of("x", 1));
    }
}
