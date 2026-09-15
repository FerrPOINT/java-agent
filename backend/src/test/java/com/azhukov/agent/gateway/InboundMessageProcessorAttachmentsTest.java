package com.azhukov.agent.gateway;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.service.AttachmentArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-f: MessageEvent.attachments was a dead field nothing read. Gateway
 * inbound attachments now register as backend artifacts and their cache
 * paths flow into the turn as references.
 */
@ExtendWith(MockitoExtension.class)
class InboundMessageProcessorAttachmentsTest {

    @Mock private SessionResolver sessionResolver;
    @Mock private AgentRuntime agentRuntime;
    @Mock private ObjectProvider<GatewayRoutingService> routingServiceProvider;
    @Mock private GatewayRoutingService routingService;
    @Mock private com.azhukov.agent.persistence.service.MessagePersistenceService messagePersistenceService;
    @Mock private com.azhukov.agent.core.agent.SteerBuffer steerBuffer;
    @Mock private ObjectProvider<AttachmentArtifactService> attachmentArtifacts;
    @Mock private AttachmentArtifactService artifacts;

    private com.azhukov.agent.config.AgentProperties properties;
    private InboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new com.azhukov.agent.config.AgentProperties();
        properties.getGateway().getTelegram().setAllowByDefault(true);
        processor = new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider,
            messagePersistenceService, null, properties, steerBuffer, null, attachmentArtifacts);
        lenient().when(attachmentArtifacts.getIfAvailable()).thenReturn(artifacts);
        lenient().when(routingServiceProvider.getIfAvailable()).thenReturn(routingService);
    }

    @SuppressWarnings("unchecked")
    private void runEvent(MessageEvent event) {
        Session session = new Session(UUID.randomUUID(), "u1", "test", "openai", "test-model", null, Map.of());
        lenient().when(sessionResolver.resolve(any(SessionSource.class))).thenReturn(session);
        lenient().when(agentRuntime.runTurn(any(Session.class), any(String.class), anyList()))
            .thenReturn(mock(TurnResult.class));
        lenient().when(steerBuffer.steer(any(UUID.class), any(String.class))).thenReturn(false);
        processor.accept(event);
    }

    @Test
    void attachmentRegistersAndItsCachePathBecomesAReference() {
        byte[] png = {1, 2, 3};
        when(artifacts.register(any(), any(), any(), any(), any(), eq("photo"),
            eq("image/png"), eq("cat.png"), org.mockito.AdditionalMatchers.aryEq(png)))
            .thenReturn(new AttachmentArtifactService.ArtifactRegistration(
                "att_1", "hash", "/cache/u1/att_1.png", false, null));

        runEvent(new MessageEvent("evt-1",
            new SessionSource(Platform.TELEGRAM, "100", "u1", "alice", "alice"),
            MessageType.TEXT, "look",
            List.of(new MessageEvent.Attachment("https://t.me/f/cat.png", "image/png", "cat.png", png)),
            Map.of(), Instant.now()));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> refs = ArgumentCaptor.forClass(List.class);
        verify(agentRuntime).runTurn(any(Session.class), any(String.class), refs.capture());
        assertThat(refs.getValue()).containsExactly("/cache/u1/att_1.png");
    }

    @Test
    void rejectedAttachmentDoesNotKillTheMessage() {
        when(artifacts.register(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AttachmentArtifactService.ArtifactRegistration(
                null, null, null, false, "file too large"));

        runEvent(new MessageEvent("evt-2",
            new SessionSource(Platform.TELEGRAM, "100", "u1", "alice", "alice"),
            MessageType.TEXT, "hello",
            List.of(new MessageEvent.Attachment(null, "application/zip", "big.zip", new byte[] {9})),
            Map.of(), Instant.now()));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> refs = ArgumentCaptor.forClass(List.class);
        verify(agentRuntime).runTurn(any(Session.class), any(String.class), refs.capture());
        assertThat(refs.getValue()).isEmpty();
    }

    @Test
    void textOnlyEventKeepsEmptyReferences() {
        runEvent(new MessageEvent("evt-3",
            new SessionSource(Platform.TELEGRAM, "100", "u1", "alice", "alice"),
            MessageType.TEXT, "plain", List.of(), Map.of(), Instant.now()));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> refs = ArgumentCaptor.forClass(List.class);
        verify(agentRuntime).runTurn(any(Session.class), any(String.class), refs.capture());
        assertThat(refs.getValue()).isEmpty();
    }

    @Test
    void missingArtifactServiceDegradesToAttachmentFreeTurn() {
        when(attachmentArtifacts.getIfAvailable()).thenReturn(null);
        runEvent(new MessageEvent("evt-4",
            new SessionSource(Platform.TELEGRAM, "100", "u1", "alice", "alice"),
            MessageType.TEXT, "hi",
            List.of(new MessageEvent.Attachment(null, "text/plain", "a.txt", new byte[] {1})),
            Map.of(), Instant.now()));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> refs = ArgumentCaptor.forClass(List.class);
        verify(agentRuntime).runTurn(any(Session.class), any(String.class), refs.capture());
        assertThat(refs.getValue()).isEmpty();
    }
}
