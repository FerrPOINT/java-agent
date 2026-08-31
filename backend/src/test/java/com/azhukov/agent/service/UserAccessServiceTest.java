package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.AgentUserEntity;
import com.azhukov.agent.persistence.entity.UserApiKeyEntity;
import com.azhukov.agent.persistence.repository.AgentUserRepository;
import com.azhukov.agent.persistence.repository.UserApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessServiceTest {

    @Mock private AgentUserRepository userRepository;
    @Mock private UserApiKeyRepository apiKeyRepository;

    @Test
    void issueApiKeyStoresOnlyHashAndAuthenticatesItsOwner() {
        AgentUserEntity user = user("u-alice", "alice", "user");
        when(userRepository.findById("u-alice")).thenReturn(Optional.of(user));
        when(apiKeyRepository.save(org.mockito.ArgumentMatchers.any(UserApiKeyEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        UserAccessService service = new UserAccessService(userRepository, apiKeyRepository);
        UserAccessService.IssuedApiKey issued = service.issueApiKey("u-alice", "laptop");

        ArgumentCaptor<UserApiKeyEntity> stored = ArgumentCaptor.forClass(UserApiKeyEntity.class);
        org.mockito.Mockito.verify(apiKeyRepository).save(stored.capture());
        assertThat(issued.rawKey()).startsWith("agk_");
        assertThat(stored.getValue().getKeyHash()).isEqualTo(UserAccessService.sha256(issued.rawKey()));
        assertThat(stored.getValue().getKeyHash()).doesNotContain(issued.rawKey());

        when(apiKeyRepository.findByKeyHash(UserAccessService.sha256(issued.rawKey()))).thenReturn(Optional.of(stored.getValue()));
        when(userRepository.findById("u-alice")).thenReturn(Optional.of(user));
        assertThat(service.authenticate(issued.rawKey()))
            .isEqualTo(new UserAccessService.AuthenticatedUser("u-alice", "user"));
    }

    @Test
    void createUserRejectsDuplicateUsername() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("u", "alice", "user")));
        UserAccessService service = new UserAccessService(userRepository, apiKeyRepository);

        assertThatThrownBy(() -> service.createUser("alice", "Alice", "user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void authenticateUnknownKeyReturnsNull() {
        when(apiKeyRepository.findByKeyHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        UserAccessService service = new UserAccessService(userRepository, apiKeyRepository);

        assertThat(service.authenticate("agk_unknown")).isNull();
    }

    private static AgentUserEntity user(String id, String username, String role) {
        AgentUserEntity user = new AgentUserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }
}