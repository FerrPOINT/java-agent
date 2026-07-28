package com.azhukov.agent.client.mcp;

import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpOAuthManagerTest {

    @Mock
    private McpOAuthRepository mcpOAuthRepository;

    private McpOAuthManager manager;

    @BeforeEach
    void setUp() {
        manager = new McpOAuthManager(mcpOAuthRepository);
    }

    @Test
    void getToken_returnsEmpty_whenNoEntity() {
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.empty());

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isEmpty();
    }

    @Test
    void getToken_returnsToken_whenNotExpired() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getToken_returnsEmpty_whenExpired() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isEmpty();
    }

    @Test
    void storeToken_savesEntity() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.empty());
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.storeToken("test-server", "access-123", "refresh-456", expiresAt);

        ArgumentCaptor<McpOAuthEntity> captor = ArgumentCaptor.forClass(McpOAuthEntity.class);
        verify(mcpOAuthRepository).save(captor.capture());
        McpOAuthEntity saved = captor.getValue();
        assertThat(saved.getServerName()).isEqualTo("test-server");
        assertThat(saved.getAccessToken()).isEqualTo("access-123");
        assertThat(saved.getRefreshToken()).isEqualTo("refresh-456");
        assertThat(saved.getExpiresAt()).isEqualTo(expiresAt);
    }
}