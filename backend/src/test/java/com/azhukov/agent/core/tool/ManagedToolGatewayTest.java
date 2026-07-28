package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedToolGatewayTest {

    private AgentProperties properties;
    private ManagedToolGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        gateway = new ManagedToolGateway(properties);
    }

    @Test
    void isEnabled_returnsTrue_whenGatewayDisabled() {
        properties.getTools().setManagedGatewayEnabled(false);

        assertThat(gateway.isEnabled("any_tool")).isTrue();
    }

    @Test
    void isEnabled_returnsTrue_whenNoCheckRegistered() {
        properties.getTools().setManagedGatewayEnabled(true);

        assertThat(gateway.isEnabled("unchecked_tool")).isTrue();
    }

    @Test
    void isEnabled_usesRegisteredCheck() {
        properties.getTools().setManagedGatewayEnabled(true);
        gateway.registerTool("restricted_tool", name -> false);

        assertThat(gateway.isEnabled("restricted_tool")).isFalse();
    }

    @Test
    void registerTool_storesCheck() {
        properties.getTools().setManagedGatewayEnabled(true);
        gateway.registerTool("custom_tool", name -> name.equals("custom_tool"));

        assertThat(gateway.isEnabled("custom_tool")).isTrue();
        assertThat(gateway.isEnabled("other_tool")).isTrue();
    }
}