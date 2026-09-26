package com.azhukov.agent.bot.keyboard;

/**
 * Renders blocking-clarify prompts as Telegram inline keyboards.
 *
 * <p>Hermes parity (clarify_tool.py + clarify_gateway.py): single-select
 * questions render one button per option; multi-select adds a Done button;
 * free-text questions render the question text without buttons.</p>
 */

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.typing.TypingManager;
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
    private final TypingManager typingManager;

    /** chatId -> selected indexes for multi-select prompts (clarifyId-keyed). */
    private final Map<String, java.util.Set<Integer>> multiSelectState = new java.util.concurrent.ConcurrentHashMap<>();
    /** Prompt metadata stays until its matching backend pending entry resolves. */
    private final Map<String, PromptPayload> activePrompts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Backend session id for a text response, keyed by its Telegram chat. */
    private final Map<Long, String> awaitingTextSessions = new java.util.concurrent.ConcurrentHashMap<>();
    /** A delivered prompt blocks busy-mode handling until its original turn resolves. */
    private final java.util.Set<Long> awaitingResponseChats = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Single-question prompt payload (mirrors the backend ClarifyStreamBridge event). */
    record PromptPayload(long chatId, String clarifyId, String backendSessionId, String question, List<String> choices, boolean multiSelect) {}

    public ClarifyInteractionRenderer(ObjectMapper objectMapper,
                                      InlineKeyboardBuilder keyboardBuilder,
                                      @Qualifier("backendRestClient") RestClient backendRestClient,
                                      TelegramClient telegramClient,
                                      TypingManager typingManager) {
        this.objectMapper = objectMapper;
        this.keyboardBuilder = keyboardBuilder;
        this.backendRestClient = backendRestClient;
        this.telegramClient = telegramClient;
        this.typingManager = typingManager;
    }

    /**
     * Render a clarify SSE payload. Single questions receive an inline keyboard.
     * Batch questions are emitted as one prompt in the current tool contract,
     * so make every choice independently tappable instead of falling back to
     * inert plain text.
     */
    public void present(long chatId, long threadId, String backendSessionId, String payloadJson) {
        try {
            log.info("clarify_prompt_render_start chat={} session={} payloadBytes={}",
                chatId, backendSessionId, payloadJson == null ? 0 : payloadJson.length());
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload.path("batch").asBoolean(false)) {
                String prompt = payload.path("prompt").asText("");
                if (!prompt.isBlank()) {
                    boolean delivered = telegramClient.sendMessage(chatId, prompt, null, null,
                        threadId > 0 ? (int) threadId : null, null, false).isPresent();
                    if (delivered) {
                        typingManager.pauseTypingForClarify(chatId);
                    }
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
            if (backendSessionId == null || backendSessionId.isBlank()) {
                log.warn("Clarify payload missing backend session for chat {}", chatId);
                return;
            }
            activePrompts.put(clarifyId, new PromptPayload(chatId, clarifyId, backendSessionId, question, choices, multiSelect));
            awaitingResponseChats.add(chatId);
            String markup = keyboardBuilder.build(buttonsFor(clarifyId, choices, multiSelect, java.util.Set.of()));
            Integer topicId = threadId > 0 ? (int) threadId : null;
            boolean delivered = telegramClient.sendMessage(chatId, question, null, null, topicId, markup, false).isPresent();
            if (delivered) {
                typingManager.pauseTypingForClarify(chatId);
                log.info("clarify_prompt_rendered chat={} session={} id={} choices={} multiSelect={}",
                    chatId, backendSessionId, clarifyId, choices.size(), multiSelect);
            } else {
                activePrompts.remove(clarifyId);
                clearAwaitingResponseIfNoPrompt(chatId);
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
            log.info("clarify_callback_rejected chat={} id={} reason=unknown_prompt", chatId, clarifyId);
            return CallbackResult.invalid("This question has expired.");
        }
        if (prompt.chatId() != chatId) {
            log.warn("clarify_callback_rejected chat={} id={} reason=wrong_chat", chatId, clarifyId);
            return CallbackResult.invalid("This question has expired.");
        }
        log.info("clarify_callback_received chat={} session={} id={} action={}",
            chatId, prompt.backendSessionId(), clarifyId, action);
        if ("other".equals(action)) {
            boolean armed = armCustomResponse(prompt.backendSessionId(), clarifyId);
            if (armed) {
                awaitingTextSessions.put(chatId, prompt.backendSessionId());
                return CallbackResult.pending("Type your answer");
            }
            return CallbackResult.invalid("This question has expired.");
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
                typingManager.resumeTyping(chatId);
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
            typingManager.resumeTyping(chatId);
            cleanup(clarifyId, messageId, chatId);
            return CallbackResult.complete("Selected");
        }
        return CallbackResult.invalid("This question has expired.");
    }

    /** True while this chat has a delivered backend clarify prompt awaiting any response. */
    public boolean isAwaitingResponse(long chatId) {
        return awaitingResponseChats.contains(chatId);
    }

    /** Backend session id for any live clarify response in this chat. */
    public String awaitingResponseSession(long chatId) {
        String customSession = awaitingTextSessions.get(chatId);
        if (customSession != null && !customSession.isBlank()) {
            return customSession;
        }
        return activePrompts.values().stream()
            .filter(prompt -> prompt.chatId() == chatId)
            .map(PromptPayload::backendSessionId)
            .filter(sessionId -> sessionId != null && !sessionId.isBlank())
            .findFirst()
            .orElse(null);
    }

    /** Clear the prompt state after the backend accepted a typed response. */
    public void completeTextResponse(long chatId) {
        awaitingTextSessions.remove(chatId);
        activePrompts.entrySet().removeIf(entry -> entry.getValue().chatId() == chatId);
        multiSelectState.keySet().removeIf(clarifyId -> !activePrompts.containsKey(clarifyId));
        awaitingResponseChats.remove(chatId);
    }

    /** Backend session id for a custom text response, keyed by its Telegram chat. */
    public String awaitingTextSession(long chatId) {
        return awaitingTextSessions.get(chatId);
    }

    /** Clear a text-response route only after the backend accepted or rejected it. */
    public void clearAwaitingTextSession(long chatId) {
        awaitingTextSessions.remove(chatId);
    }

    private String sessionIdFor(String clarifyId) {
        PromptPayload prompt = activePrompts.get(clarifyId);
        return prompt != null && prompt.backendSessionId() != null ? prompt.backendSessionId() : "unknown";
    }

    private boolean armCustomResponse(String backendSessionId, String clarifyId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = backendRestClient.post()
                .uri("/api/v1/agent/session/{sessionId}/clarify/{clarifyId}/custom", backendSessionId, clarifyId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(Map.class);
            boolean armed = result != null && Boolean.TRUE.equals(result.get("armed"));
            log.info("clarify_custom_response_armed id={} armed={}", clarifyId, armed);
            return armed;
        } catch (Exception e) {
            log.warn("Clarify custom-response arm failed for {}: {}", clarifyId, e.getMessage());
            return false;
        }
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
            boolean resolved = result != null && Boolean.TRUE.equals(result.get("resolved"));
            log.info("clarify_callback_backend_result id={} resolved={}", clarifyId, resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("Clarify resolve failed for {}: {}", clarifyId, e.getMessage());
            return false;
        }
    }

    private void cleanup(String clarifyId, long messageId, long chatId) {
        activePrompts.remove(clarifyId);
        multiSelectState.remove(clarifyId);
        awaitingTextSessions.remove(chatId);
        clearAwaitingResponseIfNoPrompt(chatId);
        if (messageId > 0) {
            telegramClient.editMessageReplyMarkup(chatId, messageId, null);
        }
    }

    private void clearAwaitingResponseIfNoPrompt(long chatId) {
        if (activePrompts.values().stream().noneMatch(prompt -> prompt.chatId() == chatId)) {
            awaitingResponseChats.remove(chatId);
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
