package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.GatewayTargetResolver;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import com.azhukov.agent.service.GatewayHomeChannelService;
import com.azhukov.agent.service.OutboundReceiptService;
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

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            @Override public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    /** Resolver stub: platform:chat[:thread] parses; bare platform resolves to a home. */
    private static GatewayTargetResolver resolverWithHome(String homeChatId, String homeThreadId) {
        return new GatewayTargetResolver(providerOf(new GatewayHomeChannelService(null, telegramProps()))) {
            // no override needed: GatewayHomeChannelService with null repository falls back to legacy
        };
    }

    private static com.azhukov.agent.config.AgentProperties telegramProps() {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().getAllowedUserIds().add("754334329");
        return props;
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

    /** Tool wired with a real resolver (legacy home fallback 754334329) and mock gateway. */
    private static SendMessageTool tool(GatewayRoutingService gw, GatewayTargetResolver resolver) {
        return new SendMessageTool(providerOf(gw), providerOf(resolver), null,
            providerOf(new GatewayHomeChannelService(null, telegramProps())));
    }

    @Test
    void sendsMessage() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.TELEGRAM), any(SessionSource.class), eq("hi"), any()))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "mid", null)));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));
        ToolResult r = t.execute("{\"platform\":\"telegram\",\"chatId\":\"1\",\"text\":\"hi\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("platform").asText()).isEqualTo("telegram");
        assertThat(root.path("chat_id").asText()).isEqualTo("1");
        assertThat(root.path("message_id").asText()).isEqualTo("mid");
        var target = org.mockito.ArgumentCaptor.forClass(SessionSource.class);
        verify(gw).send(eq(Platform.TELEGRAM), target.capture(), eq("hi"), any());
        assertThat(target.getValue().chatId()).isEqualTo("1");
    }

    @Test
    void sendsMessageWithHermesTargetAndMessageArgs() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.DISCORD), any(SessionSource.class), eq("hello"), any()))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "m2", null)));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        ToolResult r = t.execute("{\"target\":\"discord:42\",\"message\":\"hello\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("platform").asText()).isEqualTo("discord");
        assertThat(root.path("chat_id").asText()).isEqualTo("42");
        assertThat(root.path("message_id").asText()).isEqualTo("m2");
    }

    @Test
    void sendsThreadTargetThroughResolver() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.TELEGRAM), any(SessionSource.class), eq("topic msg"), any()))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "t1", null)));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        ToolResult r = t.execute("{\"target\":\"telegram:-100123:777\",\"message\":\"topic msg\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("chat_id").asText()).isEqualTo("-100123");
        assertThat(root.path("thread_id").asText()).isEqualTo("777");
        var target = org.mockito.ArgumentCaptor.forClass(SessionSource.class);
        verify(gw).send(eq(Platform.TELEGRAM), target.capture(), eq("topic msg"), any());
        assertThat(target.getValue().threadId()).isEqualTo("777");
    }

    @Test
    void barePlatformResolvesLegacyHomeFallback() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(eq(Platform.TELEGRAM), any(SessionSource.class), eq("home msg"), any()))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "h1", null)));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        ToolResult r = t.execute("{\"target\":\"telegram\",\"message\":\"home msg\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("chat_id").asText()).isEqualTo("754334329");
    }

    @Test
    void barePlatformWithoutHomeFailsClosed() throws Exception {
        // Resolver over a home service with no repository and no allowed users: no home.
        com.azhukov.agent.config.AgentProperties empty = new com.azhukov.agent.config.AgentProperties();
        GatewayTargetResolver resolver = new GatewayTargetResolver(
            providerOf(new GatewayHomeChannelService(null, empty)));
        SendMessageTool t = new SendMessageTool(providerOf(mock(GatewayRoutingService.class)), providerOf(resolver), null, null);

        JsonNode root = errorJson(t.execute("{\"target\":\"discord\",\"message\":\"hi\"}", null, Session.create("u","p","m")));
        assertThat(root.path("error").asText()).contains("Unknown or unresolvable target 'discord'");
    }

    @Test
    void handlesSendError() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.send(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new SendResult(false, null, "boom")));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));
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
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        ToolResult r = t.execute("{\"action\":\"list\"}", null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("count").asInt()).isEqualTo(1);
        assertThat(root.path("targets").get(0).path("platform").asText()).isEqualTo("telegram");
        // Legacy home fallback present → listing shows the resolved home target.
        assertThat(root.path("targets").get(0).path("target").asText()).isEqualTo("telegram:754334329");
        assertThat(root.path("targets").get(0).path("home").asBoolean()).isTrue();
    }

    @Test
    void sendsReactionWithHermesTargetAndMessageId() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.addReaction(eq(Platform.TELEGRAM), any(SessionSource.class), eq("👍"), eq("99")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "99", null)));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

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
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        ToolResult r = t.execute("{\"action\":\"unreact\",\"platform\":\"telegram\",\"chatId\":\"1\",\"messageId\":\"99\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("action").asText()).isEqualTo("unreact");
        assertThat(root.path("message_id").asText()).isEqualTo("99");
    }

    @Test
    void reactionWithoutMessageIdResolvesLatestReceipt() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.addReaction(eq(Platform.TELEGRAM), any(SessionSource.class), eq("👍"), eq("55")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "55", null)));
        OutboundReceiptService receipts = mock(OutboundReceiptService.class);
        OutboundMessageReceiptEntity latest = new OutboundMessageReceiptEntity();
        latest.setMessageId("55");
        when(receipts.lastMessageFor("telegram", "1", null)).thenReturn(Optional.of(latest));
        SendMessageTool t = new SendMessageTool(providerOf(gw), providerOf(resolverWithHome(null, null)),
            providerOf(receipts), null);

        ToolResult r = t.execute("{\"action\":\"react\",\"target\":\"telegram:1\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        JsonNode root = json(r);
        assertThat(root.path("message_id").asText()).isEqualTo("55");
        verify(receipts).lastMessageFor("telegram", "1", null);
    }

    @Test
    void reactionWithoutMessageIdAndWithoutReceiptFailsClosed() throws Exception {
        OutboundReceiptService receipts = mock(OutboundReceiptService.class);
        when(receipts.lastMessageFor(any(), any(), any())).thenReturn(Optional.empty());
        SendMessageTool t = new SendMessageTool(providerOf(mock(GatewayRoutingService.class)),
            providerOf(resolverWithHome(null, null)), providerOf(receipts), null);

        JsonNode root = errorJson(t.execute("{\"action\":\"react\",\"target\":\"telegram:1\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("No recent outbound message");
    }

    @Test
    void reportsUnsupportedReactionPlatform() throws Exception {
        GatewayRoutingService gw = mock(GatewayRoutingService.class);
        when(gw.addReaction(eq(Platform.DISCORD), any(SessionSource.class), eq("👍"), eq("99")))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(false, null, "Platform 'discord' does not support message reactions.")));
        SendMessageTool t = tool(gw, resolverWithHome(null, null));

        JsonNode root = errorJson(t.execute("{\"action\":\"react\",\"target\":\"discord:1\",\"message_id\":\"99\",\"emoji\":\"👍\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("does not support message reactions");
    }

    @Test
    void rejectsMissingSendArgsAsStructuredError() throws Exception {
        SendMessageTool t = tool(mock(GatewayRoutingService.class), resolverWithHome(null, null));

        JsonNode root = errorJson(t.execute("{\"target\":\"telegram:1\"}", null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("Both 'target' and 'message' are required");
    }

    @Test
    void rejectsUnknownTargetShape() throws Exception {
        SendMessageTool t = tool(mock(GatewayRoutingService.class), resolverWithHome(null, null));

        JsonNode root = errorJson(t.execute("{\"target\":\"carrier:pigeon\",\"message\":\"hi\"}",
            null, Session.create("u","p","m")));

        assertThat(root.path("error").asText()).contains("Unknown or unresolvable target");
    }
}
