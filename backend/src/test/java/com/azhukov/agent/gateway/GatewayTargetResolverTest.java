package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.persistence.entity.GatewayHomeChannelEntity;
import com.azhukov.agent.persistence.repository.GatewayHomeChannelRepository;
import com.azhukov.agent.service.GatewayHomeChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GatewayTargetResolverTest {

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

    private static AgentProperties propsWith(String... allowedUserIds) {
        AgentProperties props = new AgentProperties();
        for (String id : allowedUserIds) {
            props.getGateway().getTelegram().getAllowedUserIds().add(id);
        }
        return props;
    }

    @Test
    void parsesExplicitChatTarget() {
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(null));
        Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve("telegram:12345");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().platform()).isEqualTo(Platform.TELEGRAM);
        assertThat(resolved.get().chatId()).isEqualTo("12345");
        assertThat(resolved.get().threadId()).isNull();
        assertThat(resolved.get().homeResolved()).isFalse();
    }

    @Test
    void parsesThreadTarget() {
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(null));
        Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve("telegram:-100999:42");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().chatId()).isEqualTo("-100999");
        assertThat(resolved.get().threadId()).isEqualTo("42");
    }

    @Test
    void emptyChatSegmentFallsBackToHomeResolution() {
        GatewayHomeChannelService home = new GatewayHomeChannelService(null, propsWith("111"));
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(home));
        Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve("telegram:");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().chatId()).isEqualTo("111");
        assertThat(resolved.get().homeResolved()).isTrue();
    }

    @Test
    void barePlatformResolvesPersistedHomeFirst() {
        GatewayHomeChannelRepository repository = mock(GatewayHomeChannelRepository.class);
        GatewayHomeChannelEntity row = new GatewayHomeChannelEntity();
        row.setPlatform("telegram");
        row.setProfile("default");
        row.setChatId("222");
        row.setThreadId("7");
        when(repository.findByPlatformAndProfile("telegram", "default")).thenReturn(Optional.of(row));
        GatewayHomeChannelService home = new GatewayHomeChannelService(repository, propsWith("111"));

        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(home));
        Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve("telegram");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().chatId()).isEqualTo("222");
        assertThat(resolved.get().threadId()).isEqualTo("7");
        assertThat(resolved.get().homeResolved()).isTrue();
    }

    @Test
    void legacyFallbackUsesFirstNumericAllowedUser() {
        GatewayHomeChannelService home = new GatewayHomeChannelService(null, propsWith("alice", "333"));
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(home));

        Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve("telegram");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().chatId()).isEqualTo("333");
    }

    @Test
    void unresolvableHomeIsEmpty() {
        GatewayHomeChannelService home = new GatewayHomeChannelService(null, propsWith());
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(home));

        assertThat(resolver.resolve("telegram")).isEmpty();
        assertThat(resolver.resolve("discord")).isEmpty();
    }

    @Test
    void unknownPlatformAndReservedWordsAreRejected() {
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(null));
        assertThat(resolver.resolve("carrier:1")).isEmpty();
        assertThat(resolver.resolve("origin")).isEmpty();
        assertThat(resolver.resolve("local")).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void toSessionSourceCarriesThreadId() {
        GatewayTargetResolver resolver = new GatewayTargetResolver(providerOf(null));
        var target = resolver.resolve("telegram:5:9").orElseThrow();
        assertThat(target.toSessionSource().threadId()).isEqualTo("9");
        assertThat(target.toSessionSource().chatId()).isEqualTo("5");
    }
}
