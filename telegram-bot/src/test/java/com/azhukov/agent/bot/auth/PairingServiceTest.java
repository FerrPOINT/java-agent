package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PairingServiceTest {

    private PairingCodeRepository repository;
    private BotProperties properties;
    private PairingService service;

    @BeforeEach
    void setUp() {
        repository = mock(PairingCodeRepository.class);
        properties = new BotProperties();
        properties.getAuth().getPairing().setEnabled(true);
        service = new PairingService(repository, properties);
    }

    @Test
    void generateCode_returnsCode() {
        when(repository.countByUserIdAndStatus("456", "pending")).thenReturn(0L);

        Optional<String> code = service.generateCode("456", "123", "user");

        assertThat(code).isPresent();
        assertThat(code.get()).hasSize(8);
        verify(repository).save(any(PairingCodeEntity.class));
    }

    @Test
    void generateCode_pairingDisabled_returnsEmpty() {
        properties.getAuth().getPairing().setEnabled(false);

        Optional<String> code = service.generateCode("456", "123", "user");

        assertThat(code).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void generateCode_maxPendingReached_returnsEmpty() {
        when(repository.countByUserIdAndStatus("456", "pending")).thenReturn(3L);

        Optional<String> code = service.generateCode("456", "123", "user");

        assertThat(code).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void validateCode_validCode_returnsEntity() {
        PairingCodeEntity entity = new PairingCodeEntity();
        entity.setCode("ABCDEFGH");
        entity.setUserId("456");
        entity.setStatus("pending");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));

        when(repository.findByCodeAndStatus("ABCDEFGH", "pending")).thenReturn(Optional.of(entity));

        Optional<PairingCodeEntity> result = service.validateCode("ABCDEFGH");

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo("ABCDEFGH");
    }

    @Test
    void approve_validCode_updatesStatus() {
        PairingCodeEntity entity = new PairingCodeEntity();
        entity.setCode("ABCDEFGH");
        entity.setUserId("456");
        entity.setStatus("pending");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));

        when(repository.findByCodeAndStatus("ABCDEFGH", "pending")).thenReturn(Optional.of(entity));

        Optional<PairingCodeEntity> result = service.approve("ABCDEFGH");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("approved");
        verify(repository).save(entity);
    }
}