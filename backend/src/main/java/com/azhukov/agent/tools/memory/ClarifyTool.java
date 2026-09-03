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

@AgentTool(name = "clarify", description = "Format one or more clarifying questions when you need clarification, feedback, or a decision before proceeding. In this Java execution context the tool returns the prompt text to relay; it does not block waiting for user input. Supports three prompt shapes:\n\n1. **Single-select multiple choice** — provide up to 4 choices. Put the choice you recommend FIRST; it is labeled '(Recommended)' when there is more than one choice, and an 'Other (type answer)' row is appended.\n2. **Multi-select multiple choice** — set multi_select=true. The formatted prompt includes 'Select all that apply.'\n3. **Open-ended** — omit choices entirely.\n\nYou can ask SEVERAL independent questions in ONE call: pass questions in the `questions` array (each with its own choices/multi_select, any mix of the three shapes). STRONGLY preferred over a chain of single-question clarify calls when you need several independent answers.\nCRITICAL: when you are offering options, put each option ONLY in the `choices` array — NEVER enumerate the options inside the `question` text. Right: question='Which deployment target?', choices=['staging', 'prod']. Wrong: question='Which target? 1) staging 2) prod', choices=[].\n\nUse this tool when:\n- The task is ambiguous and you need the user to choose an approach\n- You want post-task feedback ('How did that work out?')\n- You want to offer to save a skill or update memory\n- A decision has meaningful trade-offs the user should weigh in on\n\nDo NOT use this tool for simple yes/no confirmation of dangerous commands (the terminal tool handles that). Prefer making a reasonable default choice yourself when the decision is low-stakes.", toolset = "clarify")
@Component
public class ClarifyTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_CHOICES = 4;
    static final int MAX_QUESTIONS = 5;
    private static final String RECOMMENDED_LABEL = "(Recommended)";

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
        if (args.questions() != null && !args.questions().isEmpty()) {
            return formatBatch(args.questions());
        }
        if (args.question() == null || args.question().isBlank()) {
            return jsonError("No question provided. Pass questions=[{question: '...', choices?: [...], multi_select?: bool}, ...] - a single question is a one-entry array.");
        }
        return ToolResult.ok(formatQuestion(args));
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
        private boolean multiSelect;

        public String id() { return id; }
        public String question() { return question; }
        public List<Object> choices() { return choices; }
        public boolean multiSelect() { return multiSelect; }
    }

    private record NormalizedQuestion(String question, List<String> choices, boolean multiSelect) {}
}
