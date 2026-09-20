package com.azhukov.agent.bot.keyboard;

/**
 * Renders blocking-clarify prompts as Telegram inline keyboards.
 *
 * <p>Hermes parity (clarify_tool.py + clarify_gateway.py): single-select
 * questions render one button per option; multi-select adds a Done button;
 * free-text questions render the question text without buttons.</p>
 */

import com.azhukov.agent.bot.client.TelegramClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the backend's BLOCKING clarify prompts (Hermes clarify_gateway
 * parity) as Telegram inline keyboards and resolves pending entries through
 * the backend endpoints. The agent turn stays open on the backend while the
 * user answers; a button tap POSTs /clarify/resolve and the tool result
 * carries the structured answer back into the SAME turn.
 */
@Slf4j
@Component
public class ClarifyInteractionRenderer {

    public static final String CLARIFY_CALLBACK = "clfy";

    private final ObjectMapper objectMapper;
    private final InlineKeyboardBuilder keyboardBuilder;
    private final RestClient backendRestClient;
    private final TelegramClient telegramClient;

    /** chatId -> selected indexes for multi-select prompts (clarifyId-keyed). */
    private final Map<String, java.util.Set<Integer>> multiSelectState = new java.util.concurrent.ConcurrentHashMap<>();
    /** Prompt metadata stays until its matching backend pending entry resolves. */
    private final Map<String, PromptPayload> activePrompts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Single-question prompt payload (mirrors the backend ClarifyStreamBridge event). */
    record PromptPayload(String clarifyId, String backendSessionId, String question, List<String> choices, boolean multiSelect) {}

    public ClarifyInteractionRenderer(ObjectMapper objectMapper,
                                      InlineKeyboardBuilder keyboardBuilder,
                                      @Qualifier("backendRestClient") RestClient backendRestClient,
                                      TelegramClient telegramClient) {
        this.objectMapper = objectMapper;
        this.keyboardBuilder = keyboardBuilder;
        this.backendRestClient = backendRestClient;
        this.telegramClient = telegramClient;
    }

