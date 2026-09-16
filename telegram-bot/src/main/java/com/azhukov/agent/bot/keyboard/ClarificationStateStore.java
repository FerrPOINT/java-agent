package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Converts clarify tool arguments into Telegram interactions and retains the
 * small amount of state needed to turn a callback or typed answer into a user
 * message for the next agent turn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClarificationStateStore {

    public static final String CALLBACK_COMMAND = "cq";
    public static final String ANSWER_PREFIX = "\u0000clarify-answer:";
    private static final String ACTION_OTHER = "other";
    private static final String ACTION_DONE = "done";
    private static final int MAX_CHOICES = 4;

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, PendingQuestion> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final InlineKeyboardBuilder keyboardBuilder;

    /** Render a supported single question. Batch questions stay textual: one response must not trigger several turns. */
    public void present(long chatId, long threadId, String rawArguments, TelegramClient telegramClient) {
        Prompt prompt = parseSinglePrompt(rawArguments);
        if (prompt == null || prompt.choices().isEmpty()) {
            return;
        }
        String id = Long.toUnsignedString(nextId.getAndIncrement(), 36);
        PendingQuestion state = new PendingQuestion(chatId, threadId, prompt);
        pending.put(id, state);
        String markup = keyboardBuilder.build(buttonsFor(id, state));
        Integer topicId = threadId > 0 ? (int) threadId : null;
        if (telegramClient.sendMessage(chatId, prompt.question(), null, null, topicId, markup, false).isEmpty()) {
            pending.remove(id);
            log.warn("Clarification keyboard delivery failed for chat={}", chatId);
        }
    }

    /** Process one cq callback. The returned answer is fed back into the normal text pipeline. */
    public CallbackOutcome handleCallback(long chatId, long messageId, String value, TelegramClient telegramClient) {
        String[] parts = value == null ? new String[0] : value.split(":", 2);
        if (parts.length != 2) return CallbackOutcome.invalid("Invalid clarification");
        PendingQuestion state = pending.get(parts[0]);
        if (state == null || state.chatId != chatId) return CallbackOutcome.invalid("This question has expired.");

        String action = parts[1];
        if (ACTION_OTHER.equals(action)) {
            state.awaitingTypedAnswer = true;
            return CallbackOutcome.pending("Type your answer");
        }
        if (state.prompt.multiSelect()) {
            if (ACTION_DONE.equals(action)) {
                if (state.selected.isEmpty()) return CallbackOutcome.pending("Choose at least one option");
                return complete(parts[0], state, messageId, telegramClient);
            }
            Integer choice = parseChoice(action, state.prompt.choices().size());
            if (choice == null) return CallbackOutcome.invalid("Invalid choice");
            if (!state.selected.add(choice)) state.selected.remove(choice);
            if (messageId > 0) {
                telegramClient.editMessageReplyMarkup(chatId, messageId,
                    keyboardBuilder.build(buttonsFor(parts[0], state)));
            }
            return CallbackOutcome.pending("Selection updated");
        }

        Integer choice = parseChoice(action, state.prompt.choices().size());
        if (choice == null) return CallbackOutcome.invalid("Invalid choice");
        state.selected.clear();
        state.selected.add(choice);
        return complete(parts[0], state, messageId, telegramClient);
    }

    /** Consume a free-form response after the user pressed the Other button. */
    public String consumeTypedAnswer(long chatId, String text) {
        if (text == null || text.isBlank()) return null;
        for (Map.Entry<String, PendingQuestion> entry : pending.entrySet()) {
            PendingQuestion state = entry.getValue();
            if (state.chatId == chatId && state.awaitingTypedAnswer) {
                pending.remove(entry.getKey(), state);
                return formatAnswer(state.prompt, List.of(text.trim()));
            }
        }
        return null;
    }

    private CallbackOutcome complete(String id, PendingQuestion state, long messageId, TelegramClient telegramClient) {
        pending.remove(id, state);
        if (messageId > 0) telegramClient.editMessageReplyMarkup(state.chatId, messageId, null);
        List<String> selected = state.selected.stream().map(state.prompt.choices()::get).toList();
        return CallbackOutcome.complete(formatAnswer(state.prompt, selected));
    }

    private List<List<KeyboardButton>> buttonsFor(String id, PendingQuestion state) {
        List<List<KeyboardButton>> rows = new ArrayList<>();
        for (int index = 0; index < state.prompt.choices().size(); index++) {
            boolean selected = state.selected.contains(index);
            String label = (selected ? "✓ " : "") + state.prompt.choices().get(index);
            rows.add(List.of(new KeyboardButton(label, CALLBACK_COMMAND + ":" + id + ":" + index)));
        }
        rows.add(List.of(new KeyboardButton("Other (type answer)", CALLBACK_COMMAND + ":" + id + ":" + ACTION_OTHER)));
        if (state.prompt.multiSelect()) {
            rows.add(List.of(new KeyboardButton("Done", CALLBACK_COMMAND + ":" + id + ":" + ACTION_DONE)));
        }
        return rows;
    }

    private Prompt parseSinglePrompt(String rawArguments) {
        try {
            JsonNode root = objectMapper.readTree(rawArguments);
            if (root == null || !root.isObject() || root.path("questions").isArray()) {
                return null;
            }
            String question = root.path("question").asText("").trim();
            JsonNode choicesNode = root.path("choices");
            if (question.isBlank() || !choicesNode.isArray()) return null;
            List<String> choices = new ArrayList<>();
            for (JsonNode choice : choicesNode) {
                String value = choice.asText("").trim();
                if (!value.isBlank()) choices.add(value);
                if (choices.size() == MAX_CHOICES) break;
            }
            return new Prompt(question, List.copyOf(choices), root.path("multi_select").asBoolean(false));
        } catch (Exception ex) {
            log.debug("Clarification arguments cannot be rendered as keyboard: {}", ex.getMessage());
            return null;
        }
    }

    private static Integer parseChoice(String action, int count) {
        try {
            int index = Integer.parseInt(action);
            return index >= 0 && index < count ? index : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatAnswer(Prompt prompt, List<String> answers) {
        String answer = String.join(", ", answers);
        return "Answer to clarification question '" + prompt.question() + "': " + answer;
    }

    private record Prompt(String question, List<String> choices, boolean multiSelect) {}

    private static final class PendingQuestion {
        private final long chatId;
        private final long threadId;
        private final Prompt prompt;
        private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        private volatile boolean awaitingTypedAnswer;

        private PendingQuestion(long chatId, long threadId, Prompt prompt) {
            this.chatId = chatId;
            this.threadId = threadId;
            this.prompt = prompt;
        }
    }

    public record CallbackOutcome(String answer, String acknowledgement, boolean complete) {
        static CallbackOutcome complete(String answer) { return new CallbackOutcome(answer, "Selected", true); }
        static CallbackOutcome pending(String acknowledgement) { return new CallbackOutcome(null, acknowledgement, false); }
        static CallbackOutcome invalid(String acknowledgement) { return new CallbackOutcome(null, acknowledgement, false); }
    }
}
