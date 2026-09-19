package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dashboard-persisted MCP configs (mcp_server_configs) must join the effective
 * server list on boot/reload — otherwise servers added via the dashboard never
 * connect after a restart (the dev tool-test hit exactly this).
 */
class McpEffectiveServersTest {

    @Test
    @SuppressWarnings("unchecked")
    void persistedServersMergeWithYamlServers() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().clear();
        AgentProperties.McpProperties.ServerProperties yaml = new AgentProperties.McpProperties.ServerProperties();
        yaml.setName("yaml-server");
        properties.getMcp().getServers().add(yaml);

        var entity = new com.azhukov.agent.persistence.entity.McpServerConfigEntity();
        entity.setProfile("default");
        entity.setName("echo-dev");
        entity.setEnabled(true);
        entity.setTransport("stdio");
        entity.setCommand("sh");
        entity.setArgsJson("[\"/tmp/mcp-echo-server.sh\"]");
        entity.setTimeoutSeconds(30);
        entity.setTrust("full");

        com.azhukov.agent.service.McpConfigStore store = Mockito.mock(com.azhukov.agent.service.McpConfigStore.class);
        Mockito.when(store.list("default")).thenReturn(List.of(entity));
        ObjectProvider<com.azhukov.agent.service.McpConfigStore> provider =
            (ObjectProvider<com.azhukov.agent.service.McpConfigStore>) Mockito.mock(ObjectProvider.class,
                Mockito.withSettings().stubOnly());
        Mockito.when(provider.getIfAvailable()).thenReturn(store);

        McpLifecycleManager manager = McpTestFactory.create(properties, provider);

        List<AgentProperties.McpProperties.ServerProperties> merged = manager.effectiveServers();

        assertThat(merged).extracting(AgentProperties.McpProperties.ServerProperties::getName)
            .containsExactly("yaml-server", "echo-dev");
        var echo = merged.get(1);
        assertThat(echo.getCommand()).isEqualTo("sh");
        assertThat(echo.getArgs()).containsExactly("/tmp/mcp-echo-server.sh");
        assertThat(echo.getTransport()).isEqualTo("stdio");
    }

    @Test
    @SuppressWarnings("unchecked")
    void yamlNameWinsOverDuplicatePersistedName() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().clear();
        AgentProperties.McpProperties.ServerProperties yaml = new AgentProperties.McpProperties.ServerProperties();
        yaml.setName("repomix");
        yaml.setCommand("repomix-yaml");
        properties.getMcp().getServers().add(yaml);

        var entity = new com.azhukov.agent.persistence.entity.McpServerConfigEntity();
        entity.setProfile("default");
        entity.setName("repomix");
        entity.setCommand("repomix-db");
        entity.setTransport("stdio");

        com.azhukov.agent.service.McpConfigStore store = Mockito.mock(com.azhukov.agent.service.McpConfigStore.class);
        Mockito.when(store.list("default")).thenReturn(List.of(entity));
        ObjectProvider<com.azhukov.agent.service.McpConfigStore> provider =
            (ObjectProvider<com.azhukov.agent.service.McpConfigStore>) Mockito.mock(ObjectProvider.class,
                Mockito.withSettings().stubOnly());
        Mockito.when(provider.getIfAvailable()).thenReturn(store);

        McpLifecycleManager manager = McpTestFactory.create(properties, provider);

        assertThat(manager.effectiveServers()).hasSize(1);
        assertThat(manager.effectiveServers().getFirst().getCommand()).isEqualTo("repomix-yaml");
    }

    @Test
    void storeUnavailableYieldsYamlOnly() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().clear();
        AgentProperties.McpProperties.ServerProperties yaml = new AgentProperties.McpProperties.ServerProperties();
        yaml.setName("only-yaml");
        properties.getMcp().getServers().add(yaml);

        McpLifecycleManager manager = McpTestFactory.create(properties, null);

        assertThat(manager.effectiveServers())
            .extracting(AgentProperties.McpProperties.ServerProperties::getName)
            .containsExactly("only-yaml");
    }
}
