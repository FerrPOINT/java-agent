package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SendMessageToolTest {

    private static ObjectProvider<GatewayRoutingService> providerOf(GatewayRoutingService gw) {
        return new ObjectProvider<GatewayRoutingService>() {
            @Override public GatewayRoutingService getObject() { return gw; }
            @Override public GatewayRoutingService getObject(Object... args) { return gw; }
            @Override public GatewayRoutingService getIfAvailable() { return gw; }
            @Override public GatewayRoutingService getIfUnique() { return gw; }
            @Override public Stream<GatewayRoutingService> stream() { return Stream.of(gw); }
            @Override public Stream<GatewayRoutingService> orderedStream() { return Stream.of(gw); }
        };
    }

    @Test
    void sendsMessage() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.TELEGRAM), any(SessionSource.class), eq("hi"))).thenReturn(CompletableFuture.completedFuture(new SendResult(true, "mid", null)));
        SendMessageTool t = new SendMessageTool(providerOf(gw));
        ToolResult r = t.execute("{\"platform\":\"telegram\",\"chatId\":\"1\",\"text\":\"hi\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("mid");
    }

    @Test
    void handlesSendError() {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new SendResult(false, null, "boom")));
        SendMessageTool t = new SendMessageTool(providerOf(gw));
        ToolResult r = t.execute("{\"platform\":\"telegram\",\"chatId\":\"1\",\"text\":\"hi\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isEqualTo("boom");
    }
}
