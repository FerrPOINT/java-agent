package com.azhukov.agent.bot.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BotHealthController} covering health status
 * reporting and response structure.
 */
class BotHealthControllerTest {

    private final BotHealthController controller = new BotHealthController();

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void health_returnsUpStatus() {
        Map<String, Object> result = controller.health();

        assertThat(result)
            .containsEntry("status", "UP")
            .containsEntry("service", "telegram-bot");
    }

    // ─── Response structure ──────────────────────────────────────────────

    @Test
    void health_returnsExactlyTwoFields() {
        Map<String, Object> result = controller.health();

        assertThat(result).hasSize(2);
        assertThat(result.keySet()).containsExactlyInAnyOrder("status", "service");
    }

    // ─── Status value ────────────────────────────────────────────────────

    @Test
    void health_statusIsUp() {
        Map<String, Object> result = controller.health();

        assertThat(result.get("status")).isEqualTo("UP");
    }

    // ─── Service name ────────────────────────────────────────────────────

    @Test
    void health_serviceIsTelegramBot() {
        Map<String, Object> result = controller.health();

        assertThat(result.get("service")).isEqualTo("telegram-bot");
    }

    // ─── Immutability: returned map is immutable ─────────────────────────

    @Test
    void health_returnsImmutableMap() {
        Map<String, Object> result = controller.health();

        assertThatThrownBy(() -> result.put("extra", "value"));
    }

    // ─── Consistency: multiple calls return same content ─────────────────

    @Test
    void health_multipleCallsReturnConsistentResult() {
        Map<String, Object> first = controller.health();
        Map<String, Object> second = controller.health();

        assertThat(first).isEqualTo(second);
    }

    @SuppressWarnings("unchecked")
    private static <T> void assertThatThrownBy(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected an UnsupportedOperationException to be thrown");
        } catch (UnsupportedOperationException e) {
            // Expected — Map.of returns an immutable map
        }
    }
}