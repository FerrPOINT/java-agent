package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdpClientTest {

    @Test
    void startsDisconnected() {
        CdpClient client = new CdpClient(new ObjectMapper());
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void disconnectWhenNotConnectedDoesNotThrow() {
        CdpClient client = new CdpClient(new ObjectMapper());
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
    }
}
