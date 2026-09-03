package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SendMessageToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static JsonNode json(ToolResult result) throws Exception {
        return MAPPER.readTree(result.content());
    }

    private static JsonNode errorJson(ToolResult result) throws Exception {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isNotBlank();
        JsonNode root = json(result);
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("error").asText()).isNotBlank();
        assertThat(result.error()).isEqualTo(root.path("error").asText());
        return root;
    }

    @Test
    void sendsMessage() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.TELEGRAM), any(SessionSource.class), eq("hi"))).thenReturn(CompletableFuture.completedFuture(new SendResult(true, "mid", null)));
        SendMessageTool t = new SendMessageTool(providerOf(gw));
        ToolResult r = t.execute("{\"platform\":\"telegram\",\"chatId\":\"1\",\"text\":\"hi\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("platform").asText()).isEqualTo("telegram");
        assertThat(root.path("chat_id").asText()).isEqualTo("1");
        assertThat(root.path("message_id").asText()).isEqualTo("mid");
        var target = org.mockito.ArgumentCaptor.forClass(SessionSource.class);
        verify(gw).send(eq(Platform.TELEGRAM), target.capture(), eq("hi"));
        assertThat(target.getValue().chatId()).isEqualTo("1");
    }

    @Test
    void sendsMessageWithHermesTargetAndMessageArgs() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.DISCORD), any(SessionSource.class), eq("hello")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "m2", null)));
        SendMessageTool t = new SendMessageTool(providerOf(gw));

        ToolResult r = t.execute("{\"target\":\"discord:42\",\"message\":\"hello\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("platform").asText()).isEqualTo("discord");
        assertThat(root.path("chat_id").asText()).isEqualTo("42");
        assertThat(root.path("message_id").asText()).isEqualTo("m2");
    }

    @Test
    void handlesSendError() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new SendResult(false, null, "boom")));
        SendMessageTool t = new SendMessageTool(providerOf(gw));
        ToolResult r = t.execute("{\"platform\":\"telegram\",\"chatId\":\"1\",\"text\":\"hi\"}", null, Session.create("u","p","m"));
        JsonNode root = errorJson(r);
        assertThat(root.path("error").asText()).isEqualTo("boom");
    }

    @Test
    void listsRegisteredGatewayPlatforms() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(mock(BasePlatformAdapter.class)));
        when(gw.adapterFor(Platform.DISCORD)).thenReturn(Optional.empty());
        when(gw.adapterFor(Platform.WEB)).thenReturn(Optional.empty());
        SendMessageTool t = new SendMessageTool(providerOf(gw));

        ToolResult r = t.execute("{\"action\":\"list\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("count").asInt()).isEqualTo(1);
        assertThat(root.path("targets").get(0).path("platform").asText()).isEqualTo("telegram");
        assertThat(root.path("targets").get(0).path("target").asText()).isEqualTo("telegram:<chat_id>");
    }

    @Test
    void sendsReactionWithHermesTargetAndMessageId() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.addReaction(eq(Platform.TELEGRAM), any(SessionSource.class), eq("👍"), eq("99")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "99", null)));
        SendMessageTool t = new SendMessageTool(providerOf(gw));

        ToolResult r = t.execute("{\"action\":\"react\",\"target\":\"telegram:1\",\"message_id\":\"99\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("action").asText()).isEqualTo("react");
        assertThat(root.path("platform").asText()).isEqualTo("telegram");
        assertThat(root.path("chat_id").asText()).isEqualTo("1");
        assertThat(root.path("message_id").asText()).isEqualTo("99");
    }

    @Test
    void clearsReactionWithUnreactAction() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.removeReaction(eq(Platform.TELEGRAM), any(SessionSource.class), eq("99")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "99", null)));
        SendMessageTool t = new SendMessageTool(providerOf(gw));

        ToolResult r = t.execute("{\"action\":\"unreact\",\"platform\":\"telegram\",\"chatId\":\"1\",\"messageId\":\"99\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("action").asText()).isEqualTo("unreact");
        assertThat(root.path("message_id").asText()).isEqualTo("99");
    }

    @Test
    void rejectsReactionWithoutExplicitMessageIdUntilLiveGatewayStateExists() throws Exception {
        SendMessageTool t = new SendMessageTool(providerOf(mock(GatewayRoutingService.class)));

        JsonNode root = errorJson(t.execute("{\"action\":\"react\",\"target\":\"telegram:1\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("message_id is required");
    }

    @Test
    void reportsUnsupportedReactionPlatform() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.addReaction(eq(Platform.DISCORD), any(SessionSource.class), eq("👍"), eq("99")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(false, null, "Platform 'discord' does not support message reactions.")));
        SendMessageTool t = new SendMessageTool(providerOf(gw));

        JsonNode root = errorJson(t.execute("{\"action\":\"react\",\"target\":\"discord:1\",\"message_id\":\"99\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("does not support message reactions");
    }

    @Test
    void rejectsMissingSendArgsAsStructuredError() throws Exception {
        SendMessageTool t = new SendMessageTool(providerOf(mock(GatewayRoutingService.class)));

        JsonNode root = errorJson(t.execute("{\"target\":\"telegram:1\"}", null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("Both 'target' and 'message' are required");
    }

    @Test
    void rejectsThreadTargetsUntilGatewaySupportsThreadIds() throws Exception {
        SendMessageTool t = new SendMessageTool(providerOf(mock(GatewayRoutingService.class)));

        JsonNode root = errorJson(t.execute("{\"target\":\"telegram:-100:123\",\"message\":\"hi\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("Thread/topic targets are not implemented");
    }
}
