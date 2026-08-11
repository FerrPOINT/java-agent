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

@AgentTool(name = "clarify", description = "Ask the user a clarifying question when the request is ambiguous or missing required information. Supports open-ended questions (default) or multi-choice with up to 4 options plus an 'Other' choice.", toolset = "core")
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