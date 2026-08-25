package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

@AgentTool(name = "clarify", description = "Ask the user a question when you need clarification, feedback, or a decision before proceeding. Supports three modes:\n\n1. **Single-select multiple choice** — provide up to 4 choices. The user picks one or types their own answer via a 5th 'Other' option. List the choice you recommend FIRST: the UI labels it '(Recommended)' and highlights it by default.\n2. **Multi-select multiple choice** — set multi_select=true. The user can select multiple options via checkboxes. user_response will be a list of selected choices.\n3. **Open-ended** — omit choices entirely. The user types a free-form response.\n\nYou can also ask SEVERAL questions in ONE call: pass questions in the `questions` array (each with its own choices/multi_select, any mix of the three modes). The user answers them all on a single form, in any order. STRONGLY preferred over a chain of single-question clarify calls when you need several independent answers.\nCRITICAL: when you are offering options, put each option ONLY in the `choices` array — NEVER enumerate the options inside the `question` text. The UI renders `choices` as selectable rows; options written into the question string render as dead prose the user can't pick. Right: question='Which deployment target?', choices=['staging', 'prod']. Wrong: question='Which target? 1) staging 2) prod', choices=[].\n\nUse this tool when:\n- The task is ambiguous and you need the user to choose an approach\n- You want post-task feedback ('How did that work out?')\n- You want to offer to save a skill or update memory\n- A decision has meaningful trade-offs the user should weigh in on\n\nDo NOT use this tool for simple yes/no confirmation of dangerous commands (the terminal tool handles that). Prefer making a reasonable default choice yourself when the decision is low-stakes.", toolset = "core")
@Component
public class ClarifyTool implements ToolHandler {

    static final int MAX_CHOICES = 4;
    static final int MAX_QUESTIONS = 5;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClarifyArgs args = parseJson(arguments, ClarifyArgs.class);
        if (args.questions() != null && !args.questions().isEmpty()) {
            return formatBatch(args.questions());
        }
        if (args.question() == null || args.question().isBlank()) {
            return ToolResult.fail("question is required when questions is not provided");
        }
        return ToolResult.ok(formatQuestion(args));
    }

    static ToolResult formatBatch(List<ClarifyQuestion> questions) {
        if (questions.size() > MAX_QUESTIONS) {
            return ToolResult.fail("questions supports at most " + MAX_QUESTIONS + " items.");
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            ClarifyQuestion item = questions.get(i);
            if (item == null || item.question() == null || item.question().isBlank()) {
                return ToolResult.fail("questions[" + i + "].question must be non-empty text.");
            }
            if (i > 0) output.append("\n\n");
            output.append("Question ").append(i + 1).append(":\n");
            output.append(formatQuestion(item.question(), item.choices(), item.multiSelect()));
        }
        return ToolResult.ok(output.toString());
    }

    static String formatQuestion(ClarifyArgs args) {
        return formatQuestion(args.question(), args.choices(), args.multiSelect());
    }

    static String formatQuestion(String question, List<String> choices, boolean multiSelect) {
        if (choices == null || choices.isEmpty()) {
            return question;
        }
        List<String> truncated = choices.size() > MAX_CHOICES
            ? choices.subList(0, MAX_CHOICES)
            : choices;
        StringBuilder sb = new StringBuilder(question);
        sb.append("\n");
        for (int i = 0; i < truncated.size(); i++) {
            sb.append(i + 1).append(". ").append(truncated.get(i)).append("\n");
        }
        sb.append(truncated.size() + 1).append(". Other (type answer)");
        if (multiSelect) {
            sb.append("\nSelect all that apply.");
        }
        return sb.toString();
    }

    public static class ClarifyArgs {
        @ToolParam(description = "The clarifying question to present to the user. Required unless questions is provided.", required = false)
        private String question;

        @ToolParam(description = "Up to 4 predefined answer choices for multi-choice mode. When provided, a numbered list with an 'Other (type answer)' option is appended. When omitted, the question is open-ended.", required = false)
        private List<String> choices;

        @JsonProperty("multi_select")
        @ToolParam(description = "When true, the user may select multiple choices. Has no effect without choices.", required = false)
        private boolean multiSelect;

        @ToolParam(description = "Up to 5 independent questions asked in one batch. Each item: {id?, question, choices?, multi_select?}. When present, single-question fields are ignored.", required = false)
        private List<ClarifyQuestion> questions;

        public String question() { return question; }
        public List<String> choices() { return choices; }
        public boolean multiSelect() { return multiSelect; }
        public List<ClarifyQuestion> questions() { return questions; }
    }

    public static class ClarifyQuestion {
        private String id;
        private String question;
        private List<String> choices;
        @JsonProperty("multi_select")
        private boolean multiSelect;

        public String id() { return id; }
        public String question() { return question; }
        public List<String> choices() { return choices; }
        public boolean multiSelect() { return multiSelect; }
    }
}