    /**
     * Render a clarify SSE payload. Single questions receive an inline keyboard.
     * Batch questions are emitted as one prompt in the current tool contract,
     * so make every choice independently tappable instead of falling back to
     * inert plain text.
     */
    public void present(long chatId, long threadId, String backendSessionId, String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload.path("batch").asBoolean(false)) {
                String prompt = payload.path("prompt").asText("");
                if (!prompt.isBlank()) {
                    telegramClient.sendMessage(chatId, prompt, null, null,
                        threadId > 0 ? (int) threadId : null, null, false);
                }
                return;
            }
            String clarifyId = payload.path("clarifyId").asText("");
            String question = payload.path("question").asText("");
            List<String> choices = new ArrayList<>();
            payload.path("choices").forEach(c -> choices.add(c.asText()));
            boolean multiSelect = payload.path("multi_select").asBoolean(false);
            if (clarifyId.isEmpty() || question.isEmpty()) {
                log.debug("Clarify payload missing fields for chat {}", chatId);
                return;
            }
            activePrompts.put(clarifyId, new PromptPayload(clarifyId, backendSessionId, question, choices, multiSelect));
            String markup = keyboardBuilder.build(buttonsFor(clarifyId, choices, multiSelect, java.util.Set.of()));
            Integer topicId = threadId > 0 ? (int) threadId : null;
            if (telegramClient.sendMessage(chatId, question, null, null, topicId, markup, false).isEmpty()) {
                activePrompts.remove(clarifyId);
                log.warn("Clarify keyboard delivery failed for chat {}", chatId);
            }
        } catch (Exception e) {
            log.warn("Clarify render failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    /** Handle a clfy callback. */
    public CallbackResult handleCallback(long chatId, long messageId, String value) {
        String[] parts = value == null ? new String[0] : value.split(":", 2);
        if (parts.length != 2) {
            return CallbackResult.invalid("Invalid clarification");
        }
        String clarifyId = parts[0];
        String action = parts[1];
        PromptPayload prompt = activePrompts.get(clarifyId);
        if (prompt == null) {
            return CallbackResult.invalid("This question has expired.");
        }
        if ("other".equals(action)) {
            // Text-capture mode: the next typed message resolves via /clarify/text
            return CallbackResult.pending("Type your answer");
        }
        if ("done".equals(action)) {
            java.util.Set<Integer> selected = multiSelectState.getOrDefault(clarifyId, java.util.Set.of());
            if (selected.isEmpty()) {
                return CallbackResult.pending("Choose at least one option");
            }
            String numbers = selected.stream().sorted().map(i -> String.valueOf(i + 1))
                .reduce((a, b) -> a + "," + b).orElse("");
            boolean resolved = resolveOnBackend(clarifyId, numbers);
            if (resolved) {
                cleanup(clarifyId, messageId, chatId);
                return CallbackResult.complete("Selected");
            }
            return CallbackResult.invalid("This question has expired.");
        }
        int index;
        try {
            index = Integer.parseInt(action);
        } catch (NumberFormatException e) {
            return CallbackResult.invalid("Invalid choice");
        }
        if (index < 0 || index >= prompt.choices().size()) {
            return CallbackResult.invalid("Invalid choice");
        }
        if (prompt.multiSelect()) {
            java.util.Set<Integer> selected = new java.util.LinkedHashSet<>(
                multiSelectState.getOrDefault(clarifyId, java.util.Set.of()));
            if (!selected.add(index)) {
                selected.remove(index);
            }
            multiSelectState.put(clarifyId, selected);
            if (messageId > 0) {
                telegramClient.editMessageReplyMarkup(chatId, messageId,
                    keyboardBuilder.build(buttonsFor(clarifyId, prompt.choices(), true, selected)));
            }
            return CallbackResult.pending("Selection updated");
        }
        boolean resolved = resolveOnBackend(clarifyId, String.valueOf(index + 1));
        if (resolved) {
            cleanup(clarifyId, messageId, chatId);
            return CallbackResult.complete("Selected");
        }
        return CallbackResult.invalid("This question has expired.");
    }

    private String sessionIdFor(String clarifyId) {
        PromptPayload prompt = activePrompts.get(clarifyId);
        return prompt != null && prompt.backendSessionId() != null ? prompt.backendSessionId() : "unknown";
    }

    private boolean resolveOnBackend(String clarifyId, String response) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = backendRestClient.post()
                .uri("/api/v1/agent/session/{sessionId}/clarify/resolve", sessionIdFor(clarifyId))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("clarifyId", clarifyId, "response", response))
                .retrieve()
                .body(Map.class);
            return result != null && Boolean.TRUE.equals(result.get("resolved"));
        } catch (Exception e) {
            log.warn("Clarify resolve failed for {}: {}", clarifyId, e.getMessage());
            return false;
        }
    }

    private void cleanup(String clarifyId, long messageId, long chatId) {
        activePrompts.remove(clarifyId);
        multiSelectState.remove(clarifyId);
        if (messageId > 0) {
            telegramClient.editMessageReplyMarkup(chatId, messageId, null);
        }
    }

    private List<List<KeyboardButton>> buttonsFor(String clarifyId, List<String> choices,
                                                  boolean multiSelect, java.util.Set<Integer> selected) {
        List<List<KeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            String label = (selected.contains(i) ? "✓ " : "") + choices.get(i);
            rows.add(List.of(new KeyboardButton(label, CLARIFY_CALLBACK + ":" + clarifyId + ":" + i)));
        }
        rows.add(List.of(new KeyboardButton("Other (type answer)", CLARIFY_CALLBACK + ":" + clarifyId + ":other")));
        if (multiSelect) {
            rows.add(List.of(new KeyboardButton("Done", CLARIFY_CALLBACK + ":" + clarifyId + ":done")));
        }
        return rows;
    }

    /** Outcome of a clarify callback: ack text and whether the prompt is fully resolved. */
    public record CallbackResult(String acknowledgement, boolean complete) {
        static CallbackResult complete(String ack) { return new CallbackResult(ack, true); }
        static CallbackResult pending(String ack) { return new CallbackResult(ack, false); }
        static CallbackResult invalid(String ack) { return new CallbackResult(ack, false); }
    }
}
