package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.tool.ClarifyGatewayStore;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blocking ClarifyTool contract (clarify_tool.py parity): in an interactive
 * session (stream sender registered + clarifyChatId metadata) the tool
 * registers a pending entry, emits the prompt payload through the bridge,
 * BLOCKS until resolved, and returns Hermes-shaped result JSON
 * {question, choices_offered, user_response} with the (Recommended) label
 * stripped from the answer.
 */
class ClarifyToolBlockingTest {

    private static Session interactiveSession() {
        return new Session(UUID.randomUUID(), "u", "t", "openai", "m", null,
            Map.of("clarifyChatId", "12345"), null);
    }

    @Test
    void singleQuestionBlocksAndReturnsStructuredAnswer() throws Exception {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyTool tool = new ClarifyTool(store);
        AtomicReference<String> emitted = new AtomicReference<>();
        Thread resolver = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (emitted.get() == null && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
            String payload = emitted.get();
            if (payload == null) return;
            try {
                String id = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(payload).path("clarifyId").asText();
                store.resolve(id, "staging (Recommended)");
            } catch (Exception ignored) {
            }
        });
        resolver.start();
        Session session = interactiveSession();
        ClarifyStreamBridge.setSender(session.id(), emitted::set);
        try {
            ToolResult result = tool.execute(
                "{\"question\":\"Which environment?\",\"choices\":[\"staging\",\"prod\"]}",
                null, session);
            assertThat(result.success()).isTrue();
            com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.content());
            assertThat(json.path("question").asText()).isEqualTo("Which environment?");
            assertThat(json.path("user_response").asText()).isEqualTo("staging");
            assertThat(json.path("choices_offered").toString()).contains("staging");
        } finally {
            ClarifyStreamBridge.clear(session.id());
            try { resolver.join(6000); } catch (InterruptedException ignored) {}
        }
    }

    @Test
    void noInteractiveContextFallsBackToFormattedText() {
        ClarifyTool tool = new ClarifyTool(null);
        ToolResult result = tool.execute(
            "{\"question\":\"Which?\",\"choices\":[\"a\",\"b\"]}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("1. a");
        assertThat(result.content()).contains("Other (type answer)");
    }
}
