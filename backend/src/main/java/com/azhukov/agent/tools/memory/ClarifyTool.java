package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

@AgentTool(name = "clarify", description = "Ask the user one or more questions when you need a decision, clarification, or feedback before proceeding. Pass every question in `questions` (1-5 entries) — a single question is a one-entry array, and several INDEPENDENT questions belong in ONE call (one form beats a chain of clarify calls; if one answer would change another question, ask separately). Per question: single-select (up to 4 choices — put your recommended option FIRST, the UI marks it \'(Recommended)\' and auto-appends an \'Other\' free-text row), multi-select (multi_select=true), or open-ended (omit choices). Options go ONLY in `choices`, never enumerated inside the question text (choices render as pickable rows; options written into the question are dead prose the user can\'t click). Result: {responses: [...]} in question order (plus timed_out=true if the user did not answer in time). On interactive platforms the tool BLOCKS until the user answers; prefer deciding low-stakes questions yourself; don\'t use this for dangerous-command confirmation (the terminal tool handles that).", toolset = "clarify")
@Component
public class ClarifyTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_CHOICES = 4;
    static final int MAX_QUESTIONS = 5;
    private static final String RECOMMENDED_LABEL = "(Recommended)";
    /** Hermes parity (clarify_tool.TIMEOUT_RESPONSE): the sentinel the platform
     *  returns on timeout — the agent proceeds on its best judgement. */
    static final String TIMEOUT_RESPONSE =
        "The user did not provide a response within the time limit. "
            + "Use your best judgement to make the choice and proceed.";

    private final com.azhukov.agent.core.tool.ClarifyGatewayStore clarifyStore;

    /**
     * Interactive constructor (streaming turns): registers pending entries in
     * the gateway store and blocks on the user's reply.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ClarifyTool(com.azhukov.agent.core.tool.ClarifyGatewayStore clarifyStore) {
        this.clarifyStore = clarifyStore;
    }

    /** Legacy/format-only constructor (tests, non-interactive contexts). */
    public ClarifyTool() {
        this.clarifyStore = null;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ToolResult rawValidation = validateRawArguments(arguments);
        if (rawValidation != null) {
            return rawValidation;
        }

        ClarifyArgs args;
        try {
            args = parseJson(arguments, ClarifyArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }
        boolean interactive = clarifyStore != null
            && ClarifyStreamBridge.sender(session == null ? null : session.id()) != null
            && session != null
            && session.getMetadata("clarifyChatId") != null;
        if (args.questions() != null && !args.questions().isEmpty()) {
            return interactive
                ? runBlockingBatch(args.questions(), session)
                : formatBatch(args.questions());
        }
        if (args.question() == null || args.question().isBlank()) {
            return jsonError("No question provided. Pass questions=[{question: '...', choices?: [...], multi_select?: bool}, ...] - a single question is a one-entry array.");
        }
        if (interactive) {
            return runBlockingSingle(args.question(), normalizeChoices(args.choices()), args.multiSelect(), session);
        }
        return ToolResult.ok(formatQuestion(args));
    }

    // ── Blocking path (Hermes clarify_tool callback contract) ──

    private ToolResult runBlockingSingle(String question, List<String> choices, boolean multiSelect, Session session) {
        List<String> shown = markRecommendedPublic(choices);
        ClarifyStreamBridge.Sender sender = ClarifyStreamBridge.sender(session == null ? null : session.id());
        com.azhukov.agent.core.tool.ClarifyGatewayStore.PendingClarify entry =
            clarifyStore.register(sessionKey(session), question, shown, multiSelect);
        sender.sendClarifyPrompt(singlePromptPayload(entry.clarifyId(), question, shown, multiSelect));
        String raw = clarifyStore.awaitResponse(entry, com.azhukov.agent.core.tool.ClarifyGatewayStore.DEFAULT_TIMEOUT_SECONDS);
        return ToolResult.ok(singleResultJson(question, choices, cleanAnswer(raw, multiSelect && !choices.isEmpty())));
    }

    private ToolResult runBlockingBatch(List<?> questions, Session session) {
        if (questions.size() > MAX_QUESTIONS) {
            return jsonError("questions supports at most " + MAX_QUESTIONS + " items.");
        }
        List<NormalizedQuestion> normalized = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            try {
                normalized.add(normalizeQuestion(questions.get(i), i));
            } catch (IllegalArgumentException e) {
                return jsonError(e.getMessage());
            }
        }
        ClarifyStreamBridge.Sender sender = ClarifyStreamBridge.sender(session == null ? null : session.id());
        // One SSE prompt per batch (adapter renders all questions on one card);
        // answers resolve sequentially: single-choice entries answer in order,
        // open-ended entries capture the typed message.
        StringBuilder promptText = new StringBuilder();
        List<com.azhukov.agent.core.tool.ClarifyGatewayStore.PendingClarify> entries = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            NormalizedQuestion q = normalized.get(i);
            List<String> shown = markRecommendedPublic(q.choices());
            promptText.append(i > 0 ? "\n\n" : "").append("Question ").append(i + 1).append(":\n")
                .append(formatQuestion(q.question(), shown, q.multiSelect()));
            entries.add(clarifyStore.register(sessionKey(session) + ":q" + i, q.question(), shown, q.multiSelect()));
        }
        sender.sendClarifyPrompt(batchPromptPayload(sessionKey(session), promptText.toString()));
        com.fasterxml.jackson.databind.node.ArrayNode responses = MAPPER.createArrayNode();
        boolean timedOut = false;
        for (int i = 0; i < entries.size(); i++) {
            String raw = clarifyStore.awaitResponse(entries.get(i),
                com.azhukov.agent.core.tool.ClarifyGatewayStore.DEFAULT_TIMEOUT_SECONDS);
            if (raw == null || raw.isBlank()) {
                timedOut = true;
                break;
            }
            NormalizedQuestion q = normalized.get(i);
            com.fasterxml.jackson.databind.node.ObjectNode item = responses.addObject();
            item.put("question", q.question());
            item.set("choices_offered", choicesArray(q.choices()));
            item.set("user_response", answerNode(cleanAnswer(raw, q.multiSelect())));
        }
        for (int i = 0; i < normalized.size(); i++) {
            // any unanswered entries become "" responses (Hermes _batch_result)
            if (responses.size() <= i) {
                NormalizedQuestion q = normalized.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode item = responses.addObject();
                item.put("question", q.question());
                item.set("choices_offered", choicesArray(q.choices()));
                item.put("user_response", answerNode(""));
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode result = MAPPER.createObjectNode();
        result.set("responses", responses);
        if (timedOut) {
            result.put("timed_out", true);
        }
        return ToolResult.ok(result.toString());
    }

    private String sessionKey(Session session) {
        return session != null && session.id() != null ? session.id().toString() : "default";
    }

    private String singlePromptPayload(String clarifyId, String question, List<String> choices, boolean multiSelect) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = MAPPER.createObjectNode();
        payload.put("clarifyId", clarifyId);
        payload.put("question", question);
        payload.set("choices", choicesArray(choices));
        payload.put("multi_select", multiSelect);
        return payload.toString();
    }

    private String batchPromptPayload(String sessionKey, String promptText) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = MAPPER.createObjectNode();
        payload.put("sessionKey", sessionKey);
        payload.put("prompt", promptText);
        payload.put("batch", true);
        return payload.toString();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode choicesArray(List<String> choices) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        if (choices != null) {
            choices.forEach(arr::add);
        }
        return arr;
    }

    private com.fasterxml.jackson.databind.JsonNode answerNode(String answer) {
        // multi-select answers arrive as a JSON array string; single as text
        if (answer != null && answer.startsWith("[")) {
            try {
                return MAPPER.readTree(answer);
            } catch (Exception ignored) {
            }
        }
        return MAPPER.getNodeFactory().textNode(answer == null ? "" : answer);
    }

    /** Hermes parity (_clean_answer): strip the (Recommended) label from a locked answer. */
    static String cleanAnswer(String raw, boolean multi) {
        if (raw == null) {
            return "";
        }
        if (multi) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(raw);
                if (arr.isArray()) {
                    com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
                    arr.forEach(n -> out.add(stripRecommended(n.asText())));
                    return out.toString();
                }
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder("[");
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String s = stripRecommended(parts[i].trim());
                if (!s.isEmpty()) {
                    if (sb.length() > 1) sb.append(",");
                    sb.append("\"").append(s).append("\"");
                }
            }
            return sb.append("]").toString();
        }
        return stripRecommended(raw);
    }

    static String stripRecommended(String text) {
        String stripped = text == null ? "" : text.trim();
        String suffix = " " + RECOMMENDED_LABEL;
        if (stripped.regionMatches(true, Math.max(0, stripped.length() - suffix.length()), suffix, 0, suffix.length())) {
            return stripped.substring(0, stripped.length() - suffix.length()).trim();
        }
        return stripped;
    }

    private String singleResultJson(String question, List<String> choices, String userResponse) {
        com.fasterxml.jackson.databind.node.ObjectNode result = MAPPER.createObjectNode();
        result.put("question", question);
        result.set("choices_offered", choicesArray(choices));
        result.set("user_response", answerNode(userResponse));
        return result.toString();
    }

    static List<String> markRecommendedPublic(List<String> choices) {
        return markRecommended(new ArrayList<>(choices == null ? List.of() : choices));
    }

    static ToolResult formatBatch(List<?> questions) {
        if (questions.size() > MAX_QUESTIONS) {
            return jsonError("questions supports at most " + MAX_QUESTIONS + " items.");
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            NormalizedQuestion item;
            try {
                item = normalizeQuestion(questions.get(i), i);
            } catch (IllegalArgumentException e) {
                return jsonError(e.getMessage());
            }
            if (i > 0) output.append("\n\n");
            output.append("Question ").append(i + 1).append(":\n");
            output.append(formatQuestion(item.question(), item.choices(), item.multiSelect()));
        }
        return ToolResult.ok(output.toString());
    }

    private static ToolResult validateRawArguments(String arguments) {
        JsonNode root;
        try {
            root = ToolHandler.TOOL_ARGS_MAPPER.readTree(arguments);
        } catch (Exception e) {
            return jsonError("Invalid tool arguments: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return jsonError("Invalid tool arguments: expected an object");
        }
        if (isExplicitNonArray(root, "questions")) {
            return jsonError("questions must be an array of question objects.");
        }
        if (isExplicitNonArray(root, "choices")) {
            return jsonError("choices must be a list of strings.");
        }
        return null;
    }

    private static boolean isExplicitNonArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && !value.isNull() && !value.isArray();
    }

    private static ToolResult jsonError(String error) {
        String message = error == null || error.isBlank() ? "Clarify failed" : error;
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.put("error", message);
        return new ToolResult(false, response.toString(), message);
    }

    static String formatQuestion(ClarifyArgs args) {
        return formatQuestion(args.question(), args.choices(), args.multiSelect());
    }

    static String formatQuestion(String question, List<?> choices, boolean multiSelect) {
        List<String> normalized = normalizeChoices(choices);
        if (normalized.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder(question);
        sb.append("\n");
        for (int i = 0; i < normalized.size(); i++) {
            sb.append(i + 1).append(". ").append(normalized.get(i)).append("\n");
        }
        sb.append(normalized.size() + 1).append(". Other (type answer)");
        if (multiSelect) {
            sb.append("\nSelect all that apply.");
        }
        return sb.toString();
    }

    private static NormalizedQuestion normalizeQuestion(Object item, int index) {
        if (item instanceof String text) {
            String question = text.trim();
            if (question.isBlank()) {
                throw new IllegalArgumentException("questions[" + index + "].question must be non-empty text.");
            }
            return new NormalizedQuestion(question, List.of(), false);
        }
        if (item instanceof ClarifyQuestion question) {
            String text = question.question() != null ? question.question().trim() : "";
            if (text.isBlank()) {
                throw new IllegalArgumentException("questions[" + index + "].question must be non-empty text.");
            }
            return new NormalizedQuestion(text, normalizeChoices(question.choices()), question.multiSelect());
        }
        if (item instanceof Map<?, ?> map) {
            Object textValue = map.get("question");
            String text = textValue != null ? textValue.toString().trim() : "";
            if (text.isBlank()) {
                throw new IllegalArgumentException("questions[" + index + "].question must be non-empty text.");
            }
            Object choicesValue = map.get("choices");
            if (choicesValue != null && !(choicesValue instanceof List<?>)) {
                throw new IllegalArgumentException("questions[" + index + "].choices must be a list.");
            }
            Object multiSelectValue = map.containsKey("multi_select")
                ? map.get("multi_select")
                : map.get("multiSelect");
            boolean multiSelect = asBoolean(multiSelectValue);
            return new NormalizedQuestion(text, normalizeChoices((List<?>) choicesValue), multiSelect);
        }
        throw new IllegalArgumentException("questions[" + index + "] must be an object with a 'question'.");
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }

    private static List<String> normalizeChoices(List<?> choices) {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (Object choice : choices) {
            String flattened = flattenChoice(choice);
            if (!flattened.isBlank()) {
                normalized.add(flattened);
                if (normalized.size() >= MAX_CHOICES) {
                    break;
                }
            }
        }
        return markRecommended(normalized);
    }

    private static String flattenChoice(Object choice) {
        if (choice == null) {
            return "";
        }
        if (choice instanceof String text) {
            return text.trim();
        }
        if (choice instanceof Map<?, ?> map) {
            for (String key : List.of("label", "description", "text", "title")) {
                Object value = map.get(key);
                if (value instanceof String text && !text.trim().isBlank()) {
                    return text.trim();
                }
            }
            return "";
        }
        if (choice instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                String part = flattenChoice(item);
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return String.join(" ", parts).trim();
        }
        if (choice.getClass().isArray()) {
            List<String> parts = new ArrayList<>();
            int length = Array.getLength(choice);
            for (int i = 0; i < length; i++) {
                String part = flattenChoice(Array.get(choice, i));
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return String.join(" ", parts).trim();
        }
        return choice.toString().trim();
    }

    private static List<String> markRecommended(List<String> choices) {
        if (choices.size() < 2) {
            return choices;
        }
        String first = choices.get(0);
        if (first.regionMatches(true, Math.max(0, first.length() - RECOMMENDED_LABEL.length()),
            RECOMMENDED_LABEL, 0, RECOMMENDED_LABEL.length())) {
            return choices;
        }
        List<String> marked = new ArrayList<>(choices);
        marked.set(0, first + " " + RECOMMENDED_LABEL);
        return marked;
    }

    public static class ClarifyArgs {
        @ToolParam(description = "The clarifying question to present to the user. Required unless questions is provided.", required = false)
        private String question;

        @ToolParam(description = "Up to 4 predefined answer choices for multi-choice mode. When provided, a numbered list with an 'Other (type answer)' option is appended. When omitted, the question is open-ended.", required = false)
        private List<Object> choices;

        @JsonProperty("multi_select")
        @com.fasterxml.jackson.annotation.JsonAlias("multiSelect")
        @ToolParam(description = "When true, the user may select multiple choices. Has no effect without choices.", required = false)
        private boolean multiSelect;

        @ToolParam(description = "Up to 5 independent questions asked in one batch. Each item: {id?, question, choices?, multi_select?}. When present, single-question fields are ignored.", required = false)
        private List<Object> questions;

        public String question() { return question; }
        public List<Object> choices() { return choices; }
        public boolean multiSelect() { return multiSelect; }
        public List<Object> questions() { return questions; }
    }

    public static class ClarifyQuestion {
        private String id;
        private String question;
        private List<Object> choices;
        @JsonProperty("multi_select")
        @com.fasterxml.jackson.annotation.JsonAlias("multiSelect")
        private boolean multiSelect;

        public String id() { return id; }
        public String question() { return question; }
        public List<Object> choices() { return choices; }
        public boolean multiSelect() { return multiSelect; }
    }

    private record NormalizedQuestion(String question, List<String> choices, boolean multiSelect) {}
}
