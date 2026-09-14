package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.GatewayHomeChannelEntity;
import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import com.azhukov.agent.persistence.repository.GatewayHomeChannelRepository;
import com.azhukov.agent.persistence.repository.OutboundMessageReceiptRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GatewayHomeAndReceiptServiceTest {

    // ---- GatewayHomeChannelService ----

    @Test
    void setHomePersistsAndResolves() {
        GatewayHomeChannelRepository repository = mock(GatewayHomeChannelRepository.class);
        when(repository.findByPlatformAndProfile("telegram", "default")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GatewayHomeChannelService service = new GatewayHomeChannelService(repository, new AgentProperties());

        GatewayHomeChannelService.HomeTarget home =
            service.setHome("telegram", "default", "100", "9", "Home", "u1", "set_home");

        ArgumentCaptor<GatewayHomeChannelEntity> captor = ArgumentCaptor.forClass(GatewayHomeChannelEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("100");
        assertThat(captor.getValue().getThreadId()).isEqualTo("9");
        assertThat(home.persisted()).isTrue();
    }

    @Test
    void setHomeRequiresChatId() {
        GatewayHomeChannelService service = new GatewayHomeChannelService(mock(GatewayHomeChannelRepository.class), new AgentProperties());
        assertThatThrownBy(() -> service.setHome("telegram", "default", "  ", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chat_id");
    }

    @Test
    void clearHomeReturnsWhetherRowExisted() {
        GatewayHomeChannelRepository repository = mock(GatewayHomeChannelRepository.class);
        when(repository.deleteByPlatformAndProfile("telegram", "default")).thenReturn(1L);
        GatewayHomeChannelService service = new GatewayHomeChannelService(repository, new AgentProperties());
        assertThat(service.clearHome("telegram", "default")).isTrue();
    }

    @Test
    void legacyFallbackOnlyForTelegramDefault() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().getAllowedUserIds().add("777");
        GatewayHomeChannelService service = new GatewayHomeChannelService(null, props);

        assertThat(service.resolve("telegram", null)).isPresent();
        assertThat(service.resolve("telegram", "other-profile")).isEmpty();
        assertThat(service.resolve("discord", "default")).isEmpty();
    }

    // ---- OutboundReceiptService ----

    @Test
    void recordIsIdempotentPerKey() {
        OutboundMessageReceiptRepository repository = mock(OutboundMessageReceiptRepository.class);
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboundReceiptService service = new OutboundReceiptService(repository);

        Optional<OutboundMessageReceiptEntity> first = service.record(
            OutboundReceiptService.Receipt.of("telegram", "100", null, null, "m1"));

        assertThat(first).isPresent();
        // Same (target, message) → same default key → repository dedupe path.
        String key = OutboundReceiptService.defaultIdempotencyKey("telegram", "100", null, null, "m1");
        assertThat(first.get().getIdempotencyKey()).isEqualTo(key);
        verify(repository, times(1)).save(any());
    }

    @Test
    void recordReturnsExistingOnIdempotencyHit() {
        OutboundMessageReceiptRepository repository = mock(OutboundMessageReceiptRepository.class);
        OutboundMessageReceiptEntity existing = new OutboundMessageReceiptEntity();
        existing.setMessageId("m1");
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(existing));
        OutboundReceiptService service = new OutboundReceiptService(repository);

        Optional<OutboundMessageReceiptEntity> recorded = service.record(
            OutboundReceiptService.Receipt.of("telegram", "100", null, null, "m1"));

        assertThat(recorded).contains(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void recordRejectsIncompleteReceipts() {
        OutboundReceiptService service = new OutboundReceiptService(mock(OutboundMessageReceiptRepository.class));
        assertThat(service.record(OutboundReceiptService.Receipt.of(null, "100", null, null, "m1"))).isEmpty();
        assertThat(service.record(OutboundReceiptService.Receipt.of("telegram", null, null, null, "m1"))).isEmpty();
        assertThat(service.record(OutboundReceiptService.Receipt.of("telegram", "100", null, null, " "))).isEmpty();
        assertThat(service.record(null)).isEmpty();
    }

    @Test
    void lastMessagePrefersThreadScopedLookup() {
        OutboundMessageReceiptRepository repository = mock(OutboundMessageReceiptRepository.class);
        OutboundMessageReceiptEntity threadLatest = new OutboundMessageReceiptEntity();
        threadLatest.setMessageId("in-topic");
        when(repository.findFirstByPlatformAndChatIdAndThreadIdOrderByCreatedAtDesc("telegram", "100", "9"))
            .thenReturn(Optional.of(threadLatest));
        when(repository.findFirstByPlatformAndChatIdOrderByCreatedAtDesc("telegram", "100"))
            .thenReturn(Optional.empty());
        OutboundReceiptService service = new OutboundReceiptService(repository);

        assertThat(service.lastMessageFor("telegram", "100", "9")).contains(threadLatest);
        assertThat(service.lastMessageFor("telegram", "100", null)).isEmpty();
        assertThat(service.lastMessageFor("", "100", null)).isEmpty();
    }

    @Test
    void defaultKeyIsDeterministicAndDistinct() {
        UUID session = UUID.randomUUID();
        String a = OutboundReceiptService.defaultIdempotencyKey("telegram", "1", null, session, "m");
        String b = OutboundReceiptService.defaultIdempotencyKey("telegram", "1", null, session, "m");
        String c = OutboundReceiptService.defaultIdempotencyKey("telegram", "1", "5", session, "m");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void forSessionReturnsBoundedList() {
        OutboundReceiptService service = new OutboundReceiptService(mock(OutboundMessageReceiptRepository.class));
        assertThat(service.forSession(null, 5)).isEmpty();
        assertThat(service.forSession(UUID.randomUUID(), 0)).isEmpty();
    }
}
