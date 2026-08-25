package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

@AgentTool(name = "clarify", description = "Ask the user a question when you need clarification, feedback, or a decision before proceeding. Supports three modes:\n\n1. **Single-select multiple choice** — provide up to 4 choices. The user picks one or types their own answer via a 5th 'Other' option. List the choice you recommend FIRST: the UI labels it '(Recommended)' and highlights it by default.\n2. **Multi-select multiple choice** — set multi_select=true. The user can select multiple options via checkboxes. user_response will be a list of selected choices.\n3. **Open-ended** — omit choices entirely. The user types a free-form response.\n\nYou can also ask SEVERAL questions in ONE call: pass questions in the `questions` array (each with its own choices/multi_select, any mix of the three modes). The user answers them all on a single form, in any order. STRONGLY preferred over a chain of single-question clarify calls when you need several independent answers.\nCRITICAL: when you are offering options, put each option ONLY in the `choices` array — NEVER enumerate the options inside the `question` text. The UI renders `choices` as selectable rows; options written into the question string render as dead prose the user can't pick. Right: question='Which deployment target?', choices=['staging', 'prod']. Wrong: question='Which target? 1) staging 2) prod', choices=[].\n\nUse this tool when:\n- The task is ambiguous and you need the user to choose an approach\n- You want post-task feedback ('How did that work out?')\n- You want to offer to save a skill or update memory\n- A decision has meaningful trade-offs the user should weigh in on\n\nDo NOT use this tool for simple yes/no confirmation of dangerous commands (the terminal tool handles that). Prefer making a reasonable default choice yourself when the decision is low-stakes.", toolset = "core")
@Component
public class ClarifyTool implements ToolHandler {

    static final int MAX_CHOICES = 4;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClarifyArgs args = parseJson(arguments, ClarifyArgs.class);
        String formatted = formatQuestion(args);
        return ToolResult.ok(formatted);
    }

    static String formatQuestion(ClarifyArgs args) {
        List<String> choices = args.choices();
        if (choices == null || choices.isEmpty()) {
            return args.question();
        }
        // Truncate to MAX_CHOICES + append "Other" option
        List<String> truncated = choices.size() > MAX_CHOICES
            ? choices.subList(0, MAX_CHOICES)
            : choices;
        StringBuilder sb = new StringBuilder(args.question());
        sb.append("\n");
        for (int i = 0; i < truncated.size(); i++) {
            sb.append(i + 1).append(". ").append(truncated.get(i)).append("\n");
        }
        sb.append(truncated.size() + 1).append(". Other (type answer)");
        return sb.toString();
    }

    public static class ClarifyArgs {
        @ToolParam(description = "The clarifying question to present to the user", required = true)
        private String question;

        @ToolParam(description = "Up to 4 predefined answer choices for multi-choice mode. When provided, a numbered list with an 'Other (type answer)' option is appended. When omitted, the question is open-ended.", required = false)
        private List<String> choices;

        public String question() { return question; }
        public List<String> choices() { return choices; }
    }
}