package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlashAccessPolicyTest {

    private BotProperties properties;
    private SlashAccessPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        policy = new SlashAccessPolicy(properties);
    }

    @Test
    void canRun_adminAllowedForAllCommands() {
        properties.getAuth().getAdminUserIds().add("12345");
        properties.getAuth().getUserAllowedCommands().add("status");

        assertThat(policy.canRun(12345, "status")).isTrue();
        assertThat(policy.canRun(12345, "model")).isTrue();
        assertThat(policy.canRun(12345, "yolo")).isTrue();
    }

    @Test
    void canRun_nonAdminOnlyAllowedForListedCommands() {
        properties.getAuth().getAdminUserIds().add("999"); // admin is 999, not 123
        properties.getAuth().getUserAllowedCommands().add("status");
        properties.getAuth().getUserAllowedCommands().add("usage");

        assertThat(policy.canRun(123, "status")).isTrue();
        assertThat(policy.canRun(123, "usage")).isTrue();
        assertThat(policy.canRun(123, "model")).isFalse();
        assertThat(policy.canRun(123, "yolo")).isFalse();
    }

    @Test
    void canRun_alwaysAllowedCommandsForEveryone() {
        // Even with admin gating enabled, help and whoami are always allowed
        properties.getAuth().getAdminUserIds().add("999");

        assertThat(policy.canRun(123, "help")).isTrue();
        assertThat(policy.canRun(123, "whoami")).isTrue();
        assertThat(policy.canRun(999, "help")).isTrue();
    }

    @Test
    void canRun_gatingDisabledWhenNoAdminsConfigured() {
        // No admin IDs → backward compat: everyone can run everything
        assertThat(policy.canRun(123, "status")).isTrue();
        assertThat(policy.canRun(123, "model")).isTrue();
        assertThat(policy.canRun(123, "yolo")).isTrue();
    }

    @Test
    void canRun_nullCommandReturnsFalse() {
        assertThat(policy.canRun(123, null)).isFalse();
    }

    @Test
    void accessLevel_adminWhenAdminIdMatches() {
        properties.getAuth().getAdminUserIds().add("12345");
        assertThat(policy.accessLevel(12345)).isEqualTo("admin");
    }

    @Test
    void accessLevel_userWhenNonAdminWithAllowedCommands() {
        properties.getAuth().getAdminUserIds().add("999");
        properties.getAuth().getUserAllowedCommands().add("status");
        assertThat(policy.accessLevel(123)).isEqualTo("user");
    }

    @Test
    void accessLevel_noneWhenNonAdminWithoutAllowedCommands() {
        properties.getAuth().getAdminUserIds().add("999");
        assertThat(policy.accessLevel(123)).isEqualTo("none");
    }
}